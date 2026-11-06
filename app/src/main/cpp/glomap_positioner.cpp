#include <jni.h>
#include <ceres/ceres.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

namespace {

struct BataPointCameraResidual {
    BataPointCameraResidual(const double* observation) {
        for (int i = 0; i < 3; ++i) direction[i] = observation[i];
    }

    template <typename T>
    bool operator()(const T* camera_center,
                    const T* point,
                    const T* scale,
                    T* residuals) const {
        for (int i = 0; i < 3; ++i) {
            residuals[i] = T(direction[i]) -
                    scale[0] * (point[i] - camera_center[i]);
        }
        return true;
    }

    double direction[3];
};

bool AllFinite(const std::vector<double>& values) {
    return std::all_of(values.begin(), values.end(),
                       [](double value) { return std::isfinite(value); });
}

}  // namespace

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_trackstudio_app_tracking_glomap_GlomapNativeSolver_nativeSolve(
        JNIEnv* env,
        jobject,
        jdoubleArray frame_centers_array,
        jdoubleArray points_array,
        jintArray frame_indices_array,
        jintArray track_indices_array,
        jdoubleArray directions_array,
        jdoubleArray initial_scales_array,
        jdoubleArray loss_scales_array,
        jint max_iterations,
        jint num_threads) {
    if (!frame_centers_array || !points_array || !frame_indices_array ||
        !track_indices_array || !directions_array || !initial_scales_array ||
        !loss_scales_array) {
        return env->NewDoubleArray(0);
    }

    const jsize frame_value_count = env->GetArrayLength(frame_centers_array);
    const jsize point_value_count = env->GetArrayLength(points_array);
    const jsize constraint_count = env->GetArrayLength(frame_indices_array);

    if (frame_value_count == 0 || frame_value_count % 3 != 0 ||
        point_value_count == 0 || point_value_count % 3 != 0 ||
        constraint_count == 0 ||
        env->GetArrayLength(track_indices_array) != constraint_count ||
        env->GetArrayLength(directions_array) != constraint_count * 3 ||
        env->GetArrayLength(initial_scales_array) != constraint_count ||
        env->GetArrayLength(loss_scales_array) != constraint_count ||
        max_iterations <= 0 || num_threads <= 0) {
        return env->NewDoubleArray(0);
    }

    const int frame_count = frame_value_count / 3;
    const int point_count = point_value_count / 3;

    std::vector<double> frame_centers(frame_value_count);
    std::vector<double> points(point_value_count);
    std::vector<jint> frame_indices(constraint_count);
    std::vector<jint> track_indices(constraint_count);
    std::vector<double> directions(constraint_count * 3);
    std::vector<double> scales(constraint_count);
    std::vector<double> loss_scales(constraint_count);

    env->GetDoubleArrayRegion(frame_centers_array, 0, frame_value_count,
                              frame_centers.data());
    env->GetDoubleArrayRegion(points_array, 0, point_value_count, points.data());
    env->GetIntArrayRegion(frame_indices_array, 0, constraint_count,
                           frame_indices.data());
    env->GetIntArrayRegion(track_indices_array, 0, constraint_count,
                           track_indices.data());
    env->GetDoubleArrayRegion(directions_array, 0, constraint_count * 3,
                              directions.data());
    env->GetDoubleArrayRegion(initial_scales_array, 0, constraint_count,
                              scales.data());
    env->GetDoubleArrayRegion(loss_scales_array, 0, constraint_count,
                              loss_scales.data());

    if (env->ExceptionCheck() || !AllFinite(frame_centers) ||
        !AllFinite(points) || !AllFinite(directions) ||
        !AllFinite(scales) || !AllFinite(loss_scales)) {
        return env->NewDoubleArray(0);
    }

    for (int i = 0; i < constraint_count; ++i) {
        if (frame_indices[i] < 0 || frame_indices[i] >= frame_count ||
            track_indices[i] < 0 || track_indices[i] >= point_count ||
            loss_scales[i] <= 0.0) {
            return env->NewDoubleArray(0);
        }
        scales[i] = std::max(1e-5, scales[i]);
    }

    ceres::Problem problem;
    for (int i = 0; i < constraint_count; ++i) {
        auto* cost = new ceres::AutoDiffCostFunction<
                BataPointCameraResidual, 3, 3, 3, 1>(
                new BataPointCameraResidual(&directions[i * 3]));
        ceres::LossFunction* loss = new ceres::ScaledLoss(
                new ceres::HuberLoss(0.1), loss_scales[i],
                ceres::TAKE_OWNERSHIP);
        double* camera = frame_centers.data() + frame_indices[i] * 3;
        double* point = points.data() + track_indices[i] * 3;
        double* scale = &scales[i];
        problem.AddResidualBlock(cost, loss, camera, point, scale);
        problem.SetParameterLowerBound(scale, 0, 1e-5);
    }

    // GLOMAP's ONLY_POINTS gauge handling fixes the first active observation
    // scale. Its parameter ordering puts scales before points and positions.
    for (double& scale : scales) {
        problem.SetParameterBlockConstant(&scale);
        break;
    }

    auto ordering = std::make_shared<ceres::ParameterBlockOrdering>();
    for (double& scale : scales) {
        if (problem.HasParameterBlock(&scale)) ordering->AddElementToGroup(&scale, 0);
    }
    for (int i = 0; i < point_count; ++i) {
        double* point = points.data() + i * 3;
        if (problem.HasParameterBlock(point)) ordering->AddElementToGroup(point, 1);
    }
    for (int i = 0; i < frame_count; ++i) {
        double* center = frame_centers.data() + i * 3;
        if (problem.HasParameterBlock(center)) ordering->AddElementToGroup(center, 2);
    }

    ceres::Solver::Options options;
    options.linear_solver_type = ceres::SPARSE_SCHUR;
    options.preconditioner_type = ceres::CLUSTER_TRIDIAGONAL;
    options.max_num_iterations = std::min(max_iterations, 100);
    options.num_threads = num_threads;
    options.function_tolerance = 1e-5;
    options.minimizer_progress_to_stdout = false;
    options.linear_solver_ordering = ordering;

    ceres::Solver::Summary summary;
    ceres::Solve(options, &problem, &summary);

    if (!summary.IsSolutionUsable() || !AllFinite(frame_centers) ||
        !AllFinite(points) || !AllFinite(scales)) {
        return env->NewDoubleArray(0);
    }

    std::vector<double> output;
    output.reserve(4 + frame_centers.size() + points.size() + scales.size());
    output.push_back(1.0);
    output.push_back(summary.initial_cost);
    output.push_back(summary.final_cost);
    output.push_back(static_cast<double>(summary.iterations.size()));
    output.insert(output.end(), frame_centers.begin(), frame_centers.end());
    output.insert(output.end(), points.begin(), points.end());
    output.insert(output.end(), scales.begin(), scales.end());

    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(output.size()));
    if (result) {
        env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                  output.data());
    }
    return result;
}
