# GLOMAP Android port status

Pinned upstream revisions:
- GLOMAP: `99806d0869f802fad218516a2e027793e7ca687d`
- COLMAP: `e6644e744488a13f3aba82958500db9066341205`

## Global positioning: point-to-camera problem assembly

Upstream sources:
- `glomap/estimators/global_positioning.h`
- `glomap/estimators/global_positioning.cc`
- `glomap/estimators/cost_function.h`

Android implementation:
- `tracking/glomap/BataDirectionResidual.kt`: BATA residual, analytic Jacobians and scale initialization.
- `tracking/glomap/GlomapGlobalPositioningProblemBuilder.kt`: constructs ONLY_POINTS point-to-camera residual inputs from undistorted feature directions and camera-from-world rotations.

Preserved details:
- Default minimum track observations: 3.
- Observation translation: inverse camera rotation multiplied by the undistorted feature direction.
- Auxiliary scale initial value: 1.0 when scale generation is enabled.
- When scale generation is disabled and a point is initialized, scale uses max(1e-5, translation dot (point - camera center) / squaredNorm(point - camera center)).
- BATA residual remains translation observation minus scale times (point - camera center).
- Scale lower bound: 1e-5.
- No pairwise camera constraints are added in ONLY_POINTS mode.

Not yet ported:
- Ceres problem/parameter-block ownership and Schur ordering.
- GLOMAP random initialization using its mt19937 seed and 100 * uniform[-1,1] vectors.
- Huber loss execution and calibrated/uncalibrated scaled-loss objects.
- Ceres trust-region solve, gauge fixing, and conversion back to camera poses.
- The assembled problem is not yet wired into a standalone GlomapReconstructionEngine.

The builder intentionally stops before optimization. It does not substitute a different optimizer or claim to produce camera positions.
