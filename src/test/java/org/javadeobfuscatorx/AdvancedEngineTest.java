package org.javadeobfuscatorx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedEngineTest implements Opcodes {
    @TempDir Path temp;

    @Test
    void propagatesAcrossJoinsButKeepsConflictingInputsAndIncrementSideEffects() throws Exception {
        var writer = fixture("DataFlow");
        localMerge(writer, "equal", 40);
        localMerge(writer, "different", 26);
        var identity = method(writer, "opaque", "(I)I");
        var unexpected = new Label();
        identity.visitVarInsn(ILOAD, 0);
        identity.visitVarInsn(ILOAD, 0);
        identity.visitJumpInsn(IF_ICMPNE, unexpected);
        identity.visitIntInsn(BIPUSH, 99);
        identity.visitInsn(IRETURN);
        identity.visitLabel(unexpected);
        identity.visitInsn(ICONST_M1);
        identity.visitInsn(IRETURN);
        end(identity);

        var wide = method(writer, "wide", "()J");
        wide.visitLdcInsn(Long.MIN_VALUE);
        wide.visitVarInsn(LSTORE, 0);
        wide.visitVarInsn(LLOAD, 0);
        wide.visitInsn(LCONST_1);
        wide.visitInsn(LSUB);
        wide.visitInsn(LRETURN);
        end(wide);

        byte[] original = writer.toByteArray();
        var result = transform("DataFlow", original);
        assertNull(result.rollback(), result.rollback());
        assertEquals(1, result.changes().get("identical-operand branch"));
        assertTrue(result.changes().get("constant local load") >= 2);
        assertTrue(result.changes().get("numeric arithmetic") >= 1);
        Class<?> before = load(original);
        Class<?> after = load(result.bytes());
        for (String name : new String[]{"equal", "different"}) {
            for (boolean input : new boolean[]{true, false}) {
                assertEquals(before.getMethod(name, boolean.class).invoke(null, input),
                        after.getMethod(name, boolean.class).invoke(null, input));
            }
        }
        assertEquals(44, after.getMethod("equal", boolean.class).invoke(null, false));
        assertEquals(30, after.getMethod("different", boolean.class).invoke(null, false));
        assertEquals(99, after.getMethod("opaque", int.class).invoke(null, Integer.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, after.getMethod("wide").invoke(null));
    }

    @Test
    void eliminatesOnlyShadowedHandlersAndPreservesThrownExceptions() throws Exception {
        var writer = fixture("Handlers");
        var method = method(writer, "value", "(Ljava/lang/String;)I");
        var start = new Label();
        var end = new Label();
        var used = new Label();
        var shadowed = new Label();
        method.visitTryCatchBlock(start, end, used, "java/lang/NumberFormatException");
        method.visitTryCatchBlock(start, end, shadowed, "java/lang/NumberFormatException");
        method.visitLabel(start);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false);
        method.visitLabel(end);
        method.visitInsn(IRETURN);
        method.visitLabel(used);
        method.visitInsn(POP);
        method.visitInsn(ICONST_M1);
        method.visitInsn(IRETURN);
        method.visitLabel(shadowed);
        method.visitInsn(POP);
        method.visitInsn(ICONST_M1);
        method.visitInsn(INEG);
        method.visitInsn(IRETURN);
        end(method);
        byte[] original = writer.toByteArray();
        var result = transform("Handlers", original);
        assertNull(result.rollback(), result.rollback());
        assertEquals(1, result.changes().get("shadowed exception handler"));
        assertEquals(1, Bytecode.parse(result.bytes()).methods.getFirst().tryCatchBlocks.size());
        Class<?> transformed = load(result.bytes());
        assertEquals(-1, transformed.getMethod("value", String.class).invoke(null, "xyz"));
        assertEquals(12, transformed.getMethod("value", String.class).invoke(null, "12"));
    }

    @Test
    void reconstructsInlineStringAndKeepsDistinctObjectsAndCallableBridges() throws Exception {
        var writer = fixture("StringsFixture");
        var decoded = method(writer, "decoded", "()Ljava/lang/String;");
        decoded.visitTypeInsn(NEW, "java/lang/String");
        decoded.visitInsn(DUP);
        decoded.visitMethodInsn(INVOKESTATIC, "java/util/HexFormat", "of", "()Ljava/util/HexFormat;", false);
        decoded.visitLdcInsn("4f726967696e616c");
        decoded.visitMethodInsn(INVOKEVIRTUAL, "java/util/HexFormat", "parseHex", "(Ljava/lang/CharSequence;)[B", false);
        decoded.visitFieldInsn(GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;");
        decoded.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false);
        decoded.visitInsn(ARETURN);
        end(decoded);
        var indirect = method(writer, "indirect", "()Ljava/lang/String;");
        indirect.visitMethodInsn(INVOKESTATIC, "StringsFixture", "decoded", "()Ljava/lang/String;", false);
        indirect.visitInsn(ARETURN);
        end(indirect);

        var dynamic = method(writer, "dynamic", "(I)Ljava/lang/String;");
        dynamic.visitVarInsn(ILOAD, 0);
        var handle = new Handle(H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false);
        dynamic.visitInvokeDynamicInsn("makeConcatWithConstants", "(I)Ljava/lang/String;", handle, "value=\u0001");
        dynamic.visitInsn(ARETURN);
        end(dynamic);

        byte[] original = writer.toByteArray();
        var result = transform("StringsFixture", original);
        assertNull(result.rollback(), result.rollback());
        assertEquals(1, result.changes().get("literal string decoder"));
        for (var insn : Bytecode.parse(result.bytes()).methods.getFirst().instructions) {
            if (insn instanceof MethodInsnNode call) assertEquals("java/lang/String", call.owner);
        }
        Class<?> resultClass = load(result.bytes());
        Object first = resultClass.getMethod("decoded").invoke(null);
        Object second = resultClass.getMethod("indirect").invoke(null);
        assertEquals("Original", first);
        assertEquals(first, second);
        assertNotSame(first, second);
        assertNotSame(first, "Original");
        assertEquals("value=3", resultClass.getMethod("dynamic", int.class).invoke(null, 3));
        try (var archive = archive("StringsFixture", original)) {
            var report = new Inspection(archive, true);
            assertTrue(report.strings.stream().anyMatch(line -> line.contains("Hex to UTF-8\tOriginal")));
            assertTrue(report.wrappers.stream().anyMatch(line -> line.contains("indirect") && line.contains("decoded")));
            assertTrue(report.bootstraps.stream().anyMatch(line -> line.contains("StringConcatFactory")));
        }
    }

    @Test
    void consistentlyRenamesInvalidLocalMetadataAndPreservesMethodParameters() throws Exception {
        var writer = fixture("Locals");
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "scope", "(I)I", null, null);
        method.visitParameter("original", 0);
        method.visitCode();
        var start = new Label();
        var midpoint = new Label();
        var end = new Label();
        method.visitLabel(start);
        method.visitVarInsn(ILOAD, 0);
        method.visitVarInsn(ISTORE, 1);
        method.visitLabel(midpoint);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IRETURN);
        method.visitLabel(end);
        method.visitLocalVariable("original", "I", null, start, end, 0);
        method.visitLocalVariable("class", "I", null, midpoint, end, 1);
        end(method);
        var result = transform("Locals", writer.toByteArray());
        assertNull(result.rollback(), result.rollback());
        assertEquals(1, result.changes().get("local debug identifier"));
        var recovered = Bytecode.parse(result.bytes()).methods.getFirst();
        assertEquals("original", recovered.parameters.getFirst().name);
        assertEquals("local1", recovered.localVariables.getLast().name);
        assertEquals(7, load(result.bytes()).getMethod("scope", int.class).invoke(null, 7));
    }

    private Bytecode.Result transform(String name, byte[] data) throws Exception {
        try (var archive = archive(name, data)) {
            return Bytecode.transform(data, new Hierarchy(archive));
        }
    }

    private Archive archive(String name, byte[] data) throws Exception {
        return Archive.open(Fixtures.jar(temp.resolve(name + ".jar"), Map.of(name + ".class", data)),
                Archive.Limits.defaults(), 21);
    }

    private static ClassWriter fixture(String name) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        return writer;
    }

    private static MethodVisitor method(ClassWriter writer, String name, String descriptor) {
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        return method;
    }

    private static void localMerge(ClassWriter writer, String name, int otherwise) {
        var method = method(writer, name, "(Z)I");
        var alternative = new Label();
        var join = new Label();
        method.visitVarInsn(ILOAD, 0);
        method.visitJumpInsn(IFEQ, alternative);
        method.visitIntInsn(BIPUSH, 40);
        method.visitVarInsn(ISTORE, 1);
        method.visitJumpInsn(GOTO, join);
        method.visitLabel(alternative);
        method.visitIntInsn(BIPUSH, otherwise);
        method.visitVarInsn(ISTORE, 1);
        method.visitLabel(join);
        method.visitInsn(ICONST_2);
        method.visitIincInsn(1, 2);
        method.visitVarInsn(ILOAD, 1);
        method.visitInsn(IADD);
        method.visitInsn(IRETURN);
        end(method);
    }

    private static void end(MethodVisitor method) {
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static Class<?> load(byte[] fixture) {
        return new ClassLoader() {
            Class<?> fixture() {
                return defineClass(null, fixture, 0, fixture.length);
            }
        }.fixture();
    }
}
