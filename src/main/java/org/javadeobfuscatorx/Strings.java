package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

final class Strings implements Opcodes {
    private record Payload(String codec, byte[] bytes) {}

    record Decoded(String codec, String text) {}

    static Decoded evidence(MethodInsnNode instruction) {
        if (instruction.owner.equals("java/lang/String") && instruction.name.equals("<init>")) {
            var charset = instruction.getPrevious();
            if (charset instanceof FieldInsnNode field && charset(field) != null
                    && field.getPrevious() instanceof MethodInsnNode decoder) {
                return decode(payload(decoder), charset(field));
            }
        }
        if (instruction.getNext() instanceof FieldInsnNode field && charset(field) != null
                && field.getNext() instanceof MethodInsnNode constructor
                && matches(constructor, INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V")) {
            return null;
        }
        return decode(payload(instruction), StandardCharsets.UTF_8);
    }

    static boolean rewrite(InsnList code, AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode constructor)
                || !matches(constructor, INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V")
                || !(constructor.getPrevious() instanceof FieldInsnNode encoding)
                || !(encoding.getPrevious() instanceof MethodInsnNode decoder)
                || !(decoder.getPrevious() instanceof LdcInsnNode literal)
                || !(literal.getPrevious() instanceof MethodInsnNode factory)
                || factory.getPrevious() == null || factory.getPrevious().getOpcode() != DUP
                || !(factory.getPrevious().getPrevious() instanceof TypeInsnNode allocation)
                || allocation.getOpcode() != NEW || !allocation.desc.equals("java/lang/String")) return false;

        var value = decode(payload(decoder), charset(encoding));
        if (value == null) return false;
        for (AbstractInsnNode insn = factory; insn != constructor; insn = insn.getNext()) {
            if (insn.visibleTypeAnnotations != null || insn.invisibleTypeAnnotations != null) return false;
        }
        // Retain allocation and the constructor call so the result is still a distinct String object.
        code.insertBefore(factory, new LdcInsnNode(value.text()));
        code.remove(factory);
        code.remove(literal);
        code.remove(decoder);
        code.remove(encoding);
        constructor.desc = "(Ljava/lang/String;)V";
        return true;
    }

    private static Charset charset(FieldInsnNode field) {
        if (field.getOpcode() != GETSTATIC || !field.owner.equals("java/nio/charset/StandardCharsets")
                || !field.desc.equals("Ljava/nio/charset/Charset;")) return null;
        return switch (field.name) {
            case "UTF_8" -> StandardCharsets.UTF_8;
            case "US_ASCII" -> StandardCharsets.US_ASCII;
            case "ISO_8859_1" -> StandardCharsets.ISO_8859_1;
            case "UTF_16LE" -> StandardCharsets.UTF_16LE;
            case "UTF_16BE" -> StandardCharsets.UTF_16BE;
            default -> null;
        };
    }

    private static Payload payload(MethodInsnNode call) {
        if (!(call.getPrevious() instanceof LdcInsnNode load) || !(load.cst instanceof String value)
                || value.isEmpty() || value.length() > 8192
                || !(load.getPrevious() instanceof MethodInsnNode factory)) return null;
        try {
            if (matches(call, INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B")
                    && matches(factory, INVOKESTATIC, "java/util/Base64", factory.name, "()Ljava/util/Base64$Decoder;")) {
                return switch (factory.name) {
                    case "getDecoder" -> new Payload("Base64", Base64.getDecoder().decode(value));
                    case "getUrlDecoder" -> new Payload("Base64 URL", Base64.getUrlDecoder().decode(value));
                    case "getMimeDecoder" -> new Payload("Base64 MIME", Base64.getMimeDecoder().decode(value));
                    default -> null;
                };
            }
            if (matches(call, INVOKEVIRTUAL, "java/util/HexFormat", "parseHex", "(Ljava/lang/CharSequence;)[B")
                    && matches(factory, INVOKESTATIC, "java/util/HexFormat", "of", "()Ljava/util/HexFormat;")) {
                return new Payload("Hex", HexFormat.of().parseHex(value));
            }
        } catch (IllegalArgumentException invalid) {
            // Leave malformed literals for the original runtime decoder to reject.
        }
        return null;
    }

    private static Decoded decode(Payload payload, Charset charset) {
        if (payload == null || charset == null) return null;
        try {
            return new Decoded(payload.codec + " to " + charset.name(),
                    charset.newDecoder().decode(ByteBuffer.wrap(payload.bytes)).toString());
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }

    private static boolean matches(MethodInsnNode call, int opcode, String owner, String name, String descriptor) {
        return call.getOpcode() == opcode && !call.itf && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor);
    }
}
