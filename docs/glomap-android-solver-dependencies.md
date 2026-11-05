# Android GLOMAP global-positioning solver dependency plan

Pinned algorithm source:
- GLOMAP: `99806d0869f802fad218516a2e027793e7ca687d`
- COLMAP's matching upstream dependency (declared by that GLOMAP revision): `b6b7b54eca6078070f73a3f0a084f79c629a6f10`
- Separate newer COLMAP reference examined for API/porting: `e6644e744488a13f3aba82958500db9066341205`

## Decision: preserve the upstream optimizer, remove the GPL-only dependency requirement

The pinned `GlobalPositioner::Solve` creates a Ceres problem and calls
`ceres::Solve`. Its track-based path sets:

- `SPARSE_SCHUR` linear solver.
- `CLUSTER_TRIDIAGONAL` preconditioner option.
- Explicit Schur parameter ordering: per-observation scales in group 0,
  3D tracks in group 1, frame translations and estimated rig translations
  in group 2, and rig scales in group 3.
- Huber loss with threshold 0.1.
- 100 maximum iterations, function tolerance 1e-5, and hardware concurrency
  as the default thread count.
- Scale lower bound 1e-5.
- The first active observation scale and all rig scales are fixed to remove
  gauge ambiguity.
- Result conversion maps optimized camera centers back to pose translations
  using the upstream rotation and rig-scale equations.

Important Ceres detail: `CLUSTER_TRIDIAGONAL` is a preconditioner for
`ITERATIVE_SCHUR`, not for `SPARSE_SCHUR`. The pinned GLOMAP source
explicitly comments "Do not use iterative solvers" immediately before
selecting `SPARSE_SCHUR`, while still assigning the cluster preconditioner
field. Therefore that field is not the active preconditioner in this path.
Keep the upstream assignment for source parity, but do not mistake it for
an executed optimization stage or require a cluster-preconditioner test.
The required sparse backend is for `SPARSE_SCHUR` itself.

The pinned GLOMAP CMake currently calls
`find_package(Ceres REQUIRED COMPONENTS SuiteSparse)`, and its vcpkg
manifest also requests SuiteSparse. That is not acceptable for the
proprietary TrackStudio distribution without a commercial SuiteSparse
licence: Ceres documents that its required SuiteSparse CHOLMOD supernodal
and SPQR components are GPL/commercial.

Ceres supports Eigen sparse Cholesky and documents EigenSparse as a valid
backend for `SPARSE_SCHUR`. The no-GPL route to test is to patch only the
dependency selection in the vendored GLOMAP build:
- build Ceres with `WITH_EIGENSPARSE=ON` and `WITH_SUITESPARSE=OFF`;
- change the GLOMAP Ceres package requirement from `SuiteSparse` to
  `EigenSparse`;
- remove SuiteSparse from the GLOMAP dependency manifest;
- retain the upstream `SPARSE_SCHUR` setting, all optimizer math, and all
  other solver settings unchanged.

This is a build-system/licensing adaptation, not an algorithm substitution.
It is not considered validated until an Android-native smoke test solves a
small Schur-structured problem using `SPARSE_SCHUR` with EigenSparse and
confirms the Ceres summary reports `SPARSE_SCHUR` as the solver actually
used. If it fails, stop and investigate a permissively licensed sparse
backend; do not silently switch to `ITERATIVE_SCHUR` or another solver.

## Android and upstream build constraints

The app currently has NDK 27.0.12077973, CMake 3.22.1, C++17, and two ABIs:
`arm64-v8a` and `armeabi-v7a`. Its native target currently builds only the
proxy engine.

Ceres 2.3 is a candidate baseline: it requires CMake 3.22+, Eigen and
Abseil, supports Android NDK r20+, and can use the same `c++_shared` STL
and target ABI/API level as the app. It must be cross-built separately for
each app ABI.

The pinned GLOMAP top-level `CMakeLists.txt` declares CMake 3.28, even
though its README discusses lower CMake versions when using installed
dependencies. Its source also requires Boost and OpenMP, and its third-party
CMake declares PoseLib and COLMAP dependencies. The app's current CMake
3.22.1 therefore cannot configure the pinned GLOMAP project unchanged.
We must either use a compatible CMake 3.28 SDK package or maintain a narrowly
scoped Android build entry point that avoids unsupported top-level
configuration features. Do not change the app's CMake version until the
native build path is demonstrated.

The pinned GLOMAP revision fetches COLMAP
`b6b7b54eca6078070f73a3f0a084f79c629a6f10` (COLMAP 3.14 development
snapshot, Nov 20 2025). The separate COLMAP reference revision currently
listed in the port notes, `e6644e744488a13f3aba82958500db9066341205`,
is a later 4.3 development snapshot. These are not interchangeable pins.
For source parity with this GLOMAP revision, use its declared COLMAP pin
first; evaluate the later COLMAP revision separately rather than mixing
headers and libraries from different revisions.

A full desktop COLMAP build is not the Android target. The Android build
must select the core scene/geometry/feature/estimation/SfM code required by
GLOMAP and disable GUI, OpenGL, CUDA, MVS, downloads, and other irrelevant
desktop features where their dependencies are not needed. The exact minimal
target graph still needs to be established from the pinned source and
verified by an NDK build.

## Required implementation sequence

1. Vendor/pin the matching GLOMAP and COLMAP sources and record every
   dependency revision and licence.
2. Cross-build Eigen, Abseil, Ceres and required core dependencies for both
   Android ABIs. Keep GPL SuiteSparse components out of the dependency graph.
3. Patch only GLOMAP's CMake dependency requirement to EigenSparse; preserve
   its algorithm source and options.
4. Add a native smoke test using the exact `SPARSE_SCHUR` configuration
   with EigenSparse and assert that the Ceres summary reports that solver
   as actually used.
5. Port upstream GLOMAP parameter-block storage/lifetime, residual
   registration, loss ownership, bounds, parameter groups, gauge fixing,
   solver settings, and result conversion.
6. Compare a deterministic fixture against the pinned upstream GLOMAP
   implementation, including optimized translations, point values, solver
   usability, and residual summaries.
7. Build the required COLMAP core and GLOMAP sources for both ABIs, then
   expose them through the standalone `GlomapReconstructionEngine`.
   Keep the current engine available as a fallback until device validation.

## Current boundary

The Kotlin initializer, BATA residual, and point-to-camera problem builder
are pre-optimization stages only. They are not a solver and must not be
presented as a functioning GLOMAP reconstruction engine. No Android Ceres,
COLMAP, or GLOMAP native library integration is claimed complete by this
document.
