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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

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
    void repairsJvmOnlyNamesAndRecordMetadataAndEvaluatesCrossClassDecoders() throws Exception {
        Path original = Files.createDirectory(temp.resolve("original"));
        Path source = temp.resolve("Fixture.java");
        Files.writeString(source, """
                package demo;
                public class Fixture {
                    public static int guarded(int input) {
                        if (((input | 1) & 1) == 0) return -1;
                        return input + 2;
                    }
                    public static String text() {
                        return Decoder.xor("Idmmn") + Decoder.table(1) + Decoder.base("IQ==");
                    }
                    public static String guardedDecoder() { return Stateful.read("kept"); }
                    public int value() { return 9; }
                    public long valueLong() { return 10; }
                    public static long variants() {
                        var item = new Fixture();
                        return item.value() + item.valueLong() + Inner.marker();
                    }
                    public static class Inner {
                        public static int marker() { return 2; }
                    }
                }
                class Parent {
                    public static int field = 41;
                    private static int a() { return field; }
                    public static java.util.function.IntSupplier reference() { return Parent::a; }
                }
                class Child extends Parent {
                    public static int inherited() { return Child.field; }
                }
                record Pair(int number, String text) {}
                """);
        Path decoder = temp.resolve("Decoder.java");
        Files.writeString(decoder, """
                package demo;
                public class Decoder {
                    public static String xor(String value) {
                        char[] letters = value.toCharArray();
                        for (int i = 0; i < letters.length; i++) letters[i] ^= 1;
                        return new String(letters);
                    }
                    public static String table(int index) {
                        char[] table = {'z', 'X'};
                        return new String(new char[]{table[index]});
                    }
                    public static String base(String input) {
                        return new String(java.util.Base64.getDecoder().decode(input), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                """);
        Path stateful = temp.resolve("Stateful.java");
        Files.writeString(stateful, """
                package demo;
                public class Stateful {
                    static int state = 7;
                    public static String read(String input) { return new String(input); }
                }
                """);
        compileFixtures(original, List.of(source, decoder, stateful));
        var entries = new TreeMap<String, byte[]>();
        for (String name : List.of("Fixture", "Fixture$Inner", "Decoder", "Stateful", "Parent", "Child", "Pair")) {
            var node = Bytecode.parse(Files.readAllBytes(original.resolve("demo/" + name + ".class")));
            if (name.equals("Pair")) {
                node.access &= ~Opcodes.ACC_RECORD;
                node.recordComponents = null;
            }
            var writer = new ClassWriter(0);
            node.accept(new ClassRemapper(writer, new Remapper(Opcodes.ASM9) {
                @Override public String map(String name) {
                    return name.equals("demo/Fixture$Inner") ? "demo/Fixture$Fixture" : name;
                }
                @Override public String mapMethodName(String owner, String method, String desc) {
                    return owner.equals("demo/Fixture") && method.equals("valueLong") ? "value" : method;
                }
                @Override public String mapFieldName(String owner, String field, String desc) {
                    if ((owner.equals("demo/Parent") || owner.equals("demo/Child")) && field.equals("field")) return "do";
                    if (owner.equals("demo/Pair") && field.equals("number")) return "n";
                    return field;
                }
            }));
            byte[] bytes = writer.toByteArray();
            entries.put(new org.objectweb.asm.ClassReader(bytes).getClassName() + ".class", bytes);
        }
        var jar = Fixtures.jar(temp.resolve("obfuscated.jar"), entries);
        Path baseline = temp.resolve("baseline");
        var before = run("decompile", jar.toString(), "--engine", "vineflower", "--out", baseline.toString(), "--compile");
        assertEquals(3, before.code, before.log);
        assertTrue(Files.readString(baseline.resolve("syntax-primary.tsv")).contains("syntax\tdemo/Parent.java"));

        Path output = temp.resolve("restored");
        var after = run("deobfuscate", jar.toString(), "--out", output.toString(), "--compile");
        assertEquals(0, after.code, () -> after.log + diagnostics(output.resolve("javac-primary.tsv")));
        assertTrue(after.log.contains("Applied constant branch: 1"), after.log);
        assertTrue(after.log.contains("Record layouts reconstructed in decompiler input: 1"), after.log);
        assertTrue(after.log.contains("Record bootstrap calls lowered in decompiler input: 3"), after.log);
        assertTrue(after.log.contains("Files verified by full-tree compilation: 6"), after.log);
        String mapping = Files.readString(output.resolve("source-renames.tsv"));
        assertTrue(mapping.contains("field\tdemo/Parent\tdo\tI\tfield"), mapping);
        assertTrue(mapping.contains("method\tdemo/Parent\ta\t()I\tmethod"), mapping);
        assertTrue(mapping.contains("method\tdemo/Fixture\tvalue\t()J\tmethod"), mapping);
        assertTrue(mapping.contains("class\tdemo/Fixture$Fixture\t\t\tdemo/Fixture$Type"), mapping);
        String strings = Files.readString(output.resolve("string-rewrites.tsv"));
        assertTrue(strings.contains("\tHelloX!"), strings);
        assertTrue(strings.contains("Decoder owner may run class initialization"), strings);
        Path rewritten = output.resolve("bytecode.jar");
        try (var zip = new ZipFile(rewritten.toFile())) {
            byte[] actual = DecompilerWorker.readClass(zip, "demo/Fixture.class");
            assertFalse(Arrays.equals(entries.get("demo/Fixture.class"), actual));
            var node = Bytecode.parse(actual);
            var text = node.methods.stream().filter(method -> method.name.equals("text")).findFirst().orElseThrow();
            assertTrue(Arrays.stream(text.instructions.toArray()).noneMatch(insn -> insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.owner.equals("demo/Decoder")));
            assertTrue(Bytecode.parse(DecompilerWorker.readClass(zip, "demo/Parent.class")).fields.stream()
                    .anyMatch(field -> field.name.equals("do")));
        }

        Path reconstructed = Files.createDirectory(temp.resolve("recompiled"));
        compileFixtures(reconstructed, Exports.sourceFiles(output.resolve("sources")).values().stream().toList());
        // Only the synthetic test fixture is loaded. Real archives are inspected statically.
        for (Path location : List.of(jar, rewritten, reconstructed)) {
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{location.toUri().toURL()})) {
                var fixture = loader.loadClass("demo.Fixture");
                var guard = fixture.getMethod("guarded", int.class);
                for (int input : new int[]{0, -1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
                    assertEquals(input + 2, guard.invoke(null, input));
                }
                assertEquals("HelloX!", fixture.getMethod("text").invoke(null));
                assertEquals("kept", fixture.getMethod("guardedDecoder").invoke(null));
                assertEquals(21L, fixture.getMethod("variants").invoke(null));
                var accessor = loader.loadClass("demo.Child").getMethod("inherited");
                assertTrue(accessor.trySetAccessible());
                assertEquals(41, accessor.invoke(null));
                var reference = loader.loadClass("demo.Parent").getMethod("reference");
                assertTrue(reference.trySetAccessible());
                assertEquals(41, ((java.util.function.IntSupplier) reference.invoke(null)).getAsInt());
                var constructor = loader.loadClass("demo.Pair").getDeclaredConstructor(int.class, String.class);
                assertTrue(constructor.trySetAccessible());
                Object pair = constructor.newInstance(3, "ok");
                assertEquals("Pair[number=3, text=ok]", pair.toString());
                assertEquals(3 * 31 + "ok".hashCode(), pair.hashCode());
                assertEquals(pair, constructor.newInstance(3, "ok"));
                assertNotEquals(pair, constructor.newInstance(4, "ok"));
            }
        }
    }

    private static void compileFixtures(Path output, List<Path> files) {
        var args = new ArrayList<>(List.of("-proc:none", "--release", "21", "-d", output.toString()));
        files.forEach(file -> args.add(file.toString()));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
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
