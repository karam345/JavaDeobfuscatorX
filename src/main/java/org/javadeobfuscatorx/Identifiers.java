package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import javax.lang.model.SourceVersion;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.IRETURN;

final class Identifiers {
    private static final Set<String> RESTRICTED = Set.of("_", "var", "yield", "record", "sealed", "permits");

    static boolean sourceName(String name) {
        return name != null && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name)
                && !RESTRICTED.contains(name) && name.codePoints().noneMatch(Character::isIdentifierIgnorable);
    }

    static String getterName(ClassNode owner, MethodNode method, FieldInsnNode field) {
        if ((method.access & ACC_PRIVATE) == 0 || !field.owner.equals(owner.name)
                || field.name.codePointCount(0, field.name.length()) <= 2 || !sourceName(field.name)) return null;
        var code = java.util.Arrays.stream(method.instructions.toArray()).filter(insn -> insn.getOpcode() >= 0).toList();
        if (code.getLast().getOpcode() != Type.getType(field.desc).getOpcode(IRETURN) || code.get(code.size() - 2) != field) return null;
        if (field.getOpcode() == GETFIELD && (!(code.getFirst() instanceof VarInsnNode load)
                || load.getOpcode() != ALOAD || load.var != 0 || (method.access & ACC_STATIC) != 0)) return null;
        int first = field.name.codePointAt(0);
        return (field.desc.equals("Z") ? "is" : "get") + new String(Character.toChars(Character.toUpperCase(first)))
                + field.name.substring(Character.charCount(first));
    }

    static int normalizeLocals(MethodNode method) {
        if (method.localVariables == null || method.localVariables.isEmpty()) return 0;
        int argumentSlots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) argumentSlots += argument.getSize();
        var reserved = new HashSet<String>();
        for (var local : method.localVariables) reserved.add(local.name);
        if (method.parameters != null) {
            for (var parameter : method.parameters) reserved.add(parameter.name);
        }

        record Symbol(int slot, String name) {}
        Map<Symbol, String> names = new HashMap<>();
        int changed = 0;
        for (var local : method.localVariables) {
            if (local.index < argumentSlots || sourceName(local.name)) continue;
            var symbol = new Symbol(local.index, local.name);
            if (!names.containsKey(symbol)) {
                String base = "local" + local.index;
                String replacement = base;
                for (int suffix = 2; !reserved.add(replacement); suffix++) replacement = base + "_" + suffix;
                names.put(symbol, replacement);
            }
            local.name = names.get(symbol);
            changed++;
        }
        return changed;
    }
}
