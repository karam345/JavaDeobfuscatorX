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
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

final class Rewrites implements Opcodes {
    static Map<String, Integer> apply(String owner, MethodNode method, boolean flow, StringRecovery strings) throws AnalyzerException {
        var counts = new TreeMap<String, Integer>();
        var code = method.instructions;
        if (code.size() == 0) return counts;
        var initial = code.toArray();
        if (strings != null && !strings.hasCandidate(initial)) return counts;
        for (var insn : initial) {
            if (insn.getOpcode() == JSR || insn.getOpcode() == RET) return counts;
        }
        Bytecode.checkBudget(method);
        if (strings != null) strings.startMethod(method);
        Frame<ConstantFlow.Fact>[] frames = new Analyzer<>(new ConstantFlow(strings)).analyze(owner, method);

        // Apply facts against the original graph. Only remove adjacent producers if they are still literal.
        boolean reachabilityChanged = false;
        for (int i = 0; i < initial.length; i++) {
            var insn = initial[i];
            var frame = frames[i];
            if (insn.getOpcode() < 0) continue;
            if (frame == null) reachabilityChanged = true;
            String change;
            if (strings != null) change = strings.apply(method, insn, frame, i);
            else change = frame == null || annotated(insn) ? null : simplify(code, insn, frame, flow);
            if (change != null) {
                counts.merge(change, 1, Integer::sum);
                reachabilityChanged |= insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode;
            }
        }
        if (strings != null) return counts;
        int handlers = flow ? removeShadowedHandlers(method) : 0;
        if (handlers != 0) counts.put("shadowed exception handler", handlers);
        if (flow) normalize(owner, method, counts, reachabilityChanged || handlers != 0);
        int names = Identifiers.normalizeLocals(method);
        if (names != 0) counts.put("local debug identifier", names);
        return counts;
    }

    private static String simplify(InsnList code, AbstractInsnNode insn, Frame<ConstantFlow.Fact> frame, boolean flow) {
        int opcode = insn.getOpcode();
        if (flow && insn instanceof JumpInsnNode jump && opcode == GOTO
                && executable(jump.label) instanceof VarInsnNode load && load.getOpcode() == ILOAD
                && frame.getLocal(load.var).number() instanceof Integer key) {
            var dispatch = executable(load.getNext());
            LabelNode target = switchTarget(dispatch, key);
            if (target != null && executable(target) != load) {
                jump.label = target;
                return "constant dispatcher jump";
            }
        }
        if (insn instanceof VarInsnNode local && (opcode == ILOAD || opcode == LLOAD)) {
            Number value = frame.getLocal(local.var).number();
            if (value != null) {
                code.set(insn, push(value));
                return "constant local load";
            }
            return null;
        }

        var right = stack(frame, 0);
        var left = stack(frame, 1);
        if (right == null) return null;
        if (opcode == POP && integer(insn.getPrevious()) != null) {
            var previous = insn.getPrevious();
            code.set(insn, new InsnNode(NOP));
            code.remove(previous);
            return "junk stack operation";
        }

        String identity = simplifyIdenticalLoads(code, insn, flow);
        if (identity != null) return identity;
        Number unary = opcode == IINC ? null : ConstantFlow.unary(insn, right.number());
        if (unary != null) {
            replace(code, insn, push(unary), right);
            return unary instanceof Integer && right.number() instanceof Integer ? "integer arithmetic" : "numeric arithmetic";
        }
        if (left != null) {
            Number binary = ConstantFlow.binary(opcode, left.number(), right.number());
            if (binary != null) {
                replace(code, insn, push(binary), right, left);
                return right.number() instanceof Integer && left.number() instanceof Integer
                        ? "integer arithmetic" : "numeric arithmetic";
            }
        }

        if (!flow) return null;
        if (insn instanceof JumpInsnNode jump) {
            boolean sameTarget = opcode != GOTO && executable(jump.getNext()) == executable(jump.label);
            Boolean taken = branch(opcode, left, right);
            if (taken != null || sameTarget) {
                var replacement = Boolean.TRUE.equals(taken) && !sameTarget
                        ? new JumpInsnNode(GOTO, jump.label) : new InsnNode(NOP);
                if (opcode >= IF_ICMPEQ && opcode <= IF_ACMPNE) replace(code, insn, replacement, right, left);
                else replace(code, insn, replacement, right);
                return sameTarget ? "redundant branch" : "constant branch";
            }
        }
        LabelNode target = switchTarget(insn, right.number() instanceof Integer key ? key : null);
        if (target != null) {
            replace(code, insn, new JumpInsnNode(GOTO, target), right);
            return "constant switch";
        }
        return null;
    }

    private static String simplifyIdenticalLoads(InsnList code, AbstractInsnNode insn, boolean flow) {
        if (!(insn.getPrevious() instanceof VarInsnNode right) || !(right.getPrevious() instanceof VarInsnNode left)
                || left.var != right.var || left.getOpcode() != right.getOpcode() || annotated(left) || annotated(right)) return null;
        int opcode = insn.getOpcode();
        AbstractInsnNode replacement;
        String name;
        if (right.getOpcode() == ILOAD && (opcode == IXOR || opcode == ISUB)
                || right.getOpcode() == LLOAD && opcode == LCMP) {
            replacement = new InsnNode(ICONST_0);
            name = "identical-operand arithmetic";
        } else if (right.getOpcode() == LLOAD && (opcode == LXOR || opcode == LSUB)) {
            replacement = new InsnNode(LCONST_0);
            name = "identical-operand arithmetic";
        } else if (flow && insn instanceof JumpInsnNode jump && (right.getOpcode() == ILOAD
                && opcode >= IF_ICMPEQ && opcode <= IF_ICMPLE || right.getOpcode() == ALOAD
                && (opcode == IF_ACMPEQ || opcode == IF_ACMPNE))) {
            boolean taken = opcode == IF_ACMPEQ || opcode != IF_ACMPNE && matches(0, opcode - IF_ICMPEQ);
            replacement = taken ? new JumpInsnNode(GOTO, jump.label) : new InsnNode(NOP);
            name = "identical-operand branch";
        } else {
            return null;
        }
        code.remove(right);
        code.remove(left);
        code.set(insn, replacement);
        return name;
    }

    private static ConstantFlow.Fact stack(Frame<ConstantFlow.Fact> frame, int fromTop) {
        int index = frame.getStackSize() - fromTop - 1;
        return index < 0 ? null : frame.getStack(index);
    }

    private static void replace(InsnList code, AbstractInsnNode instruction, AbstractInsnNode replacement,
                                ConstantFlow.Fact... operands) {
        // Keep calls and other operand producers. Removing only consecutive literal pushes avoids crossing a label.
        for (var operand : operands) {
            var previous = instruction.getPrevious();
            if (operand.number() instanceof Integer && integer(previous) != null
                    || operand.number() instanceof Long && longLiteral(previous)
                    || operand.isNull() && previous != null && previous.getOpcode() == ACONST_NULL && !annotated(previous)) {
                code.remove(previous);
            } else {
                code.insertBefore(instruction, new InsnNode(operand.getSize() == 2 ? POP2 : POP));
            }
        }
        code.set(instruction, replacement);
    }

    private static boolean longLiteral(AbstractInsnNode insn) {
        return insn != null && !annotated(insn) && (insn.getOpcode() == LCONST_0 || insn.getOpcode() == LCONST_1
                || insn instanceof LdcInsnNode load && load.cst instanceof Long);
    }

    private static Boolean branch(int opcode, ConstantFlow.Fact left, ConstantFlow.Fact right) {
        if (opcode >= IFEQ && opcode <= IFLE && right.number() instanceof Integer value) {
            return matches(Integer.compare(value, 0), opcode - IFEQ);
        }
        if ((opcode == IFEQ || opcode == IFNE) && right.nonzero()) return opcode == IFNE;
        if (opcode >= IF_ICMPEQ && opcode <= IF_ICMPLE && left != null
                && left.number() instanceof Integer a && right.number() instanceof Integer b) {
            return matches(Integer.compare(a, b), opcode - IF_ICMPEQ);
        }
        if ((opcode == IF_ICMPEQ || opcode == IF_ICMPNE) && left != null && left.differs(right)) return opcode == IF_ICMPNE;
        if ((opcode == IFNULL || opcode == IFNONNULL) && right.isNull()) return opcode == IFNULL;
        if ((opcode == IF_ACMPEQ || opcode == IF_ACMPNE) && left != null && left.isNull() && right.isNull()) {
            return opcode == IF_ACMPEQ;
        }
        return null;
    }

    private static LabelNode switchTarget(AbstractInsnNode insn, Integer key) {
        if (insn instanceof TableSwitchInsnNode table) {
            if (key == null) return table.labels.stream().allMatch(label -> label == table.dflt) ? table.dflt : null;
            return key >= table.min && key <= table.max ? table.labels.get(key - table.min) : table.dflt;
        }
        if (insn instanceof LookupSwitchInsnNode lookup) {
            if (key == null) return lookup.labels.stream().allMatch(label -> label == lookup.dflt) ? lookup.dflt : null;
            int index = java.util.Collections.binarySearch(lookup.keys, key);
            return index >= 0 ? lookup.labels.get(index) : lookup.dflt;
        }
        return null;
    }

    static boolean matches(int compare, int condition) {
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
        if (insn == null || annotated(insn)) return null;
        int opcode = insn.getOpcode();
        if (opcode >= ICONST_M1 && opcode <= ICONST_5) return opcode - ICONST_0;
        if (opcode == BIPUSH || opcode == SIPUSH) return ((IntInsnNode) insn).operand;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
        return null;
    }

    static AbstractInsnNode push(Number value) {
        if (value instanceof Long wide) {
            return wide == 0 || wide == 1 ? new InsnNode(LCONST_0 + wide.intValue()) : new LdcInsnNode(wide);
        }
        int integer = value.intValue();
        if (integer >= -1 && integer <= 5) return new InsnNode(ICONST_0 + integer);
        if (integer >= Byte.MIN_VALUE && integer <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, integer);
        if (integer >= Short.MIN_VALUE && integer <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, integer);
        return new LdcInsnNode(integer);
    }

    static Integer unary(int opcode, int value) {
        return switch (opcode) {
            case INEG -> -value;
            case I2B -> (int) (byte) value;
            case I2C -> (int) (char) value;
            case I2S -> (int) (short) value;
            default -> null;
        };
    }

    static Integer binary(int opcode, int left, int right) {
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

    private record HandlerRange(LabelNode start, LabelNode end) {}

    private static int removeShadowedHandlers(MethodNode method) {
        var caught = new java.util.HashMap<HandlerRange, HashSet<String>>();
        int before = method.tryCatchBlocks.size();
        method.tryCatchBlocks.removeIf(handler -> {
            var types = caught.computeIfAbsent(new HandlerRange(handler.start, handler.end), key -> new HashSet<>());
            boolean shadowed = types.contains(null) || !types.add(handler.type);
            return shadowed && handler.visibleTypeAnnotations == null && handler.invisibleTypeAnnotations == null;
        });
        return before - method.tryCatchBlocks.size();
    }

    private static void normalize(String owner, MethodNode method, Map<String, Integer> counts,
                                  boolean reachabilityChanged) throws AnalyzerException {
        var code = method.instructions;
        for (var insn : code.toArray()) {
            if (!(insn instanceof JumpInsnNode jump) || annotated(jump) || jump.getOpcode() == JSR) continue;
            LabelNode target = thread(jump.label);
            if (jump.getOpcode() == GOTO && executable(jump.getNext()) == executable(target)) {
                code.set(jump, new InsnNode(NOP));
                counts.merge("redundant jump", 1, Integer::sum);
            } else if (target != jump.label) {
                jump.label = target;
                counts.merge("threaded jump", 1, Integer::sum);
                reachabilityChanged = true;
            }
        }

        // The original frames already cover every instruction when no edge or handler was removed.
        if (!reachabilityChanged) return;
        var frames = Bytecode.frames(owner, method);
        var nodes = code.toArray();
        var liveBefore = new IdentityHashMap<LabelNode, Integer>();
        int live = 0;
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i] instanceof LabelNode label) liveBefore.put(label, live);
            if (frames[i] != null && nodes[i].getOpcode() >= 0) live++;
        }
        for (var handler : method.tryCatchBlocks) {
            if (liveBefore.get(handler.start).equals(liveBefore.get(handler.end))
                    && (handler.invisibleTypeAnnotations != null || handler.visibleTypeAnnotations != null)) return;
        }
        int before = method.tryCatchBlocks.size();
        method.tryCatchBlocks.removeIf(handler -> liveBefore.get(handler.start).equals(liveBefore.get(handler.end)));
        if (before != method.tryCatchBlocks.size()) counts.put("unreachable exception handler", before - method.tryCatchBlocks.size());

        int removed = 0;
        for (int i = 0; i < nodes.length; i++) {
            if (frames[i] == null && nodes[i].getOpcode() >= 0) {
                code.remove(nodes[i]);
                removed++;
            }
        }
        if (removed != 0) counts.put("unreachable instruction", removed);
    }

    private static LabelNode thread(LabelNode original) {
        var label = original;
        for (int hops = 0; hops < 32; hops++) {
            if (!(executable(label) instanceof JumpInsnNode next) || next.getOpcode() != GOTO || annotated(next)) return label;
            label = next.label;
            if (label == original) return original;
        }
        return original;
    }

    private static AbstractInsnNode executable(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node;
    }

    static boolean annotated(AbstractInsnNode instruction) {
        return instruction.visibleTypeAnnotations != null || instruction.invisibleTypeAnnotations != null;
    }
}
