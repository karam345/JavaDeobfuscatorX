package org.javadeobfuscatorx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest implements Opcodes {
    @TempDir Path temp;

    @Test
    void transformsAndDifferentiallyVerifiesOriginalFixtures() throws Exception {
        Path input = Fixtures.jar(temp.resolve("fixture.jar"), Map.of("sample/a.class", Fixtures.constants("sample/a", 19)));
        try (var archive = Archive.open(input, Archive.Limits.defaults(), 21)) {
            var random = new Random(88423);
            for (int iteration = 0; iteration < 100; iteration++) {
                int number = random.nextInt();
                byte[] fixture = Fixtures.constants("sample/a", number);
                var result = Bytecode.transform(fixture, new Hierarchy(archive));
                assertNull(result.rollback(), result.rollback());
                assertEquals(2, result.changes().get("integer arithmetic"));
                assertEquals(1, result.changes().get("constant switch"));
                assertEquals(1, result.changes().get("constant branch"));
                assertEquals(1, result.changes().get("junk stack operation"));
                assertTrue(result.changes().get("unreachable instruction") > 0);
                Bytecode.verify(result.bytes());
                Class<?> before = loadFixture(fixture);
                Class<?> after = loadFixture(result.bytes());
                assertEquals(before.getMethod("a").invoke(null), after.getMethod("a").invoke(null));
                assertEquals(8, after.getMethod("b").invoke(null));
                assertArrayEquals((byte[]) before.getMethod("decoded").invoke(null),
                        (byte[]) after.getMethod("decoded").invoke(null));
                var thrown = assertThrows(InvocationTargetException.class, () -> after.getMethod("divisor").invoke(null));
                assertInstanceOf(ArithmeticException.class, thrown.getCause());
            }
        }
    }

    @Test
    void retainsOriginalClassIfStaticVerificationFails() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(V21, ACC_PUBLIC, "Invalid", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        method.visitInsn(ACONST_NULL);
        method.visitInsn(IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        byte[] original = writer.toByteArray();
        Path input = Fixtures.jar(temp.resolve("invalid.jar"), Map.of("Invalid.class", original));
        try (var archive = Archive.open(input, Archive.Limits.defaults(), 21)) {
            var result = Bytecode.transform(original, new Hierarchy(archive));
            assertSame(original, result.bytes());
            assertTrue(result.changes().isEmpty());
            assertNotNull(result.rollback());
        }
    }

    @Test
    void rollbackWhenRecomputedFramesNeedMissingClassHierarchy() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(V21, ACC_PUBLIC, "Merge", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "(Z)[Ljava/lang/Object;", null, null);
        method.visitCode();
        var branch = new Label();
        var right = new Label();
        var merged = new Label();
        method.visitInsn(ICONST_1);
        method.visitJumpInsn(IFNE, branch);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ARETURN);
        method.visitLabel(branch);
        method.visitFrame(F_SAME, 0, null, 0, null);
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, right);
        method.visitInsn(ICONST_0);
        method.visitTypeInsn(ANEWARRAY, "missing/Left");
        method.visitJumpInsn(GOTO, merged);
        method.visitLabel(right);
        method.visitFrame(F_SAME, 0, null, 0, null);
        method.visitInsn(ICONST_0);
        method.visitTypeInsn(ANEWARRAY, "missing/Right");
        method.visitLabel(merged);
        method.visitFrame(F_SAME1, 0, null, 1, new Object[]{"[Ljava/lang/Object;"});
        method.visitInsn(ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        byte[] original = writer.toByteArray();
        try (var archive = Archive.open(Fixtures.jar(temp.resolve("merge.jar"), Map.of("Merge.class", original)),
                Archive.Limits.defaults(), 21)) {
            var result = Bytecode.transform(original, new Hierarchy(archive));
            assertSame(original, result.bytes());
            assertTrue(result.rollback().contains("Missing hierarchy"), result.rollback());
        }
    }

    @Test
    void selectsManifestEnabledReleaseAndInspectsMinecraft() throws Exception {
        String plugin = "name: Fixture\nmain: sample.a\nfolia-supported: true\ncommands:\n  demo: {}\n";
        var jar = Fixtures.jar(temp.resolve("multi.jar"), Map.of(
                "META-INF/MANIFEST.MF", utf8("Manifest-Version: 1.0\r\nMulti-Release: true\r\nX-Obfuscator: ProGuard\r\n\r\n"),
                "sample/a.class", Fixtures.constants("sample/a", 1),
                "META-INF/versions/21/sample/a.class", Fixtures.constants("sample/a", 2),
                "plugin.yml", utf8(plugin)));
        try (var base = Archive.open(jar, Archive.Limits.defaults(), 17);
             var modern = Archive.open(jar, Archive.Limits.defaults(), 21)) {
            assertEquals(0, base.selected.get("sample/a").release());
            assertEquals(21, modern.selected.get("sample/a").release());
            var inspection = new Inspection(modern, true);
            assertEquals(1, inspection.parsed);
            assertEquals(1, inspection.verified);
            assertTrue(inspection.attribution.getFirst().contains("self-reported"));
            assertTrue(inspection.strings.getFirst().contains("Synthetic fixture"));
            assertTrue(inspection.minecraft.metadata.contains("entrypoint\tsample/a\tpresent"));
            assertTrue(inspection.minecraft.metadata.contains("plugin.yml\tcommands\tdemo"));
            assertTrue(inspection.minecraft.signals.contains("Plugin API class"));
        }
    }

    @Test
    void rejectsTraversalDuplicatesCorruptionAndExpansion() throws Exception {
        for (String path : new String[]{"../escape", "/absolute", "a\\b", "C:/file", "aux.txt",
                "foo/../escape", "foo//escape", "foo\u001b/bar"}) {
            Files.deleteIfExists(temp.resolve("unsafe.jar"));
            Path file = Fixtures.jar(temp.resolve("unsafe.jar"), Map.of(path, utf8("x")));
            assertThrows(IOException.class, () -> Archive.open(file, Archive.Limits.defaults(), 21), path);
        }
        Path duplicate = Fixtures.jar(temp.resolve("duplicate.jar"), Map.of("test.txt", utf8("one"), "TEST.txt", utf8("two")));
        assertThrows(IOException.class, () -> Archive.open(duplicate, Archive.Limits.defaults(), 21));
        Path bomb = Fixtures.jar(temp.resolve("bomb.jar"), Map.of("big", new byte[65_536]));
        assertThrows(IOException.class, () -> Archive.open(bomb, new Archive.Limits(10, 200, 400, 20_000), 21));
        Path corrupt = Fixtures.jar(temp.resolve("corrupt.jar"), Map.of("file", utf8("check me")));
        byte[] bytes = Files.readAllBytes(corrupt);
        Files.write(corrupt, Arrays.copyOf(bytes, bytes.length - 8));
        assertThrows(IOException.class, () -> Archive.open(corrupt, Archive.Limits.defaults(), 21));
    }

    @Test
    void fuzzesArchivesAndClassReadersWithReproducibleInputs() throws Exception {
        var random = new Random(337170);
        for (int trial = 0; trial < 100; trial++) {
            byte[] bytes = new byte[random.nextInt(1024)];
            random.nextBytes(bytes);
            Path raw = temp.resolve("fuzz.zip");
            Files.write(raw, bytes);
            assertThrows(IOException.class, () -> Archive.open(raw, Archive.Limits.defaults(), 21));
            Files.delete(raw);
            Fixtures.jar(raw, Map.of("A.class", bytes));
            try (var archive = Archive.open(raw, Archive.Limits.defaults(), 21)) {
                assertEquals(1, archive.problems.size());
                assertTrue(archive.selected.isEmpty());
            }
        }
    }

    @Test
    void reportsInvalidClassBodyWithoutExecutingInput() throws Exception {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, "Initializer", null, "java/lang/Object", null);
        var initializer = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitIntInsn(BIPUSH, 99);
        initializer.visitMethodInsn(INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
        initializer.visitInsn(RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        writer.visitEnd();
        var file = Fixtures.jar(temp.resolve("input.jar"), Map.of("Initializer.class", writer.toByteArray(),
                "Broken.class", utf8("broken")));
        try (var archive = Archive.open(file, Archive.Limits.defaults(), 21)) {
            var result = new Inspection(archive, true);
            assertEquals(1, result.verified);
            assertEquals(1, result.problems.size());
        }
    }

    private static Class<?> loadFixture(byte[] bytes) {
        return new ClassLoader() {
            Class<?> loadFixture() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.loadFixture();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
