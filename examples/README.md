# Examples

Build with `./gradlew installDist` (`.\gradlew.bat installDist` on Windows). Use the generated `jdx` launcher.

```sh
jdx analyze sample.jar --out sample-analysis
jdx detect sample.jar --out sample-patterns
jdx deobfuscate sample.jar --compile --out sample-recovered
jdx compare sample.jar --compile --out sample-comparison
jdx verify sample.jar --sources sample-recovered/sources --out sample-check
```

Repeat `--classpath` for each local dependency JAR. `verify` accepts a JAR, a source directory passed with `--sources`, or both. `$java-deobfuscation analyze ./sample.jar and explain the detected patterns.` invokes the same workflow in Codex.

The integration tests create build, comparison and Minecraft metadata fixtures in temporary directories. Run them with `./gradlew test`.
