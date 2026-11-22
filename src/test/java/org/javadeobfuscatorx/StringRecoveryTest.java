package org.javadeobfuscatorx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StringRecoveryTest implements Opcodes {
    @TempDir Path temp;

    @Test
    void replacesProvenDecoderCallsAndConcatWhilePreservingStringsAndExceptions() throws Exception {
        byte[] fixture = compile("21", """
                import java.nio.charset.StandardCharsets;
                import java.util.Base64;
                public class Sample {
                    private static int changes;
                    private static String xor(String input, int key) {
                        char[] chars = input.toCharArray();
                        for (int i = 0; i < chars.length; i++) chars[i] = (char) (chars[i] ^ key);
                        return new String(chars);
                    }
                    private static String base(String input) {
                        return new String(Base64.getDecoder().decode(input), StandardCharsets.UTF_8);
                    }
                    private static String table(int index) {
                        int[] offsets = {Integer.MIN_VALUE, 100, 401};
                        char[] chars = {(char) (offsets[index] - 1), (char) (offsets[index + 1] / 2 + 16)};
                        return new String(chars);
                    }
                    private static String layered(String input) { return xor(base(input), 1); }
                    private static String stateful(String input) { changes++; return input; }
                    private static String identity(String input) { return input; }
                    private static String tooMuch(int x) {
                        for (int i = 0; i < 100_000; i++) x ^= i;
                        return new String(new char[]{(char) x});
                    }
                    private static String constant(int x) { return x == 7 ? "interned text" : "wrong"; }
                    public static String recovered() { return layered("YWMi"); }
                    public static String unicode() { return base("%s"); }
                    public static String replacementChar() { return base("/w=="); }
                    public static String empty() { return base(""); }
                    public static String lookup() { return table(0); }
                    public static String literal(boolean b) { int x = b ? 7 : 7; return constant(x); }
                    public static String concat() { String s = recoveredInternal(); int n = 23; return "[" + s + n + "]"; }
                    private static String recoveredInternal() { return layered("YWMi"); }
                    public static String malformed() { return base("%%invalid"); }
                    public static String state() { return stateful("unchanged"); }
                    public static String borrowed() { return identity("same reference"); }
                    public static String overBudget() { return tooMuch(9); }
                    public static String unknown(String s) { return xor(s, 1); }
                    public static int changes() { return changes; }
                }
                """.formatted(Base64.getEncoder().encodeToString("Grüße, 東京 😀".getBytes(StandardCharsets.UTF_8))));
        var result = rewrite(fixture);
        assertNull(result.rollback(), result.rollback());
        assertTrue(result.changes().getOrDefault("static string decoder", 0) >= 7, result.changes().toString());
        assertEquals(1, result.changes().get("constant string concatenation"));
        assertEquals(result.strings().detected(), result.strings().replaced() + result.strings().skipped() + result.strings().failed());
        assertTrue(result.strings().decoded() > result.strings().replaced());
        assertEquals(1, result.strings().failed());
        assertTrue(result.stringSites().stream().anyMatch(row -> row.contains("state\t") && row.contains("skipped")));
        assertTrue(result.stringSites().stream().anyMatch(row -> row.contains("overBudget\t") && row.contains("budget")));
        assertTrue(result.stringSites().stream().anyMatch(row -> row.contains("replacementChar\t") && row.contains("skipped")));

        var rewritten = Bytecode.parse(result.bytes());
        assertFalse(rewritten.methods.stream().filter(method -> method.name.equals("concat"))
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray())).anyMatch(InvokeDynamicInsnNode.class::isInstance));
        assertTrue(rewritten.methods.stream().filter(method -> method.name.equals("recovered"))
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .noneMatch(insn -> insn instanceof MethodInsnNode call && call.owner.equals("Sample")));
        Class<?> before = load(fixture);
        Class<?> after = load(result.bytes());
        for (String name : new String[]{"recovered", "unicode", "replacementChar", "empty", "lookup", "concat", "borrowed", "state"}) {
            assertEquals(before.getMethod(name).invoke(null), after.getMethod(name).invoke(null), name);
        }
        assertEquals("`b#", after.getMethod("recovered").invoke(null));
        assertEquals("\ufffd", after.getMethod("replacementChar").invoke(null));
        assertEquals("\uffffB", after.getMethod("lookup").invoke(null));
        assertEquals("[`b#23]", after.getMethod("concat").invoke(null));
        assertSame("interned text", after.getMethod("literal", boolean.class).invoke(null, false));
        assertNotSame(after.getMethod("unicode").invoke(null), after.getMethod("unicode").invoke(null));
        assertEquals(1, after.getMethod("changes").invoke(null));
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(InvocationTargetException.class, () -> after.getMethod("malformed").invoke(null)).getCause());
    }

    @Test
    void reconstructsFreshStringsInJava8WithoutInterningDecodedText() throws Exception {
        String expected = UUID.randomUUID().toString();
        String encoded = Base64.getEncoder().encodeToString(expected.getBytes(StandardCharsets.UTF_8));
        byte[] fixture = compile("8", """
                import java.nio.charset.StandardCharsets;
                import java.util.Base64;
                public class Sample {
                    public static String value() {
                        return new String(Base64.getDecoder().decode("%s"), StandardCharsets.UTF_8);
                    }
                }
                """.formatted(encoded));
        var result = rewrite(fixture);
        assertNull(result.rollback(), result.rollback());
        assertEquals(1, result.strings().replaced());
        String actual = (String) load(result.bytes()).getMethod("value").invoke(null);
        assertEquals(expected, actual);
        assertSame(actual, actual.intern());
    }

    @Test
    void reconstructsJava8StringBuildersWithRuntimeSpecificEmptyIdentity() throws Exception {
        byte[] fixture = compile("8", """
                public class Sample {
                    private static String concat(String value, int number, long wide, char separator, boolean flag) {
                        return value + separator + number + ':' + wide + ':' + flag;
                    }
                    private static String reverse(String value) {
                        StringBuilder builder = new StringBuilder(value);
                        String snapshot = builder.reverse().append(new char[] {'!', '!'}).toString();
                        builder.append('X');
                        return snapshot;
                    }
                    private static String empty(int capacity) { return new StringBuilder(capacity).toString(); }
                    public static String value() { return concat(null, -12, Long.MIN_VALUE, '/', true); }
                    public static String unicode() { return reverse("A😀Ω"); }
                    public static String blank() { return empty(8); }
                    public static String badCapacity() { return empty(-1); }
                }
                """);
        var result = rewrite(fixture);
        assertNull(result.rollback(), result.rollback());
        assertEquals(3, result.changes().get("static string decoder"));
        assertEquals(1, result.strings().failed());
        var rewritten = Bytecode.parse(result.bytes());
        for (String name : new String[]{"value", "unicode", "blank"}) {
            var method = rewritten.methods.stream().filter(item -> item.name.equals(name)).findFirst().orElseThrow();
            assertFalse(java.util.Arrays.stream(method.instructions.toArray()).anyMatch(insn ->
                    insn instanceof MethodInsnNode call && call.owner.equals("Sample")), name);
        }
        Class<?> before = load(fixture);
        Class<?> after = load(result.bytes());
        assertEquals("null/-12:-9223372036854775808:true", after.getMethod("value").invoke(null));
        assertEquals("Ω😀A!!", after.getMethod("unicode").invoke(null));
        Object originalEmpty = before.getMethod("blank").invoke(null);
        Object rewrittenEmpty = after.getMethod("blank").invoke(null);
        assertEquals(originalEmpty, rewrittenEmpty);
        assertEquals(originalEmpty == "", rewrittenEmpty == "");
        assertInstanceOf(NegativeArraySizeException.class,
                assertThrows(InvocationTargetException.class, () -> after.getMethod("badCapacity").invoke(null)).getCause());
    }

    @Test
    void reportsRolledBackStringSitesWithoutCountingReplacements() throws Exception {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V1_8, ACC_PUBLIC, "Sample", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
        method.visitCode();
        for (int i = 0; i < 64_700; i++) method.visitInsn(NOP);
        method.visitTypeInsn(NEW, "java/lang/String");
        method.visitInsn(DUP);
        method.visitMethodInsn(INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false);
        method.visitLdcInsn(Base64.getEncoder().encodeToString(new byte[512]));
        method.visitMethodInsn(INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false);
        method.visitFieldInsn(GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;");
        method.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        byte[] input = writer.toByteArray();
        var result = rewrite(input);
        assertArrayEquals(input, result.bytes());
        assertTrue(result.rollback().contains("MethodTooLarge"), result.rollback());
        assertEquals(new StringRecovery.Counts(1, 1, 0, 0, 1), result.strings());
        assertTrue(result.stringSites().getFirst().contains("rolled back"));
    }

    @Test
    void followsSameClassConstantFieldsAndKeepsAnInitializerOverride() throws Exception {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V21, ACC_PUBLIC, "Sample", null, "java/lang/Object", null);
        int flags = ACC_PRIVATE | ACC_STATIC | ACC_FINAL;
        String string = "Ljava/lang/String;";
        String returnsString = "()" + string;
        writer.visitField(flags, "KEY", "I", null, 7).visitEnd();
        writer.visitField(flags, "TEXT", string, null, "proven text").visitEnd();
        writer.visitField(flags, "OVERRIDDEN", string, null, "wrong").visitEnd();
        var clinit = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("later value");
        clinit.visitFieldInsn(PUTSTATIC, "Sample", "OVERRIDDEN", string);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        var select = writer.visitMethod(ACC_PRIVATE | ACC_STATIC, "select", "(I)" + string, null, null);
        select.visitCode();
        var otherwise = new Label();
        select.visitVarInsn(ILOAD, 0);
        select.visitFieldInsn(GETSTATIC, "Sample", "KEY", "I");
        select.visitJumpInsn(IF_ICMPNE, otherwise);
        select.visitFieldInsn(GETSTATIC, "Sample", "TEXT", string);
        select.visitInsn(ARETURN);
        select.visitLabel(otherwise);
        select.visitFieldInsn(GETSTATIC, "Sample", "OVERRIDDEN", string);
        select.visitInsn(ARETURN);
        select.visitMaxs(0, 0);
        select.visitEnd();
        for (int key : new int[]{7, 0}) {
            var call = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "value" + key, returnsString, null, null);
            call.visitCode();
            call.visitIntInsn(BIPUSH, key);
            call.visitMethodInsn(INVOKESTATIC, "Sample", "select", "(I)" + string, false);
            call.visitInsn(ARETURN);
            call.visitMaxs(0, 0);
            call.visitEnd();
        }
        byte[] bytes = writer.toByteArray();
        var result = rewrite(bytes);
        assertNull(result.rollback(), result.rollback());
        assertEquals(new StringRecovery.Counts(2, 1, 1, 1, 0), result.strings());
        Class<?> loaded = load(result.bytes());
        assertSame("proven text", loaded.getMethod("value7").invoke(null));
        assertSame("later value", loaded.getMethod("value0").invoke(null));
    }

    @Test
    void recoversAnArgumentExposedByVerifiedControlFlowSimplification() throws Exception {
        byte[] fixture = compile("21", """
                public class Sample {
                    private static String select(int value) { return value == 7 ? "reachable" : "decoy"; }
                    public static String value() {
                        int predicate = 0;
                        int argument;
                        if (predicate == 0) argument = 7;
                        else argument = 5;
                        return select(argument);
                    }
                }
                """);
        var result = rewrite(fixture);
        assertNull(result.rollback(), result.rollback());
        assertTrue(result.changes().containsKey("constant branch"));
        assertEquals(1, result.strings().replaced());
        assertSame("reachable", load(result.bytes()).getMethod("value").invoke(null));
    }

    private byte[] compile(String release, String text) throws Exception {
        Path source = temp.resolve("Sample.java");
        Files.writeString(source, text);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", release,
                "-d", temp.toString(), source.toString()));
        return Files.readAllBytes(temp.resolve("Sample.class"));
    }

    private Bytecode.Result rewrite(byte[] fixture) throws Exception {
        var path = Fixtures.jar(temp.resolve("string fixture.jar"), Map.of("Sample.class", fixture));
        try (var archive = Archive.open(path, Archive.Limits.defaults(), 21)) {
            return Bytecode.transform(fixture, new Hierarchy(archive));
        }
    }

    private static Class<?> load(byte[] bytes) {
        return new ClassLoader() {
            Class<?> fixture() { return defineClass(null, bytes, 0, bytes.length); }
        }.fixture();
    }
}
