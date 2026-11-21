package org.javadeobfuscatorx;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.Map;
import java.util.TreeMap;

final class Bytecode {
    record Result(byte[] bytes, Map<String, Integer> changes, String rollback) {}

    static ClassNode parse(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    static void verify(byte[] bytes) throws AnalyzerException {
        verify(parse(bytes));
    }

    private static void verify(ClassNode node) throws AnalyzerException {
        for (MethodNode method : node.methods) {
            checkBudget(method);
        }
        node.accept(new CheckClassAdapter(new ClassNode(), true));
    }

    static Frame<BasicValue>[] frames(String owner, MethodNode method) throws AnalyzerException {
        checkBudget(method);
        return new Analyzer<>(new BasicVerifier()).analyze(owner, method);
    }

    private static void checkBudget(MethodNode method) throws AnalyzerException {
        long instructions = method.instructions.size();
        long slots = (long) method.maxLocals + method.maxStack + 1;
        if (instructions * slots > 2_000_000
                || instructions * method.tryCatchBlocks.size() > 2_000_000
                || (long) method.maxLocals < parameterSlots(method)) {
            throw new AnalyzerException(null, "Method exceeds static verification budget or has invalid locals");
        }
    }

    private static int parameterSlots(MethodNode method) {
        if (method.instructions.size() == 0) {
            return 0;
        }
        int slots = (method.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : Type.getArgumentTypes(method.desc)) {
            slots += type.getSize();
        }
        return slots;
    }

    static Result transform(byte[] original, Hierarchy hierarchy) {
        var changes = new TreeMap<String, Integer>();
        try {
            // Keep custom attributes and legacy subroutines byte-for-byte.
            var node = parse(original);
            if (node.attrs != null || node.fields.stream().anyMatch(field -> field.attrs != null)
                    || node.methods.stream().anyMatch(method -> method.attrs != null)) {
                return new Result(original, Map.of(), "Custom attributes");
            }
            verify(node);
            boolean controlFlowChanged = false;
            for (MethodNode method : node.methods) {
                if (method.instructions.size() == 0) {
                    continue;
                }
                var counts = Rewrites.apply(method, node.version & 0xffff);
                boolean simplified = counts.containsKey("constant branch") || counts.containsKey("constant switch");
                controlFlowChanged |= simplified || counts.containsKey("redundant jump");
                counts.forEach((key, value) -> changes.merge(key, value, Integer::sum));
                if (simplified) {
                    int removed = Rewrites.prune(node.name, method);
                    if (removed != 0) {
                        changes.merge("unreachable instruction", removed, Integer::sum);
                    }
                }
            }
            if (changes.isEmpty()) {
                return new Result(original, Map.of(), null);
            }
            var writer = new ClassWriter(controlFlowChanged ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String left, String right) {
                    return hierarchy.common(left, right);
                }
            };
            node.accept(writer);
            byte[] candidate = writer.toByteArray();
            verify(candidate);
            return new Result(candidate, changes, null);
        } catch (AnalyzerException | RuntimeException | StackOverflowError failure) {
            return new Result(original, Map.of(), Inspection.failure(failure));
        }
    }
}
