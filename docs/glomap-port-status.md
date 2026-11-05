# GLOMAP Android port status

Pinned upstream source revision: `colmap/glomap@99806d0869f802fad218516a2e027793e7ca687d`

Pinned COLMAP dependency revision inspected during the port: `colmap/colmap@e6644e744488a13f3aba82958500db9066341205`

## Porting rules

- Preserve upstream equations, objective functions, thresholds, filtering order, and solver stages.
- Android changes are limited to platform/dependency adaptation and mathematically equivalent execution optimizations.
- A ported residual kernel is not considered a completed optimizer or pipeline stage.
- Do not report parity until source-to-source review and reference-case comparisons pass.

## Source-to-Android checklist

| Upstream source / stage | Android implementation | Status |
| --- | --- | --- |
| `glomap/controllers/global_mapper.cc`: rotation controller | `tracking/GlobalViewGraphSolver.kt` | Controller pass count/order corrected to two solves; view-graph input is still a limited initial-pair graph, not the full GLOMAP view graph |
| COLMAP/GLOMAP rotation averaging implementation | `tracking/GlobalRotationAverager.kt` | Partial Kotlin implementation; exact upstream solver parity is not yet established |
| `glomap/estimators/cost_function.h`: `BATAPairwiseDirectionError` | `tracking/glomap/BataDirectionResidual.kt` | Residual and analytic Jacobians ported; unit tests added |
| `glomap/estimators/global_positioning.cc`: problem setup, parameter blocks, bounds and Ceres solve | Not yet ported | Pending |
| GLOMAP track establishment / track selection | Not yet ported | Pending |
| GLOMAP staged bundle adjustment | Not yet ported | Pending |
| GLOMAP retriangulation and final pruning | Not yet ported | Pending |

## Engine boundary

`ReconstructionEngine` is the shared UI-facing contract. The existing
`CameraTrackingEngine` remains the custom implementation and is still the
active default. A separate GLOMAP implementation can be introduced behind the
same contract without mixing its pipeline with the custom incremental solver.

## Verified upstream rotation controller order

At the pinned revision, `global_mapper.cc` performs:
1. First `SolveRotationAveraging` for filtering.
2. Rotation-error filtering.
3. Largest-connected-component extraction.
4. Second `SolveRotationAveraging` for final estimation.
5. Rotation-error filtering and a final largest-connected-component extraction.

The source comment says "three times", but the executable controller body contains
two solve calls. The Android wrapper follows the executable call sequence.
