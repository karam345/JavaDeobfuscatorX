package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

import java.util.List;

final class StringConcat implements Opcodes {
    private static final String OWNER = "java/lang/invoke/StringConcatFactory";
    private static final String PARAMETERS = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;";
    private static final String RETURN = ")Ljava/lang/invoke/CallSite;";

    static boolean candidate(InvokeDynamicInsnNode call) {
        return OWNER.equals(call.bsm.getOwner()) && Type.getReturnType(call.desc).equals(StringRecovery.STRING);
    }

    static String evaluate(InvokeDynamicInsnNode call, List<?> values) {
        var handle = call.bsm;
        if (!candidate(call) || handle.getTag() != H_INVOKESTATIC || handle.isInterface()) return null;
        var types = Type.getArgumentTypes(call.desc);
        if ((Type.getArgumentsAndReturnSizes(call.desc) >> 2) - 1 > 200) return null;
        String recipe;
        if (handle.getName().equals("makeConcat") && handle.getDesc().equals(PARAMETERS + RETURN) && call.bsmArgs.length == 0) {
            recipe = "\u0001".repeat(types.length);
        } else if (handle.getName().equals("makeConcatWithConstants")
                && handle.getDesc().equals(PARAMETERS + "Ljava/lang/String;[Ljava/lang/Object;" + RETURN)
                && call.bsmArgs.length > 0 && call.bsmArgs[0] instanceof String text && text.length() <= Strings.LIMIT) {
            recipe = text;
        } else {
            return null;
        }

        var result = new StringBuilder();
        int dynamic = 0;
        int constant = 1;
        int fixedCharacters = 0;
        for (int i = 0; i < recipe.length(); i++) {
            char token = recipe.charAt(i);
            if (token == '\u0001') {
                if (dynamic == types.length) return null;
                String text = convert(types[dynamic], values.get(dynamic++));
                if (text == null || text.length() > Strings.LIMIT - result.length()) return null;
                result.append(text);
            } else if (token == '\u0002') {
                if (constant == call.bsmArgs.length) return null;
                Object value = call.bsmArgs[constant++];
                if (!(value instanceof String || value instanceof Integer || value instanceof Long)) return null;
                String text = value.toString();
                if (text.length() > Strings.LIMIT - result.length()) return null;
                fixedCharacters += text.length();
                result.append(text);
            } else {
                if (result.length() == Strings.LIMIT) return null;
                fixedCharacters++;
                result.append(token);
            }
        }
        // Require genuine concatenation. Degenerate targets may return an existing String.
        return dynamic == types.length && constant == Math.max(1, call.bsmArgs.length)
                && (dynamic >= 2 || dynamic == 1 && fixedCharacters != 0) ? result.toString() : null;
    }

    private static String convert(Type type, Object value) {
        if (value instanceof Integer number) {
            return switch (type.getSort()) {
                case Type.INT -> number.toString();
                // Keep unusual JVM int-category values for the original call to handle.
                case Type.BOOLEAN -> number == 0 ? "false" : number == 1 ? "true" : null;
                case Type.BYTE -> number == number.byteValue() ? number.toString() : null;
                case Type.SHORT -> number == number.shortValue() ? number.toString() : null;
                case Type.CHAR -> number == (char) number.intValue() ? Character.toString((char) number.intValue()) : null;
                default -> null;
            };
        }
        if (value instanceof Long number && type.equals(Type.LONG_TYPE)) return number.toString();
        if (type.equals(StringRecovery.STRING) || type.getDescriptor().equals("Ljava/lang/Object;")) {
            if (value == StringRecovery.NULL) return "null";
            if (value instanceof String text) return text;
        }
        return null;
    }
}
