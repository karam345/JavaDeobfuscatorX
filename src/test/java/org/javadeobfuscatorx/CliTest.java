package org.javadeobfuscatorx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path temp;

    @Test
    void deobfuscatesExportsCompilesAndPreservesResources() throws Exception {
        byte[] descriptor = "name: Demo\nmain: sample.a\n".getBytes(StandardCharsets.UTF_8);
        var jar = Fixtures.jar(temp.resolve("input.jar"), Map.of(
                "sample/a.class", Fixtures.constants("sample/a", 33), "plugin.yml", descriptor));
        byte[] original = Files.readAllBytes(jar);
        Path output = temp.resolve("recovered");
        var result = run("deobfuscate", jar.toString(), "--out", output.toString(), "--compile");
        assertEquals(0, result.code, result.log);
        assertTrue(result.log.contains("Applied constant branch"), result.log);
        assertTrue(result.log.contains("javac primary: passed"), result.log);
        assertTrue(Files.isRegularFile(output.resolve("sources/sample/a.java")));
        assertTrue(Files.isRegularFile(output.resolve("bytecode.jar")));
        assertTrue(Files.readString(output.resolve("strings.tsv")).contains("Synthetic fixture"));
        assertTrue(Files.readString(output.resolve("calls.tsv")).contains("static\tjava/util/Base64\tgetDecoder"));
        assertTrue(Files.readString(output.resolve("hierarchy.tsv")).contains("sample/a\tsuperclass\tjava/lang/Object"));
        try (var resources = new ZipFile(output.resolve("resources.zip").toFile())) {
            try (var stream = resources.getInputStream(resources.getEntry("plugin.yml"))) {
                assertArrayEquals(descriptor, stream.readAllBytes());
            }
        }
        assertArrayEquals(original, Files.readAllBytes(jar));
        assertEquals(2, run("deobfuscate", jar.toString(), "--out", output.toString()).code);
    }

    @Test
    void comparesTwoRealBackendOutputsAndVerifiesProvidedSources() throws Exception {
        var jar = Fixtures.jar(temp.resolve("input.jar"), Map.of("sample/Demo.class", Fixtures.marker("sample/Demo", 42)));
        Path output = temp.resolve("compared");
        var result = run("compare", jar.toString(), "--out", output.toString(), "--compile");
        assertEquals(0, result.code, () -> result.log + diagnostics(output.resolve("cfr-diagnostics.tsv")));
        assertTrue(Files.isRegularFile(output.resolve("vineflower/sample/Demo.java")), result.log);
        assertTrue(Files.isRegularFile(output.resolve("cfr/sample/Demo.java")), result.log);
        assertTrue(Files.readString(output.resolve("comparison.tsv")).contains("sample/Demo.java\tdifferent UTF-8 bytes"));
        var verify = run("verify", "--sources", output.resolve("cfr").toString(), "--out", temp.resolve("verified").toString());
        assertEquals(0, verify.code, verify.log);
    }

    @Test
    void cfrTargetsOneRootAndResolvesItsInnerClassFromTheFullArchive() throws Exception {
        Path source = temp.resolve("Outer.java");
        Files.writeString(source, "public class Outer { public static class Inner { public int value() { return 42; } } }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21",
                "-d", temp.toString(), source.toString()));
        Path full = Fixtures.jar(temp.resolve("full archive.jar"), Map.of(
                "Outer.class", Files.readAllBytes(temp.resolve("Outer.class")),
                "Outer$Inner.class", Files.readAllBytes(temp.resolve("Outer$Inner.class")),
                "Other.class", Fixtures.marker("Other", 8)));
        Path targets = temp.resolve("target classes.jar");
        assertTrue(Exports.subset(full, targets, java.util.Set.of("Outer.class")));
        Path output = Files.createDirectory(temp.resolve("targeted sources"));
        Path diagnostics = temp.resolve("targeted.tsv");
        var result = runMain(DecompilerWorker.class, "cfr", targets.toString(), output.toString(), diagnostics.toString(), "21", full.toString());
        assertEquals(0, result.code, () -> result.log + diagnostics(diagnostics));
        assertFalse(Files.exists(output.resolve("Other.java")));
        assertTrue(Files.readString(output.resolve("Outer.java")).contains("class Inner"));
    }

    @Test
    void reportsCompilationFailureAndReturnsPartialExitCode() throws Exception {
        Path sources = Files.createDirectory(temp.resolve("sources"));
        Files.writeString(sources.resolve("Broken.java"), "class Broken { MissingType value; }");
        Path output = temp.resolve("verification");
        var result = run("verify", "--sources", sources.toString(), "--out", output.toString());
        assertEquals(3, result.code, result.log);
        assertTrue(Files.readString(output.resolve("javac-provided.tsv")).contains("MissingType"));
    }

    @Test
    void exportsGradleProjectAndRecognizesModLoaderMetadata() throws Exception {
        byte[] fabric = """
                {"schemaVersion":1,"id":"demo","version":"1.0","depends":{"fabricloader":"*"},
                 "entrypoints":{"main":["sample.Demo::value"]},"mixins":["demo.mixins.json"]}
                """.getBytes(StandardCharsets.UTF_8);
        var jar = Fixtures.jar(temp.resolve("mod.jar"), Map.of(
                "WEB-INF/classes/sample/Demo.class", Fixtures.marker("sample/Demo", 21),
                "fabric.mod.json", fabric,
                "META-INF/mods.toml", "modLoader=\"javafml\"\n".getBytes(StandardCharsets.UTF_8),
                "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n".getBytes(StandardCharsets.UTF_8),
                "WEB-INF/classes/demo.mixins.json", "{}".getBytes(StandardCharsets.UTF_8),
                "META-INF/OLD.SF", "Signature-Version: 1.0".getBytes(StandardCharsets.UTF_8)));
        Path output = temp.resolve("project-export");
        var result = run("decompile", jar.toString(), "--out", output.toString(), "--compile", "--project");
        assertEquals(0, result.code, result.log);
        assertArrayEquals(fabric, Files.readAllBytes(output.resolve("project/src/main/resources/fabric.mod.json")));
        assertTrue(Files.isRegularFile(output.resolve("project/src/main/resources/META-INF/mods.toml")));
        assertTrue(Files.isRegularFile(output.resolve("project/src/main/resources/META-INF/neoforge.mods.toml")));
        assertTrue(Files.isRegularFile(output.resolve("project/src/main/resources/demo.mixins.json")));
        assertTrue(Files.isRegularFile(output.resolve("project/src/main/java/sample/Demo.java")));
        assertFalse(Files.exists(output.resolve("project/src/main/resources/META-INF/OLD.SF")));
        assertTrue(Files.readString(output.resolve("project/build.gradle")).contains("options.release = 21"));
        assertTrue(Files.readString(output.resolve("minecraft.tsv")).contains("mod entrypoint\tsample/Demo\tpresent"));
        try (var resources = new ZipFile(output.resolve("resources.zip").toFile())) {
            assertNotNull(resources.getEntry("META-INF/OLD.SF"));
        }
    }

    @Test
    void distinguishesMissingDependencyFromSourceError() throws Exception {
        Path sources = Files.createDirectory(temp.resolve("sources"));
        Files.writeString(sources.resolve("Broken.java"), """
                import fixture.unavailable.Api;
                import org.bukkit.World;
                import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
                class Broken {
                    Api api;
                    int invalid = "text";
                }
                """);
        Path output = temp.resolve("verification");
        var result = run("verify", "--sources", sources.toString(), "--source-release", "17", "--out", output.toString());
        assertEquals(3, result.code, result.log);
        String details = Files.readString(output.resolve("javac-provided.tsv"));
        assertTrue(details.contains("missing-dependency\tBroken.java\tcompiler.err.doesnt.exist"), details);
        assertTrue(details.contains("compilation\tBroken.java\tcompiler.err.prob.found.req"), details);
        assertTrue(details.contains("package fixture.unavailable"), details);
        assertTrue(details.contains("matching Bukkit, Spigot or Paper API"), details);
        assertTrue(details.contains("matching Paper or Folia API"), details);
    }

    @Test
    void compilesModuleWithStagedNamedAndFilenameDerivedDependencies() throws Exception {
        Path named = Fixtures.jar(temp.resolve("renamed.jar"), Map.of(
                "META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\n"
                        + "Automatic-Module-Name: fixture.named\r\nClass-Path: missing.jar\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8),
                "fixture/named/Named.class", Fixtures.marker("fixture/named/Named", 7)));
        Path implicit = Fixtures.jar(temp.resolve("fixture-implicit-1.0.jar"),
                Map.of("fixture/implicit/Implicit.class", Fixtures.marker("fixture/implicit/Implicit", 8)));
        Path source = Files.createDirectory(temp.resolve("src"));
        Files.writeString(source.resolve("module-info.java"),
                "module fixture.consumer { requires fixture.named; requires fixture.implicit; }");
        Path packageRoot = Files.createDirectories(source.resolve("fixture/consumer"));
        Files.writeString(packageRoot.resolve("UsesModules.java"), """
                package fixture.consumer;
                class UsesModules {
                    int value() { return fixture.named.Named.value() + fixture.implicit.Implicit.value(); }
                }
                """);
        var result = run("verify", "--sources", source.toString(), "--classpath", named.toString(),
                "--classpath", implicit.toString(), "--out", temp.resolve("modular").toString());
        assertEquals(0, result.code, result.log);
        assertTrue(result.log.contains("javac provided: passed"), result.log);
    }

    @Test
    void inspectionCommandsDoNotProduceSourceOrRewriteTheInput() throws Exception {
        var jar = Fixtures.jar(temp.resolve("plain.jar"), Map.of("sample/A.class", Fixtures.marker("sample/A", 1)));
        byte[] original = Files.readAllBytes(jar);
        for (String command : List.of("analyze", "detect", "report", "verify")) {
            Path output = temp.resolve(command);
            var result = run(command, jar.toString(), "--out", output.toString());
            assertEquals(0, result.code, result.log);
            assertTrue(Files.isRegularFile(output.resolve("report.txt")));
            assertFalse(Files.exists(output.resolve("sources")));
            assertFalse(Files.exists(output.resolve("bytecode.jar")));
        }
        assertTrue(run("help").log.contains("deobfuscate"));
        assertArrayEquals(original, Files.readAllBytes(jar));
    }

    private Result run(String... arguments) throws Exception {
        return runMain(Main.class, arguments);
    }

    private Result runMain(Class<?> main, String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                java.io.File.separatorChar == '\\' ? "java.exe" : "java");
        var command = new ArrayList<>(List.of(executable.toString(), "-Xmx768m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", System.getProperty("jdx.testClasspath"),
                main.getName()));
        command.addAll(Arrays.asList(arguments));
        Path log = Files.createTempFile(temp, "cli-", ".log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "CLI subprocess timed out");
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
    }

    private static String diagnostics(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "(worker did not write diagnostics)";
        } catch (java.io.IOException failure) {
            return failure.toString();
        }
    }

    private record Result(int code, String log) {}
}
