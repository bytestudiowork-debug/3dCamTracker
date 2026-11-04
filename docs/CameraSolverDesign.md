# Camera Solver: Incremental SfM and Continuous Trajectory Design

Status: engineering design for the Android camera-tracking engine. This is not a claim that the current prototype already implements these stages.

## Goals

- Reconstruct one coherent 3D camera path from the complete source clip.
- Reduce expensive feature extraction, matching, triangulation, and optimisation without dropping output frames.
- Export a pose at every original source frame/time, so Blender and other DCC applications do not interpolate across large keyframe gaps.
- Bound memory and runtime for low-, mid-, and high-tier Android devices.
- Report degeneracy and uncertainty instead of manufacturing a plausible-looking solve.

## Critical correction to the current prototype

The current pairwise `recoverPose` stage returns a translation direction with arbitrary unit magnitude for each pair. Summing those translations as if each were a metric camera displacement is not valid. The resulting path changes scale with pair spacing and can drift badly. It is suitable only as an early geometry experiment, not as the final trajectory to export.

The current triangulator also consumes those provisional poses. Its landmarks must not be treated as a stable map until camera poses are registered against a common reconstruction and jointly refined.

## 1. Separate analysis frames from output frames

Maintain two timelines:

- **Analysis/keyframes:** selected for expensive descriptor extraction, robust matching, triangulation, and map maintenance.
- **Dense output frames:** every original decoded frame with its original timestamp/frame index.

A keyframe decision never removes a source frame from the exported animation.

For intermediate frames, first attempt inexpensive map-based pose estimation using tracked 2D observations and existing 3D landmarks (PnP-RANSAC). If there are insufficient 2D-3D correspondences, use short-baseline visual tracking/relative motion to create a pose prior. Interpolate only as a fallback, over a bounded gap, and mark the pose as inferred.

For export, emit one camera sample per source frame. Use a continuous rotation representation (quaternion with sign continuity) and consistent translation/time units. Do not ask Blender to bridge large unsolved intervals with sparse transform keys.

## 2. Initial pair selection

Evaluate candidate pairs from a bounded temporal search window, widening the window progressively when adjacent frames have inadequate parallax. Do not select by frame distance or raw feature count alone.

For each candidate pair:

1. Match features and perform geometric verification (essential matrix with RANSAC; use a homography comparison to detect planar/rotation-dominated degeneracy).
2. Score verified inlier count and inlier ratio.
3. Measure image-space coverage using a grid/quadrant occupancy score; reject matches concentrated in one small region.
4. Compute triangulation-angle distribution, not only its maximum. Prefer a healthy median/percentile and enough points with useful parallax.
5. Check cheirality, forward-motion degeneracy, and spatial conditioning.
6. Require enough overlap to establish persistent tracks.

Choose the best well-conditioned pair, not necessarily the pair with the greatest temporal separation. Keep thresholds resolution-aware and profile-dependent; COLMAP's documented 16-degree initial minimum triangulation angle is a useful reference, not a fixed mobile-video rule.

## 3. Incremental camera registration

After initial two-view reconstruction, register subsequent selected frames against the existing 3D map:

1. Associate 2D features with existing 3D landmarks through persistent tracks.
2. Estimate absolute camera pose using PnP-RANSAC.
3. Validate inlier count, inlier ratio, reprojection error, image coverage, and pose conditioning.
4. Triangulate new landmarks from observations in registered cameras.
5. Refine new and nearby cameras/points with local bundle adjustment.
6. Retry rejected frames after neighbouring frames improve the map.

Candidate-frame ranking should consider:
- number of visible 3D landmarks;
- visible-landmark ratio;
- spatial distribution of those landmarks;
- expected pose uncertainty / conditioning;
- time since last registered keyframe and motion/blur change.

This follows COLMAP's proven alternatives (maximum visible points, visible-point ratio, and minimum uncertainty) while adding mobile-video temporal and coverage constraints.

## 4. Bundle adjustment objective

Optimise camera poses and 3D landmarks by minimising robust reprojection residuals:

`r_ij = observed_pixel_ij - project(K, distortion, pose_i, point_j)`

Use a robust loss (e.g. Huber initially, with outlier filtering) so moving objects and bad correspondences do not dominate the solution.

Gauge constraints are mandatory:
- fix the first camera pose;
- fix the reconstruction scale, e.g. a reliable initial baseline or a scale prior;
- keep known intrinsics fixed initially; refine focal length/distortion only when the sequence has enough evidence and the model is well-conditioned.

### Mobile optimisation schedule

- Local BA: a sliding window of recent keyframes and connected landmarks after registration.
- Periodic global BA: run only when camera/landmark growth or estimated drift justifies the cost.
- Prune redundant observations/landmarks while preserving track connectivity and spatial coverage.
- Bound iterations, window size, and working-set memory by device profile.
- Use Ceres Schur-based solvers: DENSE_SCHUR for small problems; ITERATIVE_SCHUR with an appropriate preconditioner as the problem grows. Evaluate Android NDK build size and dependency costs before integrating Ceres.

Do not apply an aggressive post-solve smoothing filter to hide bad geometry. If a temporal smoothness prior is used, make it a weak, robust acceleration prior and keep reprojection evidence dominant.

## 5. Dense, smooth camera output

The reconstruction's sparse keyframe poses are not the deliverable animation. Build a dense pose track:

- Estimate every source frame against the map where possible.
- Use short-interval visual tracking for pose priors between expensive keyframes.
- Fill only short gaps with SE(3)-aware interpolation: quaternion SLERP/SQUAD for rotation and time-aware cubic Hermite interpolation for translation when endpoint velocities are reliable.
- Enforce monotonically increasing timestamps and exactly one output sample per original frame.
- Keep camera coordinate conventions explicit and convert once at export.
- Preserve actual motion; smoothing must not flatten intentional acceleration or hand-held movement.
- Report interpolated/inferred frames and confidence.

A smooth interpolation cannot recover missing motion information across a long gap. The correct fix is to add intermediate pose observations or re-register frames, not merely increase spline smoothness.

## 6. Quality gates and diagnostics

Track per-frame:
- selected/registered/interpolated status;
- 2D-3D PnP inliers and ratio;
- median and RMS reprojection error;
- landmark coverage and triangulation-angle percentiles;
- pose uncertainty / conditioning;
- local and global BA cost before/after;
- frame-to-frame translation and rotation increments.

Reject or warn on pure rotation, insufficient parallax, severe blur, low texture, dynamic-scene dominance, rolling-shutter distortion, or unstable intrinsics. Never label a reconstruction accurate solely because the optimiser converged.

## 7. Implementation order

1. Fix pose/track timeline invariants and add synthetic geometry tests.
2. Implement initial-pair scoring and keyframe selection as a standalone, testable module.
3. Replace accumulated unit-translation motion with map-based PnP camera registration and consistent scale.
4. Add multi-view triangulation and track-to-landmark associations.
5. Add local BA, then periodic global BA; evaluate Ceres Android integration.
6. Add dense per-source-frame pose estimation and export sampling.
7. Add loop/revisit detection, pose-graph/global refinement, and device-tier budgets.
8. Validate on controlled synthetic sequences and real handheld clips before claiming production accuracy.

## Research references

- COLMAP incremental mapper options and methods: https://github.com/colmap/colmap/blob/main/src/colmap/sfm/incremental_mapper.h
- COLMAP incremental mapper implementation (candidate ranking, local bundle selection): https://github.com/colmap/colmap/blob/main/src/colmap/sfm/incremental_mapper_impl.cc
- COLMAP FAQ (incremental/global SfM trade-offs, intrinsics, BA runtime controls): https://colmap.github.io/faq.html
- Ceres solver guidance for Schur-based bundle adjustment: https://ceres-solver.readthedocs.io/latest/solving_faqs.html
- Blender camera solver and conversion to F-curves: https://docs.blender.org/manual/en/latest/animation/constraints/motion_tracking/camera_solver.html
