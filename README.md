# Build

## Local build

Requirements: JDK 17, Android SDK 34, NDK 27.0.12077973, and CMake 3.22.1.

```bash
./gradlew testReleaseUnitTest
./gradlew assembleRelease
```

The release APK is written to:

```
app/build/outputs/apk/release/
```

## GitHub Actions

The workflow at `.github/workflows/android-build.yml` runs on pushes to
`main`, `master`, and `global-reconstruction-engine`, on pull requests
targeting `main` or `master`, and through manual dispatch.

It runs the tracking-engine release unit tests, builds the Release APK, and
uploads the APK as the `trackstudio-release-apk` artifact. Native build logs
are uploaded when a workflow fails.

## Technical notes

Architecture, solver design, dependency audits, and porting notes are kept in
the `docs/` directory.