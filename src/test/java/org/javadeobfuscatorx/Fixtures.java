package org.javadeobfuscatorx;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Fixtures implements Opcodes {
    static byte[] constants(String name, int seed) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V21, ACC_PUBLIC | ACC_FINAL, name, null, "java/lang/Object", null);
        var first = new Label();
        var last = new Label();
        var arithmetic = method(writer, "a", "()I");
        arithmetic.visitLdcInsn(seed);
        arithmetic.visitIntInsn(SIPUSH, 732);
        arithmetic.visitInsn(IXOR);
        arithmetic.visitInsn(ICONST_1);
        arithmetic.visitInsn(IADD);
        arithmetic.visitInsn(ICONST_0);
        arithmetic.visitInsn(POP);
        arithmetic.visitJumpInsn(GOTO, first);
        arithmetic.visitLabel(first);
        arithmetic.visitInsn(ICONST_0);
        arithmetic.visitJumpInsn(IFNE, last);
        arithmetic.visitInsn(IRETURN);
        arithmetic.visitLabel(last);
        arithmetic.visitInsn(POP);
        arithmetic.visitIntInsn(BIPUSH, -9);
        arithmetic.visitInsn(IRETURN);
        end(arithmetic);

        var dispatcher = method(writer, "b", "()I");
        var zero = new Label();
        var defaultCase = new Label();
        dispatcher.visitIntInsn(BIPUSH, 4);
        dispatcher.visitLookupSwitchInsn(defaultCase, new int[]{4}, new Label[]{zero});
        dispatcher.visitLabel(defaultCase);
        dispatcher.visitInsn(ICONST_0);
        dispatcher.visitInsn(IRETURN);
        dispatcher.visitLabel(zero);
        dispatcher.visitIntInsn(BIPUSH, 8);
        dispatcher.visitInsn(IRETURN);
        end(dispatcher);

        var divisor = method(writer, "divisor", "()I");
        divisor.visitInsn(ICONST_1);
        divisor.visitInsn(ICONST_0);
        divisor.visitInsn(IDIV);
        divisor.visitInsn(IRETURN);
        end(divisor);

        var strings = method(writer, "decoded", "()[B");
        strings.visitMethodInsn(INVOKESTATIC, "java/util/Base64", "getDecoder", "()Ljava/util/Base64$Decoder;", false);
        strings.visitLdcInsn("U3ludGhldGljIGZpeHR1cmU=");
        strings.visitMethodInsn(INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B", false);
        strings.visitInsn(ARETURN);
        end(strings);
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] marker(String name, int value) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(V21, ACC_PUBLIC, name, null, "java/lang/Object", null);
        var method = method(writer, "value", "()I");
        method.visitLdcInsn(value);
        method.visitInsn(IRETURN);
        end(method);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodVisitor method(ClassWriter writer, String name, String descriptor) {
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, descriptor, null, null);
        method.visitCode();
        return method;
    }

    private static void end(MethodVisitor method) {
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    static Path jar(Path path, Map<String, byte[]> entries) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : new TreeMap<>(entries).entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }
}
