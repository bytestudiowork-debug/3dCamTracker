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

### Initial-pair scoring implementation contract

The standalone `InitialPairSelector` ranks already-evaluated pair geometry using a hard quality gate followed by a balanced score. Inputs are produced by the OpenCV evaluator and include verified inliers/ratio, 4x4 image-grid coverage, median and upper-quartile parallax, cheirality ratio, homography dominance, and forward-motion ratio. A high match count cannot compensate for weak parallax or spatially concentrated features.

The selector returns no pair when all candidates fail. It does not silently choose the least-bad pair. Its temporal candidate generator is bounded and deterministic; temporal distance schedules the search only and is never treated as geometric quality. Current default thresholds are conservative starting values for mobile footage and must be tuned using synthetic and real clips, not treated as universal constants. COLMAP's desktop initialization defaults are reference points, not copied wholesale.

The selector is a pure policy module with JVM tests. It is not yet wired into `CameraTrackingEngine`; integration requires a bounded feature-frame cache and a two-pass bootstrap so the chosen pair can become the actual reconstruction origin. Until that integration and a consistent-scale map exist, the existing accumulated pairwise trajectory remains provisional and must not feed final export or trusted PnP registration.

## 3. Incremental camera registration

OpenCV's Android Java API already exposes `solvePnPRansac` and pose refinement, so the first PnP registration stage can be prototyped without waiting for Ceres. Ceres is for joint multi-camera/multi-point optimisation, not a prerequisite for PnP.

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

### Ceres on Android

Ceres 2.2 is compatible in principle with this project's C++17, CMake 3.22.1, and NDK r27 baseline. Its Android build requires Eigen and Abseil; those must be cross-compiled for each ABI and packaged correctly. Start with SuiteSparse disabled: Ceres documents it as optional, and the required SuiteSparse components can impose GPL/commercial licensing implications. Build only required solver components, disable tests/examples/benchmarks, strip native symbols, and measure APK size and CI build time before adopting it. Begin with Ceres' Eigen-based iterative Schur path; do not pull in SuiteSparse merely for a theoretical speed gain. Keep a pure OpenCV/Eigen fallback or a feature flag until Android arm64 and armeabi-v7a builds are verified.

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

- OpenCV 4.x Java Calib3d PnP-RANSAC and pose refinement API: https://docs.opencv.org/4.11.0/javadoc/org/opencv/calib3d/Calib3d.html
- COLMAP incremental mapper options and methods: https://github.com/colmap/colmap/blob/main/src/colmap/sfm/incremental_mapper.h
- COLMAP incremental mapper implementation (candidate ranking, local bundle selection): https://github.com/colmap/colmap/blob/main/src/colmap/sfm/incremental_mapper_impl.cc
- COLMAP FAQ (incremental/global SfM trade-offs, intrinsics, BA runtime controls): https://colmap.github.io/faq.html
- Ceres solver guidance for Schur-based bundle adjustment: https://ceres-solver.readthedocs.io/latest/solving_faqs.html
- Ceres Android build requirements and SuiteSparse licensing note: https://ceres-solver.readthedocs.io/latest/installation.html
- Blender camera solver and conversion to F-curves: https://docs.blender.org/manual/en/latest/animation/constraints/motion_tracking/camera_solver.html


## Comparative review: production matchmove systems and mobile adaptation

The reviewed workflows converge on a staged pipeline rather than one monolithic solver:

- **After Effects** separates background analysis from solving, exposes solve-type hints (typical, mostly-flat, tripod-pan), lets users inspect/delete bad tracks, and reports reprojection error. TrackStudio should preserve this separation and surface uncertainty instead of hiding it.
- **Nuke / Foundry CameraTracker** supports automatic and user-seeded tracks, moving-object masks, multiple camera models, survey constraints, lens distortion, and exports a camera plus point cloud. The mobile equivalent should prioritize robust automatic tracks first, then add masks/manual seeds and calibrated intrinsics as explicit inputs.
- **Blender / libmv** treats 2D tracks, camera intrinsics/distortion, camera solving, and scene orientation as distinct operations. Its solve requires common tracks and parallax; the solve error is measured by reprojecting reconstructed points. libmv is a matchmove-focused library, not a drop-in dense reconstruction engine.
- **3DEqualizer and SynthEyes** are professional matchmove references for track supervision, lens calibration/distortion, survey constraints, and solve refinement. Their internal proprietary implementations are not assumed or copied; only publicly documented workflows are used as design guidance.
- **COLMAP / OpenMVG** provide the open incremental-SfM reference: sequential matching for video, view registration against a shared map, multi-view triangulation, local/global bundle adjustment, and loop/revisit handling.
- **AMD / OpenCV / Arm performance guidance** favors portable optimized primitives and SIMD dispatch (including NEON on ARM), profiling before hand-written architecture-specific kernels, and keeping working sets bounded. AMD's 2026 OpenCV work is upstream portable SIMD, not a reason to add AMD-only code to Android.

### Implemented in this iteration

PnP registration now snapshots only feature tracks already associated with current 3D landmarks. Previously, every frame copied the complete feature-track graph (including short-lived tracks that cannot contribute to PnP), and the registrar rebuilt an ID lookup over that larger list. The new filtered snapshot copies only map-connected tracks for each registration/recovery attempt. The complete track graph is materialized only when a map update, final triangulation, refinement, or result assembly needs it. This is an allocation/CPU reduction that does not lower RANSAC quality gates or discard map landmarks.

### Next engine milestones (ordered by correctness and mobile cost)

1. Add synthetic camera/landmark tests for pose direction, cheirality, reverse registration, and exact per-frame timeline invariants.
2. Add pyramidal Lucas-Kanade flow between ORB refresh/keyframes; periodically re-detect/replenish features and reject flow by forward-backward error, patch error, and image coverage. Benchmark on low-end ARM before enabling by default.
3. Replace the current all-map triangulation passes with bounded multi-view track triangulation and stable track-to-landmark maintenance.
4. Implement true sliding-window joint camera/point bundle adjustment with robust loss and a fixed gauge; evaluate Ceres/Schur on Android only after APK size, ABI, memory, and latency measurements.
5. Add adaptive keyframe selection, moving-object masks, user-seeded tracks, lens distortion/calibration, and camera-model hints (typical / mostly planar / tripod-pan).
6. Add revisit/loop detection and pose-graph refinement; keep dense per-source-frame pose output and explicit inferred/unregistered statuses.
7. Profile decode, feature extraction, matching, PnP, triangulation, and BA independently on representative ARM devices. Use adaptive feature/resolution budgets and portable OpenCV NEON paths; do not assume workstation SIMD/GPU optimizations exist on phones.

### Sources reviewed

- Adobe After Effects 3D Camera Tracker: https://helpx.adobe.com/after-effects/desktop/work-with-3d-composition/3d-camera-tracker-effect/tracking-3d-camera-movement.html
- Foundry Nuke CameraTracker workflow: https://modosdk.foundry.com/nuke/12.0/content/comp_environment/cameratracker/camera_tracking.html
- Blender camera solver: https://docs.blender.org/manual/en/latest/animation/constraints/motion_tracking/camera_solver.html
- Blender/libmv overview via OpenCV SFM docs: https://docs.opencv.org/3.4.20/d8/d8c/group__sfm.html
- COLMAP incremental mapping and sequential matching: https://github.com/colmap/colmap/blob/main/src/colmap/sfm/incremental_mapper.h and https://colmap.github.io/faq.html
- OpenCV pyramidal Lucas-Kanade reference: https://docs.opencv.org/2.3/modules/video/doc/motion_analysis_and_object_tracking.html
- AMD portable SIMD work in OpenCV: https://www.amd.com/en/developer/resources/technical-articles/2026/accelerating-opencv-with-portable-simd.html
- OpenCV hardware acceleration layer: https://docs.opencv.org/5.0/main_modules/hal.html
- Arm NEON overview: https://www.arm.com/technologies/neon
