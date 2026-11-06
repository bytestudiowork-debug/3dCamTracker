#include <jni.h>
#include <ceres/ceres.h>
#include "glomap_progress_callback.h"

#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>

namespace {

struct ReprojectionResidual {
    ReprojectionResidual(const double* rotation,
                         double observed_x,
                         double observed_y,
                         const double* intrinsics) :
            observed_x(observed_x),
            observed_y(observed_y),
            fx(intrinsics[0]),
            skew(intrinsics[1]),
            cx(intrinsics[2]),
            fy(intrinsics[3]),
            cy(intrinsics[4]) {
        std::copy(rotation, rotation + 9, this->rotation);
    }

    template <typename T>
    bool operator()(const T* camera_center,
                    const T* point,
                    T* residuals) const {
        const T dx = point[0] - camera_center[0];
        const T dy = point[1] - camera_center[1];
        const T dz = point[2] - camera_center[2];

        const T x = T(rotation[0]) * dx + T(rotation[1]) * dy + T(rotation[2]) * dz;
        const T y = T(rotation[3]) * dx + T(rotation[4]) * dy + T(rotation[5]) * dz;
        const T z = T(rotation[6]) * dx + T(rotation[7]) * dy + T(rotation[8]) * dz;

        // Keep projections finite while strongly penalizing points behind or
        // extremely close to the camera plane.
        const T safe_z = z > T(1e-4) ? z : T(1e-4);
        const T predicted_x = T(fx) * x / safe_z + T(skew) * y / safe_z + T(cx);
        const T predicted_y = T(fy) * y / safe_z + T(cy);
        residuals[0] = predicted_x - T(observed_x);
        residuals[1] = predicted_y - T(observed_y);
        residuals[2] = z > T(1e-4) ? T(0) : (T(1e-4) - z) * T(100);
        return true;
    }

    double rotation[9];
    double observed_x;
    double observed_y;
    double fx;
    double skew;
    double cx;
    double fy;
    double cy;
};

bool AllFinite(const std::vector<double>& values) {
    return std::all_of(values.begin(), values.end(),
                       [](double value) { return std::isfinite(value); });
}

jdoubleArray EmptyResult(JNIEnv* env) {
    return env->NewDoubleArray(0);
}

}  // namespace

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_trackstudio_app_tracking_glomap_GlomapGlobalBundleAdjuster_nativeSolve(
        JNIEnv* env,
        jobject,
        jdoubleArray camera_centers_array,
        jdoubleArray rotations_array,
        jdoubleArray points_array,
        jintArray frame_indices_array,
        jintArray track_indices_array,
        jdoubleArray image_points_array,
        jdoubleArray intrinsics_array,
        jint max_iterations,
        jint num_threads,
        jobject progress_listener) {
    if (!camera_centers_array || !rotations_array || !points_array ||
        !frame_indices_array || !track_indices_array || !image_points_array ||
        !intrinsics_array) {
        return EmptyResult(env);
    }

    const jsize center_values = env->GetArrayLength(camera_centers_array);
    const jsize rotation_values = env->GetArrayLength(rotations_array);
    const jsize point_values = env->GetArrayLength(points_array);
    const jsize observation_count = env->GetArrayLength(frame_indices_array);
    if (center_values == 0 || center_values % 3 != 0 ||
        rotation_values != (center_values / 3) * 9 ||
        point_values == 0 || point_values % 3 != 0 ||
        observation_count < 2 ||
        env->GetArrayLength(track_indices_array) != observation_count ||
        env->GetArrayLength(image_points_array) != observation_count * 2 ||
        env->GetArrayLength(intrinsics_array) != 5 ||
        max_iterations <= 0 || num_threads <= 0) {
        return EmptyResult(env);
    }

    const int frame_count = center_values / 3;
    const int point_count = point_values / 3;
    std::vector<double> centers(center_values);
    std::vector<double> rotations(rotation_values);
    std::vector<double> points(point_values);
    std::vector<jint> frame_indices(observation_count);
    std::vector<jint> track_indices(observation_count);
    std::vector<double> image_points(observation_count * 2);
    std::vector<double> intrinsics(5);

    env->GetDoubleArrayRegion(camera_centers_array, 0, center_values, centers.data());
    env->GetDoubleArrayRegion(rotations_array, 0, rotation_values, rotations.data());
    env->GetDoubleArrayRegion(points_array, 0, point_values, points.data());
    env->GetIntArrayRegion(frame_indices_array, 0, observation_count, frame_indices.data());
    env->GetIntArrayRegion(track_indices_array, 0, observation_count, track_indices.data());
    env->GetDoubleArrayRegion(image_points_array, 0, observation_count * 2, image_points.data());
    env->GetDoubleArrayRegion(intrinsics_array, 0, 5, intrinsics.data());

    if (env->ExceptionCheck() || !AllFinite(centers) || !AllFinite(rotations) ||
        !AllFinite(points) || !AllFinite(image_points) || !AllFinite(intrinsics) ||
        intrinsics[0] <= 0.0 || intrinsics[3] <= 0.0) {
        return EmptyResult(env);
    }

    for (int i = 0; i < observation_count; ++i) {
        if (frame_indices[i] < 0 || frame_indices[i] >= frame_count ||
            track_indices[i] < 0 || track_indices[i] >= point_count) {
            return EmptyResult(env);
        }
    }

    ceres::Problem problem;
    auto ordering = std::make_shared<ceres::ParameterBlockOrdering>();
    for (int i = 0; i < observation_count; ++i) {
        const int frame = frame_indices[i];
        const int track = track_indices[i];
        double* center = centers.data() + frame * 3;
        double* point = points.data() + track * 3;
        const double* rotation = rotations.data() + frame * 9;
        auto* cost = new ceres::AutoDiffCostFunction<ReprojectionResidual, 3, 3, 3>(
                new ReprojectionResidual(rotation, image_points[i * 2],
                                         image_points[i * 2 + 1], intrinsics.data()));
        problem.AddResidualBlock(cost, new ceres::HuberLoss(2.0), center, point);
        if (problem.HasParameterBlock(point)) ordering->AddElementToGroup(point, 0);
        if (problem.HasParameterBlock(center)) ordering->AddElementToGroup(center, 1);
    }

    // Fix the first camera centre and the most separated second centre to
    // remove the global translation, rotation and scale gauge.
    problem.SetParameterBlockConstant(centers.data());
    int scale_anchor = -1;
    double maximum_distance_squared = 0.0;
    for (int frame = 1; frame < frame_count; ++frame) {
        const double dx = centers[frame * 3] - centers[0];
        const double dy = centers[frame * 3 + 1] - centers[1];
        const double dz = centers[frame * 3 + 2] - centers[2];
        const double distance_squared = dx * dx + dy * dy + dz * dz;
        if (distance_squared > maximum_distance_squared) {
            maximum_distance_squared = distance_squared;
            scale_anchor = frame;
        }
    }
    if (scale_anchor < 0 || maximum_distance_squared < 1e-8) {
        return EmptyResult(env);
    }
    problem.SetParameterBlockConstant(centers.data() + scale_anchor * 3);

    ceres::Solver::Options options;
    // Dense Schur is usually faster for bundle adjustment with about 100 cameras.
    if (frame_count <= 100) {
        options.linear_solver_type = ceres::DENSE_SCHUR;
    } else {
        options.linear_solver_type = ceres::SPARSE_SCHUR;
        options.preconditioner_type = ceres::JACOBI;
    }
    options.max_num_iterations = std::min(max_iterations, 60);
    options.num_threads = std::min(num_threads, 4);
    options.function_tolerance = 1e-6;
    options.gradient_tolerance = 1e-10;
    options.minimizer_progress_to_stdout = false;
    options.linear_solver_ordering = ordering;

    trackstudio::JniProgressCallback progress_callback(env, progress_listener, std::min(max_iterations, 60));
    if (progress_callback.valid()) options.callbacks.push_back(&progress_callback);
    ceres::Solver::Summary summary;
    ceres::Solve(options, &problem, &summary);
    if (!summary.IsSolutionUsable() || !std::isfinite(summary.initial_cost) ||
        !std::isfinite(summary.final_cost) || !AllFinite(centers) || !AllFinite(points)) {
        return EmptyResult(env);
    }

    std::vector<double> output;
    output.reserve(4 + centers.size() + points.size());
    output.push_back(1.0);
    output.push_back(summary.initial_cost);
    output.push_back(summary.final_cost);
    output.push_back(static_cast<double>(summary.iterations.size()));
    output.insert(output.end(), centers.begin(), centers.end());
    output.insert(output.end(), points.begin(), points.end());

    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(output.size()));
    if (result) {
        env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}
