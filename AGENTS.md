# Build

- Compile with `gradle compileKotlinJvm`.
- Launch the desktop game with `gradle runJvm`.
- If Gradle reports it cannot load `libnative-platform.dylib`, rerun the build with elevated access. The library may be present; the sandbox can block Gradle's `~/.gradle` lock file or local sockets.

## Output & Communication
- **No fluff:** Less preambles, acknowledgments ("Sure, I can help"), or trailing summaries of what you just did.
- **Terse prose:** Limit explanations to a few bullet points or a few lines unless asked for deep analysis.
- **Minimal code:** Write the simplest working solution. Do not add defensive abstractions, extra helpers, or unrequested tests. Old or unused code or helpers are cut aggressively.
- **Silent execution:** Run verification commands quietly; only report output if a test or build fails.

