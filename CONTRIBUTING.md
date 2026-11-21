# Contributing

Use JDK 21 and the checked-in Gradle Wrapper. Run `./gradlew clean build skillZip` before submitting a change. Build on Windows with `gradlew.bat`.

Keep transformations conservative. Describe the pattern, its effect on stack and control flow, and the conditions that preserve original bytecode. Use synthetic fixtures and compare behavior before and after. Keep third-party binaries, credentials and decoded data out of commits and issues.

When changing a dependency, regenerate Gradle verification metadata against its official repository, review the difference, and update licensing. Report vulnerabilities through the [security policy](SECURITY.md) and follow the [code of conduct](CODE_OF_CONDUCT.md).
