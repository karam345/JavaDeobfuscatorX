package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;

import java.util.Map;
import java.util.TreeMap;

final class Rewrites implements Opcodes {
    static Map<String, Integer> apply(MethodNode method, int major) {
        var counts = new TreeMap<String, Integer>();
        var code = method.instructions;
        boolean flow = major >= 51 && method.tryCatchBlocks.isEmpty();
        for (int pass = 0; pass < 8; pass++) {
            int before = code.size();
            for (var insn = code.getFirst(); insn != null; ) {
                var next = insn.getNext();
                if (insn.visibleTypeAnnotations == null && insn.invisibleTypeAnnotations == null) {
                    String change = simplify(code, insn, flow);
                    if (change != null) counts.merge(change, 1, Integer::sum);
                }
                insn = next;
            }
            if (code.size() == before) break;
        }
        return counts;
    }

    private static String simplify(InsnList code, AbstractInsnNode insn, boolean flow) {
        int opcode = insn.getOpcode();
        var operand = insn.getPrevious();
        Integer right = integer(operand);
        if (flow && insn instanceof JumpInsnNode jump && opcode == GOTO && fallsThrough(insn, jump.label)) {
            code.set(insn, new InsnNode(NOP));
            return "redundant jump";
        }
        if (right == null) return null;
        if (opcode == POP) {
            code.set(insn, new InsnNode(NOP));
            code.remove(operand);
            return "junk stack operation";
        }

        var lhs = operand.getPrevious();
        Integer left = integer(lhs);
        Integer folded = unary(opcode, right);
        if (folded != null) {
            code.set(insn, push(folded));
            code.remove(operand);
            return "integer arithmetic";
        }
        folded = left == null ? null : binary(opcode, left, right);
        if (folded != null) {
            code.set(insn, push(folded));
            code.remove(operand);
            code.remove(lhs);
            return "integer arithmetic";
        }

        if (!flow) return null;
        if (insn instanceof JumpInsnNode jump) {
            boolean pair = opcode >= IF_ICMPEQ && opcode <= IF_ICMPLE;
            boolean unary = opcode >= IFEQ && opcode <= IFLE;
            if (unary || (pair && left != null)) {
                int comparison = unary ? Integer.compare(right, 0) : Integer.compare(left, right);
                int condition = unary ? opcode - IFEQ : opcode - IF_ICMPEQ;
                code.set(insn, matches(comparison, condition) ? new JumpInsnNode(GOTO, jump.label) : new InsnNode(NOP));
                code.remove(operand);
                if (pair) code.remove(lhs);
                return "constant branch";
            }
        }

        LabelNode target = switchTarget(insn, right);
        if (target != null) {
            code.set(insn, new JumpInsnNode(GOTO, target));
            code.remove(operand);
            return "constant switch";
        }
        return null;
    }

    private static boolean fallsThrough(AbstractInsnNode jump, LabelNode target) {
        for (var cursor = jump.getNext(); cursor != null && cursor.getOpcode() == -1; cursor = cursor.getNext()) {
            if (cursor == target) return true;
        }
        return false;
    }

    private static LabelNode switchTarget(AbstractInsnNode insn, int key) {
        if (insn instanceof TableSwitchInsnNode table) {
            return key >= table.min && key <= table.max ? table.labels.get(key - table.min) : table.dflt;
        }
        if (insn instanceof LookupSwitchInsnNode lookup) {
            int index = java.util.Collections.binarySearch(lookup.keys, key);
            return index >= 0 ? lookup.labels.get(index) : lookup.dflt;
        }
        return null;
    }

    private static boolean matches(int compare, int condition) {
        return switch (condition) {
            case 0 -> compare == 0;
            case 1 -> compare != 0;
            case 2 -> compare < 0;
            case 3 -> compare >= 0;
            case 4 -> compare > 0;
            case 5 -> compare <= 0;
            default -> throw new IllegalArgumentException("Unknown integer condition");
        };
    }

    static Integer integer(AbstractInsnNode insn) {
        if (insn == null || insn.visibleTypeAnnotations != null || insn.invisibleTypeAnnotations != null) return null;
        int opcode = insn.getOpcode();
        if (opcode >= ICONST_M1 && opcode <= ICONST_5) return opcode - ICONST_0;
        if (opcode == BIPUSH || opcode == SIPUSH) return ((IntInsnNode) insn).operand;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }

    private static AbstractInsnNode push(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }

    private static Integer unary(int opcode, int value) {
        return switch (opcode) {
            case INEG -> -value;
            case I2B -> (int) (byte) value;
            case I2C -> (int) (char) value;
            case I2S -> (int) (short) value;
            default -> null;
        };
    }

    private static Integer binary(int opcode, int left, int right) {
        return switch (opcode) {
            case IADD -> left + right;
            case ISUB -> left - right;
            case IMUL -> left * right;
            case IDIV -> right == 0 ? null : left / right;
            case IREM -> right == 0 ? null : left % right;
            case ISHL -> left << right;
            case ISHR -> left >> right;
            case IUSHR -> left >>> right;
            case IAND -> left & right;
            case IOR -> left | right;
            case IXOR -> left ^ right;
            default -> null;
        };
    }

    static int prune(String owner, MethodNode method) throws AnalyzerException {
        var frames = Bytecode.frames(owner, method);
        var original = method.instructions.toArray();
        int removed = 0;
        for (int i = 0; i < original.length; i++) {
            if (frames[i] == null && original[i].getOpcode() >= 0) {
                method.instructions.remove(original[i]);
                removed++;
            }
        }
        return removed;
    }
}
