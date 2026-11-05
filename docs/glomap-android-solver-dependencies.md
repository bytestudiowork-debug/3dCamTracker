# Android GLOMAP global-positioning solver dependency plan

Pinned algorithm source:
- GLOMAP: `99806d0869f802fad218516a2e027793e7ca687d`
- COLMAP: `e6644e744488a13f3aba82958500db9066341205`

## Decision: use upstream Ceres, do not replace it

The pinned `GlobalPositioner::Solve` creates a Ceres problem and calls
`ceres::Solve`. Its default track-based path uses:

- `SPARSE_SCHUR` linear solver.
- `CLUSTER_TRIDIAGONAL` preconditioner.
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

A Kotlin least-squares implementation, a different Ceres linear solver, or a
fallback to dense/iterative optimization would not be an equivalent port.
The Android implementation must retain the Ceres configuration above.

## Android dependency findings

The app currently has NDK 27.0.12077973, CMake 3.22.1, C++17, and two ABIs:
`arm64-v8a` and `armeabi-v7a`. Its native CMake target currently builds only
the proxy engine; it does not include Ceres, Eigen, Abseil, or SuiteSparse.

Ceres 2.2.0 is a suitable C++17-era baseline to evaluate against this project:
its CMake minimum is compatible with the app's CMake 3.22.1. Ceres 2.2+
requires Eigen and Abseil. The official Ceres Android instructions support
NDK r20+ and require the Ceres binaries to use the same Android ABI, API level,
and C++ STL as the application.

The exact GLOMAP solver configuration is more demanding than merely building
Ceres with Eigen sparse support: Ceres documents that
`CLUSTER_TRIDIAGONAL` requires a sparse Cholesky backend such as SuiteSparse.
Therefore, do not silently build a reduced Ceres configuration and keep the
same-looking solver options. The native dependency build must provide and
verify the SuiteSparse capability required by the selected upstream options.

## Required implementation sequence

1. Pin and cross-build Ceres plus its required dependencies for both app ABIs.
   Disable CUDA; the mobile target is CPU-only. Preserve the C++ shared STL
   and Android API compatibility.
2. Add a native smoke test that constructs a tiny Ceres problem using the
   same `SPARSE_SCHUR` / `CLUSTER_TRIDIAGONAL` options and confirms that the
   selected sparse backend is actually available on-device.
3. Port the upstream GLOMAP parameter-block storage and lifetime rules. Scale
   storage must remain stable after its addresses are registered with Ceres.
4. Port residual registration, loss ownership, lower bounds, parameter groups,
   constant blocks, gauge fixing, solver options, and result conversion.
5. Compare a deterministic small fixture against the pinned upstream GLOMAP
   implementation, including optimized translations, point values, solver
   usability, and residual summaries.
6. Only after the native solver passes the fixture should it be exposed through
   the standalone `GlomapReconstructionEngine` and made available for device
   testing.

## Current boundary

The Kotlin initializer, BATA residual, and point-to-camera problem builder are
pre-optimization stages only. They are not a solver and must not be presented
as a functioning GLOMAP reconstruction engine. No Android solver dependency or
solver implementation is claimed complete by this document.
