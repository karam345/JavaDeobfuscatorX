# Changelog

## 0.2.0

- Propagate integer and long constants through locals, the stack and control-flow joins. Verify each rewrite pass and retain the last verified bytecode on failure.
- Resolve proven predicates and switches, thread jumps, remove unreachable instructions and shadowed handlers, and repair invalid local debug names.
- Reconstruct literal Base64 and hex strings and report forwarding methods, bridges and bootstrap references.
- Validate source declarations and syntax, rank alternatives by diagnostics, and recover incomplete Vineflower units with CFR.
- Export offline Gradle projects and recognize Fabric, Forge-family and NeoForge metadata.

## 0.1.1

- Pin the JUnit BOM POM checksum so clean Gradle caches can verify test dependencies.

## 0.1.0

- Added one Codex skill and a Java 21 CLI for local JAR analysis and reconstruction.
- Added bounded ZIP checks, ASM verification and rollback-capable constant and control-flow simplification.
- Integrated Vineflower, CFR and javac workers with resource limits.
- Added source comparison, Minecraft metadata, reference indexes, name suggestions and literal Base64 decoding.
- Added synthetic regression and differential tests, a benchmark and GitHub release automation.
