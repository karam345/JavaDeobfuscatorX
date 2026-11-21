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
    void reportsCompilationFailureAndReturnsPartialExitCode() throws Exception {
        Path sources = Files.createDirectory(temp.resolve("sources"));
        Files.writeString(sources.resolve("Broken.java"), "class Broken { MissingType value; }");
        Path output = temp.resolve("verification");
        var result = run("verify", "--sources", sources.toString(), "--out", output.toString());
        assertEquals(3, result.code, result.log);
        assertTrue(Files.readString(output.resolve("javac-provided.tsv")).contains("MissingType"));
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
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                java.io.File.separatorChar == '\\' ? "java.exe" : "java");
        var command = new ArrayList<>(List.of(executable.toString(), "-Xmx768m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", System.getProperty("jdx.testClasspath"),
                Main.class.getName()));
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
