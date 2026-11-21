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
| `compare` | Both source trees plus per-path file presence and hashes |
| `help` | CLI commands and limits |

- `$java-deobfuscation deobfuscate Recover readable Java from ./plugin.jar and compile it if dependencies are available.`
- `$java-deobfuscation compare Compare the two backends on ./plugin.jar, focusing on listener classes.`
- `$java-deobfuscation verify Compile ./recovered/sources with ./api.jar as a dependency.`

`mappings.tsv` contains inferred names with original identifiers. `strings.tsv` contains text decoded from literal Base64 calls. `bytecode.jar` contains the selected class-only analysis view. `resources.zip` retains non-class entries, including plugin descriptors. Backend success means a worker emitted files with no detected error. Compilation success means javac accepted those sources with the supplied dependencies. Behavior can still differ from the input.
