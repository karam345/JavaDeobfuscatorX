# Workflow reference

Extract the release ZIP named `java-deobfuscation-*.zip` under `~/.agents/skills/`, keeping the `java-deobfuscation` directory intact. Use JDK 21 or newer and start a new Codex chat. On Unix, add executable permission with `chmod +x ~/.agents/skills/java-deobfuscation/runtime/bin/jdx` if needed.

From source, run `./gradlew skillZip` or `.\gradlew.bat skillZip`. The ZIP is in `build/distributions/`. Once installed, analysis works offline.

| Mode | Result |
|---|---|
| `analyze` | Archive inventory, bytecode patterns, Minecraft metadata, references and suggested names |
| `detect` | Same evidence, with the conversation focused on pattern detection and attribution |
| `report` | Same evidence, with links to the report and its tables |
| `decompile` | Reconstructed Java source and original resource bytes |
| `deobfuscate` | Conservative rewrites, class-only JAR, Java source and resources |
| `verify` | Static bytecode checks, javac on `--sources`, or both |
| `compare` | Both source trees, declaration and syntax checks, optional compile diagnostics, and hashes |
| `help` | CLI commands and limits |

- `$java-deobfuscation deobfuscate Recover readable Java from ./plugin.jar and export a Gradle project. Compile it if dependencies are available.`
- `$java-deobfuscation compare Compare the two backends on ./plugin.jar, focusing on listener classes.`
- `$java-deobfuscation verify Compile ./recovered/sources with ./api.jar as a dependency.`

`mappings.tsv` contains inferred names with original identifiers. `strings.tsv` contains text decoded from supported literal Base64 and hex calls. `bytecode.jar` contains the selected class-only analysis view. `resources.zip` retains original non-class entries. `source-quality.tsv` shows expected declarations and backend selection. Backend success and compilation are separate checks. The recovered behavior can still differ from the input.
