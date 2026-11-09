# Build

- Compile with `gradle compileKotlinJvm`.
- Launch the desktop game with `gradle runJvm`.
- If Gradle reports it cannot load `libnative-platform.dylib`, rerun the build with elevated access. The library may be present; the sandbox can block Gradle's `~/.gradle` lock file or local sockets.
