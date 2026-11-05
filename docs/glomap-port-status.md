# GLOMAP Android port status

Pinned upstream revisions:
- GLOMAP: `99806d0869f802fad218516a2e027793e7ca687d`
- COLMAP: `e6644e744488a13f3aba82958500db9066341205`

## Global positioning: random initialization and point-to-camera assembly

Upstream initialization source: `GlobalPositioner::InitializeRandomPositions` and the random-point initialization in `AddPointToCameraConstraints` in `glomap/estimators/global_positioning.cc`.

Android implementation:
- `tracking/glomap/GlomapMt19937.kt`: MT19937 engine seeded with the upstream default (1), plus the 53-bit canonical uniform sampling used by the pinned reference build.
- `tracking/glomap/GlomapGlobalPositioningInitializer.kt`: constrained-frame discovery from valid image pairs and sufficiently long tracks, camera-center initialization, and random 3D point initialization.

Preserved initialization behavior:
- Valid image pairs constrain both endpoint frames.
- Tracks shorter than 3 observations do not constrain frames or receive random points.
- Only registered images in eligible tracks add constrained frames.
- With random position generation and position optimization enabled, constrained frame centers and eligible track points use `100 * uniform[-1,1]` per coordinate; unconstrained frames use their camera center from the existing pose.
- When random positions are disabled or positions are not optimized, only constrained frames are converted to camera centers.
- Camera positions and track points consume one shared seeded MT19937 stream in source call order.
- Input lists preserve caller-provided traversal order. Upstream uses unordered maps, so exact assignment of random samples to IDs requires matching that upstream container iteration order.

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
