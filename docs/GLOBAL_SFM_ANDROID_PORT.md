# Android Global SfM Port: COLMAP / GLOMAP Source Map

Status: implementation specification; no reconstruction behavior is changed by this document.

## Goal

Port the global structure-from-motion methods used by COLMAP's global mapper (formerly GLOMAP) into TrackStudio's Android/OpenCV engine. Do not vendor the desktop applications or add COLMAP, GLOMAP, Ceres, or PoseLib as runtime dependencies. Keep the current Compose UI, frame playback, progress reporting, and result model stable.

The intended output is one globally consistent world-to-camera pose per usable source frame, a sparse set of static 3D landmarks, and reprojection diagnostics that report both accuracy and coverage.

## Source files selected and ordered by algorithm stage

### 1. Feature observations and verified view graph (COLMAP)

- `src/colmap/controllers/feature_extraction.cc`
- `src/colmap/controllers/feature_matching.cc`
- `src/colmap/controllers/pairing.cc`
- `src/colmap/estimators/two_view_geometry.cc`
- `src/colmap/estimators/solvers/essential_matrix.cc`
- `src/colmap/estimators/solvers/fundamental_matrix.cc`

Methods to port:
- Detect local features and retain descriptor-to-keypoint associations.
- Match temporal neighbors and selected longer-range frame pairs.
- Geometrically verify each edge with calibrated essential geometry or uncalibrated fundamental geometry.
- Retain relative pose, inlier count, inlier ratio, and match indices for each verified edge.
- Keep the largest connected component before global estimation.

Android adaptation:
- Reuse OpenCV feature detection and robust two-view estimation already present.
- Build a bounded temporal view graph instead of exhaustive all-pairs matching.
- Bound descriptor retention and decode/release frames as soon as possible.
- Do not interpret an unverified descriptor match as a graph edge.

### 2. Intrinsics / view-graph calibration

- `glomap/estimators/view_graph_calibration.cc`
- `src/colmap/estimators/view_graph_calibration.cc`

Methods to port:
- Estimate/refine focal length from the verified view graph when reliable camera calibration is not supplied.
- Recompute/decompose pair geometry after intrinsics are refined.

Important constraint:
- COLMAP's global mapper explicitly depends on good focal-length priors. TrackStudio's current `0.9 * max(width,height)` focal estimate is only a rough initialization and must not be treated as calibrated truth.
- The port must expose calibration confidence and retain a conservative fallback when the graph does not constrain focal length sufficiently.

### 3. Relative-pose graph and global rotation averaging (GLOMAP)

- `glomap/controllers/rotation_averager.cc`
- `glomap/estimators/global_rotation_averaging.cc`
- `glomap/estimators/global_rotation_averaging.h`
- `glomap/estimators/rotation_initializer.cc`
- `glomap/math/tree.h`

Methods confirmed in source:
1. Initialize camera rotations from a maximum spanning tree, weighted by pair inlier counts.
2. Build a sparse linear system for tangent-space rotation updates. The first-order relation is that the relative rotation increment is the difference between the two camera tangent updates.
3. Solve a robust L1 regression stage.
4. Refine with iteratively reweighted least squares (IRLS), using robust edge weights.
5. Convert tangent-space updates back to rotations.
6. Reject edges with inconsistent rotation residuals and recompute on the largest connected component.

Android adaptation:
- Implement compact SO(3) exponential/logarithm and quaternion/matrix conversion in Kotlin.
- Store only sparse graph edges and per-camera rotations.
- Use a bounded sparse/iterative linear solver suitable for mobile memory; never allocate a dense matrix proportional to all frame pairs.
- Keep one camera fixed to remove global rotation gauge freedom.
- Port and test maximum-spanning-tree initialization, robust residual calculation, L1/IRLS weighting, and outlier pruning separately.

### 4. Track establishment

- `glomap/controllers/track_establishment.cc`
- `glomap/scene/track.h`
- `src/colmap/feature/feature.h`

Methods to port:
- Merge verified pairwise feature correspondences into multi-view tracks.
- Prevent duplicate observations for the same frame in a track.
- Reject inconsistent cycles / conflicting feature assignments.
- Prefer tracks with sufficient view support for global positioning.

Android adaptation:
- Reuse TrackStudio's persistent feature IDs, but make graph-edge correspondences the input rather than relying only on adjacent-frame continuity.
- Store observations as compact frame/keypoint indices; avoid retaining image matrices.

### 5. Global positioning (GLOMAP)

- `glomap/estimators/global_positioning.cc`
- `glomap/estimators/cost_function.h`
- `glomap/estimators/global_positioning.h`

Method confirmed in source:
- With rotations fixed, optimize camera centers and 3D track points together from multi-view bearing constraints (the default/recommended `ONLY_POINTS` constraint mode).
- Each observation contributes a camera-to-point direction constraint with an auxiliary positive scale.
- Use robust Huber-style losses; calibrated and uncalibrated cameras receive different weighting.
- Optimize camera positions, points, and scales jointly, then convert camera centers back into world-to-camera translations.

Android adaptation:
- Derive the same bearing residual from each undistorted normalized observation and current global rotation.
- Implement a memory-bounded block optimizer. Exploit the point/camera sparsity; do not build a dense all-variables normal matrix.
- Fix the reconstruction gauge (reference camera and scale) deterministically.
- Reject poorly conditioned tracks and degenerate camera/point configurations.
- This is the largest algorithmic port and must not be replaced by chaining pairwise `recoverPose` translations (their scales are independent).

### 6. Track filtering, retriangulation, and global bundle adjustment

- `glomap/processors/track_filter.cc`
- `glomap/controllers/track_retriangulation.cc`
- `glomap/estimators/bundle_adjustment.cc`
- `glomap/estimators/cost_function.h`
- `src/colmap/estimators/triangulation.cc`
- `src/colmap/estimators/cost_functions/reprojection_error.h`

Methods to port:
- Filter by angular consistency, minimum triangulation angle, and reprojection error.
- Retriangulate tracks from the current globally estimated camera poses.
- Run staged global BA: optimize positions first, then optionally rotations; repeat filtering and BA with progressively tighter thresholds.
- Normalize the reconstruction to a stable gauge.

Android adaptation:
- Retain the current OpenCV triangulation primitives where their geometry matches COLMAP's.
- Replace the current landmark-only alternating adjustment with a joint sparse camera/point optimizer in stages.
- Use robust reprojection loss and a strict maximum per-observation error.
- Make retriangulation and BA iteration counts adaptive to graph size and device memory.

## Current TrackStudio integration points

- `tracking/IncrementalSfMSolver.kt`: currently chooses one initial pair, registers later frames with PnP, then runs a backward pass and lightweight BA. This is the current incremental path to replace in stages.
- `tracking/FeatureTrackBuilder.kt`: existing persistent feature-track storage; extend it to accept non-adjacent verified graph edges.
- `tracking/InitialPairSelector.kt` and `OpenCvInitialPairEvaluator.kt`: useful pair scoring and two-view initialization primitives; these should not remain the single global initialization mechanism.
- `tracking/IncrementalCameraRegistrar.kt`: useful local PnP recovery/diagnostic fallback, not the global pose solver.
- `tracking/SfMTriangulator.kt`: useful triangulation primitive after globally consistent rotations/positions exist.
- `tracking/MobileBundleAdjuster.kt`: current landmark-only block optimizer; explicitly not a joint sparse global BA implementation.
- `tracking/CameraTrackingEngine.kt`: stable UI-facing entry point and result contract. Preserve it while changing the internal solver.

## Implementation sequence and acceptance gates

1. **View graph + rotation averaging.** Add deterministic graph data structures, maximum-spanning-tree initialization, SO(3) tangent updates, robust L1/IRLS, edge pruning, and synthetic tests with known camera rotations and injected outlier edges.
2. **Global positioning.** Add multi-view bearing constraints and joint camera-center/point/scale optimization. Test on synthetic camera trajectories with known ground truth and scale-gauge normalization.
3. **Track graph + triangulation.** Establish multi-view tracks from all retained edges; triangulate using the globally estimated poses; filter by angle and reprojection.
4. **Global BA + retriangulation.** Add staged sparse optimization and progressively stricter filtering. Verify objective decreases and outlier observations are removed.
5. **Engine integration + mobile performance.** Preserve result/UI contracts, progress phases, cancellation, bounded memory, and frame overlays. Benchmark low-memory and stronger Android devices.

Required reporting:
- Registered-frame coverage and longest contiguous registered segment.
- Connected-component coverage and rejected-edge count.
- Mean, median, and 95th-percentile reprojection error, plus observation count.
- Runtime by stage and peak memory where measurable.
- Synthetic ground-truth pose/point error, not only reprojection error.

A low reprojection error on a small accepted subset is not a successful full-video solve. The target is high coverage and globally consistent poses as well as low reprojection error.

## Licensing and dependency boundary

The GLOMAP source repository is BSD-3-Clause and has been archived after its global-mapper functionality migrated into COLMAP. This project ports algorithmic methods; it does not copy source files into the Android application. Any future source-derived code must retain required attribution/license notices and be reviewed before inclusion.
