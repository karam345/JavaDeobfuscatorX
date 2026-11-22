package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
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
    static final int LIMIT = 8192;

    enum Codec {
        BASE64("Base64"), URL("Base64 URL"), MIME("Base64 MIME"), HEX("Hex");

        final String label;

        Codec(String label) {
            this.label = label;
        }

        byte[] decode(String text) {
            return switch (this) {
                case BASE64 -> Base64.getDecoder().decode(text);
                case URL -> Base64.getUrlDecoder().decode(text);
                case MIME -> Base64.getMimeDecoder().decode(text);
                case HEX -> HexFormat.of().parseHex(text);
            };
        }

        boolean decoder(MethodInsnNode call) {
            return this == HEX
                    ? matches(call, INVOKEVIRTUAL, "java/util/HexFormat", "parseHex", "(Ljava/lang/CharSequence;)[B")
                    : matches(call, INVOKEVIRTUAL, "java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B");
        }

        int minimumVersion() {
            return this == HEX ? V17 : V1_8;
        }
    }

    private record Payload(Codec codec, String text) {}

    record Decoded(String codec, String text) {}

    static Decoded evidence(MethodInsnNode instruction) {
        if (instruction.owner.equals("java/lang/String") && instruction.name.equals("<init>")) {
            var charset = instruction.getPrevious();
            if (matches(instruction, INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V")
                    && charset instanceof FieldInsnNode field && charset(field) != null
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

    record LiteralSite(MethodInsnNode constructor, MethodInsnNode factory, Payload payload, Charset charset) {
        Decoded decode() {
            return new Decoded(payload.codec.label + " to " + charset.name(), strictText(payload.codec.decode(payload.text), charset));
        }

        int minimumVersion() { return payload.codec.minimumVersion(); }

        boolean replace(InsnList code, Decoded decoded) {
            for (AbstractInsnNode insn = factory; insn != constructor; insn = insn.getNext()) {
                if (Rewrites.annotated(insn)) return false;
            }
            // Retain allocation and the constructor call so the result is still a distinct String object.
            code.insertBefore(factory, new LdcInsnNode(payload.text));
            code.insertBefore(factory, new InsnNode(POP));
            code.insertBefore(factory, characters(decoded.text()));
            while (constructor.getPrevious() != factory) code.remove(constructor.getPrevious());
            code.remove(factory);
            constructor.desc = "([C)V";
            return true;
        }
    }

    static String strictText(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new Decoder.Unsupported("Bytes are malformed for the declared charset");
        }
    }

    static InsnList characters(String text) {
        // Loading the decoded text as a String constant would change the result of a later intern().
        var code = new InsnList();
        code.add(Rewrites.push(text.length()));
        code.add(new IntInsnNode(NEWARRAY, T_CHAR));
        for (int i = 0; i < text.length(); i++) {
            code.add(new InsnNode(DUP));
            code.add(Rewrites.push(i));
            code.add(Rewrites.push((int) text.charAt(i)));
            code.add(new InsnNode(CASTORE));
        }
        return code;
    }

    static LiteralSite literalSite(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode constructor)
                || !matches(constructor, INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V")
                || !(constructor.getPrevious() instanceof FieldInsnNode encoding)
                || !(encoding.getPrevious() instanceof MethodInsnNode decoder)
                || !(decoder.getPrevious() instanceof LdcInsnNode literal)
                || !(literal.getPrevious() instanceof MethodInsnNode factory)
                || factory.getPrevious() == null || factory.getPrevious().getOpcode() != DUP
                || !(factory.getPrevious().getPrevious() instanceof TypeInsnNode allocation)
                || allocation.getOpcode() != NEW || !allocation.desc.equals("java/lang/String")) return null;
        var payload = payload(decoder);
        var charset = charset(encoding);
        return payload == null || charset == null ? null : new LiteralSite(constructor, factory, payload, charset);
    }

    static Charset charset(FieldInsnNode field) {
        if (field.getOpcode() != GETSTATIC || !field.owner.equals("java/nio/charset/StandardCharsets")
                || !field.desc.equals("Ljava/nio/charset/Charset;")) return null;
        return switch (field.name) {
            case "UTF_8" -> StandardCharsets.UTF_8;
            case "US_ASCII" -> StandardCharsets.US_ASCII;
            case "ISO_8859_1" -> StandardCharsets.ISO_8859_1;
            case "UTF_16" -> StandardCharsets.UTF_16;
            case "UTF_16LE" -> StandardCharsets.UTF_16LE;
            case "UTF_16BE" -> StandardCharsets.UTF_16BE;
            default -> null;
        };
    }

    private static Payload payload(MethodInsnNode call) {
        if (!(call.getPrevious() instanceof LdcInsnNode load) || !(load.cst instanceof String value)
                || value.length() > LIMIT
                || !(load.getPrevious() instanceof MethodInsnNode factory)) return null;
        Codec codec = codec(factory);
        return codec != null && codec.decoder(call) ? new Payload(codec, value) : null;
    }

    static Codec codec(MethodInsnNode factory) {
        if (matches(factory, INVOKESTATIC, "java/util/Base64", factory.name, "()Ljava/util/Base64$Decoder;")) {
            return switch (factory.name) {
                case "getDecoder" -> Codec.BASE64;
                case "getUrlDecoder" -> Codec.URL;
                case "getMimeDecoder" -> Codec.MIME;
                default -> null;
            };
        }
        return matches(factory, INVOKESTATIC, "java/util/HexFormat", "of", "()Ljava/util/HexFormat;") ? Codec.HEX : null;
    }

    private static Decoded decode(Payload payload, Charset charset) {
        if (payload == null || charset == null) return null;
        try {
            return new Decoded(payload.codec.label + " to " + charset.name(),
                    charset.newDecoder().decode(ByteBuffer.wrap(payload.codec.decode(payload.text))).toString());
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            // Leave malformed literals for the original runtime decoder to reject.
            return null;
        }
    }

    static boolean matches(MethodInsnNode call, int opcode, String owner, String name, String descriptor) {
        return call.getOpcode() == opcode && !call.itf && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor);
    }
}
