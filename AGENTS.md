# Build

- Compile with `gradle compileKotlinJvm`.
- Launch the desktop game with `gradle runJvm`.
- If Gradle reports it cannot load `libnative-platform.dylib`, rerun the build with elevated access. The library may be present; the sandbox can block Gradle's `~/.gradle` lock file or local sockets.

## Output & Communication
- **No fluff:** Less preambles, acknowledgments ("Sure, I can help"), or trailing summaries of what you just did.
- **Diffs only:** Reply with unified diffs or changed blocks only. Less rewrite full files unless explicitly requested.
- **Terse prose:** Limit explanations to a few bullet points or a few lines unless asked for deep analysis.
- **Minimal code:** Write the simplest working solution. Do not add defensive abstractions, extra helpers, or unrequested tests.
- **Silent execution:** Run verification commands quietly; only report output if a test or build fails.

