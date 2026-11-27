# RoboXR for XREAL

## Overview

Android XR robot-teaching prototype for XREAL-oriented interaction and CNC recovery demonstrations. The Kotlin source separates cloud and offline trajectory planning, deterministic guardrail checks, robot-program compilation, and Firebase-backed audit storage.

## Included components

- `ai/GeminiTrajectoryPlanner.kt` and `ai/OfflineTrajectoryPlanner.kt`: structured task planning.
- `robotics/SafetyGuardrails.kt`: trajectory checks.
- `robotics/RoboticsCodeCompiler.kt`: URScript and ROS 2 FollowJointTrajectory JSON generation.
- `robotics/Sha256AuditChain.kt`: audit digests.
- `firebase/` and `data/`: authentication, App Check, and persistence.
- `xr/XrealSdkBridge.kt`: XR bridge.

These paths are relative to `app/src/main/java/com/example/`. Robot-program text is prototype output; software checks do not establish machinery safety certification or validate a physical cell.

## Local setup

1. Open the repository in Android Studio. The app declares compile SDK 36.1, target SDK 36, and minimum SDK 24.
2. Review toolchain versions in `gradle/libs.versions.toml`. The wrapper properties request Gradle 9.3.1, but wrapper launchers and the wrapper JAR are not included in this export. Restore a compatible wrapper before using wrapper commands.
3. Configure your own Firebase Android application for package `com.aistudio.roboxrai.k7m2px`, or change the package and registration together. Add its `app/google-services.json` locally.
4. Configure Firebase AI Logic, Authentication, Firestore, and App Check for your environment. Review `firebase.json`, `firestore.rules`, and the Firebase initialization code before connecting a backend.
5. For local debug builds, remove the `debugConfig` signing assignment from the debug build type to use Android's default debug signing, or supply the expected local debug keystore. Keep release keys and credentials outside Git.
6. Build and run the `app` configuration. Device and XR capability requirements need verification on your hardware.

The current AI planner uses Firebase AI Logic; `.env.example` states that a client-side `GEMINI_API_KEY` is not required. Verify the model configured in the planner against your Firebase project's available models.

## Tests and configuration

The repository includes Android test dependencies and `firestore.test.js`. Configure the relevant Android or Firestore test environment before running these tests. No build, hardware test, or Firestore deployment was performed by this documentation cleanup.
