# Workflow reference

Extract the release ZIP named `java-deobfuscation-*.zip` under `~/.agents/skills/`, keeping the `java-deobfuscation` directory intact. Use JDK 21 or newer and start a new Codex chat. On Unix, add executable permission with `chmod +x ~/.agents/skills/java-deobfuscation/runtime/bin/jdx` if needed.

From source, run `./gradlew verifySkillZip` or `.\gradlew.bat verifySkillZip`. The ZIP is in `build/distributions/`. Verification extracts it to `build/verified-skill/` and checks the runtime, instructions and icon paths. Once installed, analysis works offline.

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

`mappings.tsv` contains name suggestions. `source-renames.tsv` records aliases and recovered records applied to the bytecode supplied to the decompilers. Those aliases can affect reflection and resource entry points in a rebuilt project. `string-rewrites.tsv` records verified replacements, skipped candidates and failures. `bytecode.jar` contains the selected class-only analysis view with original class and member names. `resources.zip` retains original non-class entries. `source-quality.tsv` shows backend selection, Java syntax status and full-tree compilation status.
