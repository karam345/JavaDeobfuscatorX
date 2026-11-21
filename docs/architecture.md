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

`ConstantFlow` uses ASM's analyzer with a constant lattice for integer and long values. Joins keep a constant only when incoming facts agree. Exception edges take part in the analysis. Rewrites keep operand producers that could have side effects and remove adjacent literal or identical local loads only when safe. Integer and long overflow, narrowing, shift masking, and exceptions from division by zero are preserved. Proven branches and dispatches expose unreachable instructions. Exception table cleanup removes exact shadowed entries and empty unreachable ranges while preserving active handlers and annotated entries.

`Bytecode` validates each candidate with ASM. A class receives up to six passes and retains its last verified bytes if verification or hierarchy resolution fails. Frame computation reads class headers from the selected archive view, staged dependencies, and JDK resources. It does not load those classes. Unchanged methods are copied from the original bytecode. Verification covers structural and basic data-flow constraints. Add a differential fixture for each semantically relevant rewrite.

## Decompiler recovery

The source inventory uses inner class metadata to identify expected files and named declarations. A bounded javac worker parses the emitted files. Per-unit ranking considers missing declarations, syntax errors, backend failure markers and optional compilation diagnostics. JVM binary names, source filenames, and nested declarations all inform coverage. Auto mode can retry with CFR after an incomplete Vineflower run. Selected units are parsed again and recompiled when requested before replacing the primary source tree.

Compilation distinguishes missing packages and modules from unresolved symbols and other compiler errors. A missing name can reflect a missing library or a decompiler defect. The tool never guesses Maven coordinates or retrieves dependencies.

## Evidence

- `report.txt` records the archive, selected view, bytecode findings, rewrites and verification.
- `class-issues.tsv` and `rewrite-issues.tsv` record malformed classes, skipped rewrites and rollbacks.
- `mappings.tsv` records suggested names with original owner, member and descriptor.
- `references.tsv` records selected class-to-class references from declarations and instructions.
- `strings.tsv` contains decoded literal payloads. Treat it as potentially sensitive.
- `bootstraps.tsv` and `wrappers.tsv` identify bootstrap references and forwarding shapes without executing target code.
- `minecraft.tsv` records plugin and mod descriptors and matched entrypoints.
- `*-diagnostics.tsv`, `syntax-*.tsv` and `javac-*.tsv` hold bounded worker evidence.
- `source-quality.tsv` records expected units, diagnostics and backend selections.
- `comparison.tsv` compares file coverage and hashes across decompilers.

The detector can identify manifest declarations or vendor-named resources for tools such as Allatori, Zelix, Stringer, DashO, ProGuard, R8, or yGuard. These indicators can be forged or bundled accidentally. Generic control-flow and API-use counts are clues, and ordinary code can exhibit them.

## Limits and priorities

General control-flow unflattening, custom decoder emulation, arbitrary bootstrap execution, and automatic class/member renaming are outside the current engine. Local debug identifiers can be repaired without changing field names, method names or reflection-visible parameter names. The tool preserves nested JARs as resources. Java file counts can differ from class counts because inner classes often appear in an outer source file.

`--project` exports an offline Java Gradle build with conventional source and resource directories. It preserves original descriptors and copies supplied staged libraries. Repacking invalidates original signatures, which remain in `resources.zip`. Loader-specific Gradle configuration, mapping namespaces, and original build scripts cannot be inferred reliably from bytecode.
