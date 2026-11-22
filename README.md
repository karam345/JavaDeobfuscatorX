<p align="center"><img src="assets/logo.svg" width="112" height="112" alt="JavaDeobfuscatorX logo"></p>

# JavaDeobfuscatorX

Local Java JAR analysis and deobfuscation for the terminal and the **`java-deobfuscation`** Codex skill. The Java 21 engine inspects bytecode with ASM, applies conservative rewrites, and reconstructs Java source with Vineflower and CFR.

## Quick start

Get the CLI ZIP or the packaged skill from [GitHub Releases](https://github.com/karam345/JavaDeobfuscatorX/releases). Install JDK 21 or newer. Extract the CLI ZIP and run `bin/jdx` (`bin\jdx.bat` on Windows). On Unix, run `chmod +x bin/jdx` if the extractor strips executable mode.

```sh
bin/jdx analyze plugin.jar --out inspection
bin/jdx deobfuscate plugin.jar --out recovered --compile --project --classpath paper-api.jar
bin/jdx compare plugin.jar --out comparison
bin/jdx verify --sources recovered/sources --classpath paper-api.jar --out compile-check
```

Quote paths containing spaces, including dependency paths. Use a fresh output directory for each run. Without `--out`, the CLI chooses `jdx-out/<input>-<command>`. Analysis works offline after installation.

### Codex installation

Extract the **`java-deobfuscation-*.zip`** into `~/.agents/skills/`. Keep the `java-deobfuscation` folder intact, including `SKILL.md` and `runtime/`. Start a new Codex chat. On Unix, restore launcher executable permission if needed.

Attach a JAR with a readable local path, place it in the workspace, or give Codex its path. Then enter:

```text
$java-deobfuscation decompile Make ./plugin.jar as readable as possible.
$java-deobfuscation detect Identify the techniques in the attached JAR.
$java-deobfuscation verify Check whether ./recovered/sources compiles with ./paper-api.jar.
```

Codex can select the skill for a matching natural-language request. Use `$java-deobfuscation <mode>` to invoke it explicitly. These modes are CLI commands and skill inputs. They do not register native slash commands. If a chat attachment has no readable path, provide a workspace copy or a local path. Name the target if several JARs are plausible. [OpenAI skill documentation](https://learn.chatgpt.com/docs/build-skills)

## Commands

| Mode | Artifacts and checks |
|---|---|
| `analyze` | Archive inventory, bytecode patterns, class references and Minecraft metadata |
| `detect` | Same evidence with an obfuscation-focused skill response |
| `report` | Same evidence exported as a text report and TSV files |
| `decompile` | Java source, declaration and syntax checks, CFR recovery, and original resources |
| `deobfuscate` | Verified rewrites of selected classes, reconstructed sources and original resources |
| `compare` | Both source trees, declaration coverage, syntax and optional compile diagnostics, and SHA-256 comparison |
| `verify` | Static bytecode checks, javac with `--sources`, or both |
| `help` | Exact flags and defaults |

## Capabilities

- **Bytecode.** Propagates integer and long constants through locals, stack slots and control-flow joins. Simplifies proven predicates and switches, bypasses proven edges into switch dispatchers, threads jumps, and removes unreachable instructions and shadowed handlers. Keeps the last verified pass on failure.
- **Strings.** Recovers constant Base64, hex, XOR and arithmetic decoding, local lookup tables, and supported `StringConcatFactory` calls. A bounded interpreter follows known inputs through private static helpers in the same class. Rewrites retain argument evaluation and keep fresh strings fresh. `string-rewrites.tsv` records verified replacements and skipped or failed sites.
- **Detection.** Reports forwarding and bridge methods, reflection and crypto calls, switch loops and static bootstrap references. Vendor attribution remains separate from general patterns.
- **Source.** Checks expected declarations and parses the recovered Java. Auto mode can target incomplete units with CFR, select better sources using diagnostics, and recheck the combined output.
- **Identifiers.** Repairs invalid local debug names consistently. `mappings.tsv` gives suggested class and member names with confidence and original descriptors. `hierarchy.tsv` and `calls.tsv` describe inheritance and bytecode call sites.
- **Minecraft.** Reads Bukkit-family, Paper, BungeeCord, Velocity and Fabric descriptors. Recognizes Forge-family and NeoForge descriptors and `@Mod` annotations. Reports plugin and mod entrypoints.
- **Projects.** `--project` exports an offline Gradle Java project with recovered source, resources and supplied classpath JARs. `resources.zip` retains original resource bytes and signatures.

### Verification and limitations

`--compile` runs javac in a separate worker with annotation processing disabled. It uses staged local dependencies on the classpath or module path as appropriate. Diagnostics distinguish missing dependencies, unresolved symbols and other compile errors, with hints for recognized Minecraft API packages. The source target comes from the selected classfile versions. Use `--source-release` to override it and `--release` to select a multi-release archive view.

Successful parsing and compilation do not establish equivalent behavior. `bytecode.jar` contains the selected classes for offline inspection. Reconstructed mods can still require loader plugins, mappings and additional dependencies.

Archive entries are size-bounded and checked for unsafe paths, duplicates and CRC errors. Decompilers and javac run in bounded JVM workers. See the [security policy](SECURITY.md) and [engine details and roadmap](docs/architecture.md) for the trust boundary and technique coverage.

## Build and test

```sh
./gradlew clean build installDist verifySkillZip
./gradlew benchmark --args=1000
```

Use `.\gradlew.bat` on Windows. Tests use original synthetic fixtures and exercise ZIP validation, differential execution of trusted test bytecode, both decompilers and source compilation. The benchmark measures archive inspection, verification and rewriting.

See the [examples](examples/README.md), [contribution guide](CONTRIBUTING.md), [AI disclosure](AI_DISCLOSURE.md), and [Apache-2.0 license](LICENSE).
