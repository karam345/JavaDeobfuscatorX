# Engine architecture

```mermaid
flowchart LR
    A[Input JAR] --> B[Bounded ZIP validation]
    B --> C[Selected class view]
    C --> D[ASM inspection]
    C --> E[Optional verified class rewrites]
    E --> F[Class-only staging archive]
    B --> G[Preserved resource ZIP]
    F --> H[Vineflower or CFR worker]
    H --> I[Coverage and syntax checks]
    I --> J[Optional javac worker]
    I --> P[Optional offline Gradle project]
    G --> P
    D --> K[Report and evidence tables]
    E --> K
    J --> K
```

## Input and limits

The CLI verifies ZIP entry paths, duplicates, expanded sizes and CRCs before creating output. It reads classes individually. Resource bytes are copied into a separate ZIP. Nested JARs are preserved as resources. Spring Boot and WAR class roots are recognized. Their nested libraries are not opened automatically.

By default the archive limit is 256 MiB expanded, 16 MiB per entry, 40,000 entries and 128 MiB compressed. Static verification caps method instructions multiplied by frame slots, and exception-table density. Decompilers and javac run one worker at a time, with per-worker heap and wall-clock limits.

For a manifest-enabled multi-release JAR, the selected view uses the greatest eligible version per class. Use `--release N` to inspect another view. Source compilation defaults to the highest selected classfile release, with Java 8 as the minimum. `--source-release` overrides this target. Full compilation needs a JDK that supports the requested release.

## Rewrite transactions

The original engine matched adjacent literals only. Loads and joins hid values from the optimizer, and any exception table disabled branch simplification. The decompiler accepted a clean process exit even when source units were missing or contained error stubs.

`ConstantFlow` uses ASM's analyzer to track integer, long and string constants. It also tracks known integer bits through masks and joins, so a predicate can be decided even when its operand comes from mutable state. Exception edges take part in the analysis. Rewrites keep operand producers that could have side effects. A jump with a known local value can bypass a dispatcher whose first two executable instructions load that local and switch on it. Unknown incoming edges still use the switch. Exception table cleanup removes exact shadowed entries and empty unreachable ranges while preserving active handlers and annotated entries.

`Bytecode` validates each candidate with ASM. A class receives up to six numeric and control-flow passes and one string pass. Only methods with changed control flow need new frames. If frame computation cannot resolve a hierarchy, the retry keeps the affected method's existing frames and applies other eligible rewrites. Frame computation reads class headers from the selected archive, staged dependencies, and JDK resources. Verification covers structural and basic data-flow constraints.

## Static string recovery

After the verified numeric and control-flow passes, `StringRecovery` uses `ConstantFlow` to recover inputs at remaining candidate sites and propagate proven text to later expressions. `Decoder` follows a concrete path through explicitly modeled bytecode. This includes same-class static methods, private helper calls, and verified public static methods in small classes in the same archive whose initialization is inert. It supports integer and long arithmetic, local byte, char and int arrays, loops with proven conditions, UTF-16 code units, and allowlisted JDK string and codec operations. It can read private static final `ConstantValue` fields of type int, long or String when bytecode never writes them. Method handlers, synchronization, writes outside local allocations, arbitrary bootstraps, and other unsupported operations cause the candidate to be retained. Path, method size, class and archive budgets bound the work.

Results known to be interned can become string constants. Fresh results are built from constant UTF-16 code units with `new String(char[])` to avoid interning new text. Java 8-style concatenation can be evaluated through local `StringBuilder` constructors, appends, reversal and snapshots. Replacements of builder snapshots retain `StringBuilder.toString()` to preserve runtime-specific empty-string identity. A site can emit up to 512 code units and a class can emit up to 8192. Calls that return an argument's existing reference are retained. The rewrite keeps argument producers. Malformed codec input and byte sequences that require charset replacement are left to the original implementation. The concatenation evaluator accepts only exact JDK `StringConcatFactory` handles and recipes with supported primitive and string arguments. It never invokes an input bootstrap.

`report.txt` counts detected sites, sites with proven text, verified replacements, skips and failures. Proven text can overlap a skip or rollback. `string-rewrites.tsv` lists bounded diagnostics and text from verified replacements. The input-only `strings.tsv` continues to show literal codec evidence.

## Decompiler recovery

For `deobfuscate`, the decompilers receive a separate bytecode view with Java-compatible names. The remapper updates declarations, resolvable references and method handles. Stripped record layouts can be recovered when the standard `ObjectMethods` bootstrap identifies every component and the matching constructor exists. Recognized record calls are lowered to Java operations while keeping their surrounding control flow. The exported `bytecode.jar` retains the original class and member names.

The source inventory uses inner class metadata to identify expected files and named declarations. A bounded javac worker parses the emitted files. Auto mode can retry incomplete roots with CFR, using the staged archive to resolve related classes. Compare mode and compilation-driven fallback use the full archive. Backend candidates are ranked by declaration coverage, syntax, failure markers and comparable compilation diagnostics. The tool parses and optionally recompiles a combined tree before selecting it. If syntax errors still block javac from attributing the full tree, a separate syntax-valid subset can provide additional diagnostics.

Compilation distinguishes missing packages and modules from unresolved symbols and other compiler errors. A missing name can reflect a missing library or a decompiler defect. The tool never guesses Maven coordinates or retrieves dependencies.

## Evidence

- `report.txt` records the archive, selected view, bytecode findings, rewrites and verification.
- `class-issues.tsv` and `rewrite-issues.tsv` record malformed classes, skipped rewrites and rollbacks.
- `mappings.tsv` records suggested names with original owner, member and descriptor.
- `source-renames.tsv` records aliases and recovered record layouts applied to the decompiler input.
- `references.tsv` records selected class-to-class references from declarations and instructions.
- `hierarchy.tsv` records declared superclass and interface edges. `calls.tsv` records symbolic method call sites and dispatch kinds. Virtual and interface calls retain their declared targets.
- `strings.tsv` contains decoded literal payloads. Treat it as potentially sensitive.
- `bootstraps.tsv` and `wrappers.tsv` identify bootstrap references and forwarding shapes without executing target code.
- `minecraft.tsv` records plugin and mod descriptors and matched entrypoints.
- `*-diagnostics.tsv`, `syntax-*.tsv` and `javac-*.tsv` hold bounded worker evidence.
- `source-quality.tsv` records expected units, diagnostics and backend selections.
- `comparison.tsv` compares file coverage and hashes across decompilers.

The detector can identify manifest declarations or vendor-named resources for tools such as Allatori, Zelix, Stringer, DashO, ProGuard, R8, or yGuard. These indicators can be forged or bundled accidentally. Generic control-flow and API-use counts are clues, and ordinary code can exhibit them.

## Limits and priorities

General control-flow unflattening, mutable static lookup tables, cross-class decoder paths, arbitrary bootstrap execution, and automatic class/member renaming are outside the current engine. Local debug identifiers can be repaired while preserving binary member and parameter names. The tool preserves nested JARs as resources. Java file counts can differ from class counts because inner classes often appear in an outer source file.

`--project` exports an offline Java Gradle build with conventional source and resource directories. It preserves original descriptors and copies supplied staged libraries. Repacking invalidates original signatures, which remain in `resources.zip`. Loader-specific Gradle configuration, mapping namespaces, and original build scripts cannot be inferred reliably from bytecode.

## Benchmark

Run `./gradlew benchmark --args=5000` to measure inspection, verification and staging on a generated archive. Each JVM warms up once and records three iterations. On Windows with JDK 25, a 768 MiB heap and four active processors, two JVMs per version measured median times of 1.700 s for 0.2.0 and 1.689 s for 0.3.0. The runs overlapped in timing. This fixture does not measure custom decoder loops or decompiler work. A baseline JFR profile identified ASM analyzer traversal and class parsing among the sampled hot methods.
