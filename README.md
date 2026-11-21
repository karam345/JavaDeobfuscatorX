# JavaDeobfuscatorX

Local Java JAR analysis and deobfuscation for the terminal and the **`java-deobfuscation`** Codex skill. The Java 21 engine inspects bytecode with ASM, applies conservative rewrites, and reconstructs Java source with Vineflower and CFR.

## Quick start

Get the CLI ZIP or the packaged skill from [GitHub Releases](https://github.com/karam345/JavaDeobfuscatorX/releases). Install JDK 21 or newer. Extract the CLI ZIP and run `bin/jdx` (`bin\jdx.bat` on Windows). On Unix, run `chmod +x bin/jdx` if the extractor strips executable mode.

```sh
bin/jdx analyze plugin.jar --out inspection
bin/jdx deobfuscate plugin.jar --out recovered --compile --classpath paper-api.jar
bin/jdx compare plugin.jar --out comparison
bin/jdx verify --sources recovered/sources --classpath paper-api.jar --out compile-check
```

Use a fresh output directory for each run. Without `--out`, the CLI chooses `jdx-out/<input>-<command>`. Analysis works offline after installation.

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
| `decompile` | Vineflower Java source, CFR fallback on detected failure, original resources |
| `deobfuscate` | Verified rewrites of selected classes, reconstructed sources and original resources |
| `compare` | Both source trees plus a file coverage and SHA-256 comparison |
| `verify` | Static bytecode checks, javac with `--sources`, or both |
| `help` | Exact flags and defaults |

## Capabilities

- **Bytecode.** Folds constant integer arithmetic, shifts, bitwise expressions and narrowing. Resolves adjacent constant integer branches and switches, removes newly unreachable instructions, eliminates literal push/pop and simplifies redundant jumps. Verifies each changed class and rolls back on failure.
- **Strings.** Decodes literal UTF-8 payloads passed to `Base64.getDecoder().decode(String)` for the report. Custom codecs and cipher implementations stay in source.
- **Detection.** Reports short names, short wrappers, switch loops, exception handlers, reflection, crypto calls, synthetic methods and bootstrap patterns. Vendor attribution requires concrete metadata evidence and stays explicitly qualified.
- **Source.** The decompilers reconstruct imports and format Java. `mappings.tsv` provides inferred names from plugin roles, field types and accessors. It preserves owners, member names, descriptors and confidence. Inferred names are suggestions for review.
- **Minecraft.** Reads Bukkit-family, Paper, BungeeCord and Velocity descriptors. Reports entrypoints, permissions, commands, dependencies, listener annotations, Bukkit and Folia schedulers, Paper/Purpur/NMS references, PacketEvents and recognizable bundled libraries. `resources.zip` retains original resource bytes.

### Verification and limitations

`--compile` runs javac in a separate worker with annotation processing disabled and only staged dependency classes on the classpath. A passing source build does not establish equivalent behavior. `bytecode.jar` contains the flattened selected class view for offline inspection. Source comparison measures file presence and hashes.

Archive entries are size-bounded and checked for unsafe paths, duplicates and CRC errors. Decompilers and javac run in bounded JVM workers. See the [security policy](SECURITY.md) and [engine details and roadmap](docs/architecture.md) for the trust boundary and technique coverage.

## Build and test

```sh
./gradlew clean build installDist skillZip
./gradlew benchmark --args=1000
```

Use `.\gradlew.bat` on Windows. Tests use original synthetic fixtures and exercise ZIP validation, differential execution of trusted test bytecode, both decompilers and source compilation. The benchmark measures archive inspection, verification and rewriting.

See the [examples](examples/README.md), [contribution guide](CONTRIBUTING.md), [AI disclosure](AI_DISCLOSURE.md), and [Apache-2.0 license](LICENSE).
