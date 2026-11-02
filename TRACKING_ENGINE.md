# TrackStudio tracking engine

## Current boundary

The tracking engine is independent of Compose and the viewer. Kotlin owns project/session state and frame acquisition. C++ owns pixel-level feature detection and optical flow. JNI calls are synchronous and are made from Dispatchers.Default, never from the UI thread.

The frame provider must return software ARGB_8888 or RGB_565 bitmaps with identical dimensions for each adjacent pair. It must downscale to the engine's maximumAnalysisDimension, preferably from the project proxy when that resolution is sufficient. The sequence processor owns each returned bitmap until it calls releaseFrame; it holds no more than the previous and current frame.

## Tracking pipeline

1. Seed — manual mode starts from user-placed points; automatic mode detects Shi-Tomasi corners on the selected start frame.
2. Propagate — pyramidal Lucas-Kanade optical flow estimates each point in the next frame.
3. Validate — reject points with weak local structure, invalid bounds, excessive photometric error, or (automatic mode) failed forward/backward consistency.
4. Record — store a frame-indexed observation with confidence and ACTIVE/LOST state. Observations are sorted by frame index, so propagation can run forward or backward.
5. Continue — load one next frame, process it, release the previous bitmap, and report progress. Cancellation is checked between frame pairs.
6. Correct — a future editor tool can replace an observation at a frame and resume propagation from that correction.

Manual and automatic tracks share the same TrackPoint / TrackObservation representation. Automatic tracking is not a separate data format.

## Coordinate convention

Image coordinates are pixel coordinates with origin at the top-left, +X to the right, +Y down, and pixel centers represented as floating-point values. Camera intrinsics are expressed in pixels. Sensor dimensions and focal length in millimeters must be converted using the active image dimensions before solving.

## Device profiles

The default profile is selected from Android's maximum heap and processor count. It changes analysis resolution, feature count, optical-flow window, and pyramid depth. The provider is responsible for honoring the requested maximum dimension. The sequence processor is serial and bounded-memory; parallel frame processing is deliberately deferred until profiling proves it helps without starving the UI or causing thermal throttling.

## Planned stages

- Foundation (this PR): data model, frame provider, feature detection, optical flow, forward/backward validation, bounded sequence processor.
- Track editing: stable IDs, manual placement/dragging, keyframe correction, resume from correction, visibility/locking, undo/redo commands, session persistence.
- Robust auto tracking: replenishment, occlusion/re-entry policy, adaptive thresholds, descriptor matching and RANSAC outlier rejection.
- Camera/lens: calibration workflow, distortion models, undistortion maps, metadata and image-orientation handling.
- 3D solve: camera pose initialization, robust triangulation, bundle adjustment, scale/orientation constraints and solve diagnostics.

A successful Android build only verifies compilation. Before production use, the native algorithms need synthetic translation/rotation/scale tests, repeatable accuracy tests, and device profiling on low-, mid- and high-tier hardware. Camera solving is not claimed by this foundation.
