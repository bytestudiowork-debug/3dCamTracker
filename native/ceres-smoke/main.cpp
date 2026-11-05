#include <array>
#include <iostream>
#include <memory>
#include <ceres/ceres.h>

struct PairResidual {
  explicit PairResidual(const std::array<double, 3>& target) : target_(target) {}
  template <typename T>
  bool operator()(const T* camera, const T* point, T* residual) const {
    for (int i = 0; i < 3; ++i) residual[i] = camera[i] + point[i] - T(target_[i]);
    return true;
  }
  std::array<double, 3> target_;
};

int main() {
  constexpr int kCameraCount = 4;
  constexpr int kPointCount = 12;
  double cameras[kCameraCount][3] = {};
  double points[kPointCount][3] = {};
  for (int camera = 1; camera < kCameraCount; ++camera) {
    cameras[camera][0] = 0.2 * camera;
    cameras[camera][1] = -0.1 * camera;
    cameras[camera][2] = 0.05 * camera;
  }
  for (int point = 0; point < kPointCount; ++point) {
    points[point][0] = 0.3 * point;
    points[point][1] = -0.2 * point;
    points[point][2] = 0.1 * point;
  }

  ceres::Problem problem;
  for (int camera = 0; camera < kCameraCount; ++camera) problem.AddParameterBlock(cameras[camera], 3);
  for (int point = 0; point < kPointCount; ++point) problem.AddParameterBlock(points[point], 3);
  problem.SetParameterBlockConstant(cameras[0]);

  auto ordering = std::make_shared<ceres::ParameterBlockOrdering>();
  for (int point = 0; point < kPointCount; ++point) ordering->AddElementToGroup(points[point], 0);
  for (int camera = 0; camera < kCameraCount; ++camera) ordering->AddElementToGroup(cameras[camera], 1);

  for (int point = 0; point < kPointCount; ++point) {
    const std::array<double, 3> target = {1.0 + 0.1 * point, 2.0 - 0.05 * point, 3.0 + 0.02 * point};
    const int second_camera = 1 + (point % (kCameraCount - 1));
    for (const int camera : {0, second_camera}) {
      auto* cost = new ceres::AutoDiffCostFunction<PairResidual, 3, 3, 3>(new PairResidual(target));
      problem.AddResidualBlock(cost, nullptr, cameras[camera], points[point]);
    }
  }

  ceres::Solver::Options options;
  options.linear_solver_type = ceres::SPARSE_SCHUR;
  options.linear_solver_ordering = ordering;
  options.max_num_iterations = 30;
  options.num_threads = 1;
  options.minimizer_progress_to_stdout = false;

  ceres::Solver::Summary summary;
  ceres::Solve(options, &problem, &summary);
  if (!summary.IsSolutionUsable()) {
    std::cerr << "Ceres solution unusable: " << summary.BriefReport() << '\n';
    return 1;
  }
  if (summary.linear_solver_type_used != ceres::SPARSE_SCHUR) {
    std::cerr << "Expected SPARSE_SCHUR, got solver enum "
              << static_cast<int>(summary.linear_solver_type_used) << '\n';
    return 2;
  }
  std::cout << "SPARSE_SCHUR_OK " << summary.BriefReport() << '\n';
  return 0;
}
