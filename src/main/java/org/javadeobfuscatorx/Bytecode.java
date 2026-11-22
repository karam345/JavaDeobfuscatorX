package org.javadeobfuscatorx;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
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
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

final class Bytecode {
    private static final int PASSES = 6;
    record Result(byte[] bytes, Map<String, Integer> changes, String rollback,
                  StringRecovery.Counts strings, List<String> stringSites) {}
    private record MethodKey(String name, String descriptor) {}

    static ClassNode parse(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    static void verify(byte[] bytes) throws AnalyzerException {
        verify(parse(bytes));
    }

    static void verify(ClassNode node) throws AnalyzerException {
        for (MethodNode method : node.methods) {
            checkBudget(method);
        }
        node.accept(new CheckClassAdapter(new ClassNode(), true));
    }

    static Frame<BasicValue>[] frames(String owner, MethodNode method) throws AnalyzerException {
        checkBudget(method);
        return new Analyzer<>(new BasicVerifier()).analyze(owner, method);
    }

    static void checkBudget(MethodNode method) throws AnalyzerException {
        long instructions = method.instructions.size();
        long slots = (long) method.maxLocals + method.maxStack + 1;
        if (instructions * slots > 2_000_000
                || instructions * method.tryCatchBlocks.size() > 2_000_000
                || (long) method.maxLocals < parameterSlots(method)) {
            throw new AnalyzerException(null, "Method exceeds static verification budget or has invalid locals");
        }
        for (var instruction : method.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) {
                for (int i = 1; i < lookup.keys.size(); i++) {
                    if (lookup.keys.get(i - 1) >= lookup.keys.get(i)) {
                        throw new AnalyzerException(instruction, "LOOKUPSWITCH keys are not strictly increasing");
                    }
                }
            }
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
        try {
            return transform(original, parse(original), hierarchy);
        } catch (RuntimeException | StackOverflowError failure) {
            return new Result(original, Map.of(), Inspection.failure(failure), StringRecovery.Counts.EMPTY, List.of());
        }
    }

    static Result transform(byte[] original, ClassNode node, Hierarchy hierarchy) {
        var changes = new TreeMap<String, Integer>();
        byte[] verified = original;
        String owner = node.name;
        StringRecovery strings = null;
        boolean stringsCommitted = false;
        try {
            // Keep custom attributes and legacy subroutines byte-for-byte.
            if (node.attrs != null || node.fields.stream().anyMatch(field -> field.attrs != null)
                    || node.methods.stream().anyMatch(method -> method.attrs != null)) {
                return new Result(original, Map.of(), "Custom attributes", StringRecovery.Counts.EMPTY, List.of());
            }
            verify(node);
            strings = new StringRecovery(node);
            boolean flow = (node.version & 0xffff) >= 51;
            for (int pass = 0; pass < PASSES; pass++) {
                var replacements = new HashMap<MethodKey, MethodNode>();
                var counts = new TreeMap<String, Integer>();
                for (MethodNode method : node.methods) {
                    var applied = Rewrites.apply(node.name, method, flow, pass == 0 ? strings : null);
                    if (!applied.isEmpty()) {
                        replacements.put(new MethodKey(method.name, method.desc), method);
                        applied.forEach((key, value) -> counts.merge(key, value, Integer::sum));
                    }
                }
                if (replacements.isEmpty()) break;
                byte[] candidate = write(verified, replacements, hierarchy, counts.keySet().stream().anyMatch(
                        change -> change.contains("jump") || change.contains("branch") || change.contains("switch")
                                || change.contains("handler") || change.equals("unreachable instruction")) && flow);
                node = parse(candidate);
                verify(node);
                verified = candidate;
                stringsCommitted = true;
                counts.forEach((key, value) -> changes.merge(key, value, Integer::sum));
            }
            return new Result(verified, changes, null, strings.counts(stringsCommitted), strings.rows(owner, stringsCommitted));
        } catch (AnalyzerException | RuntimeException | StackOverflowError failure) {
            return new Result(verified, changes, Inspection.failure(failure),
                    strings == null ? StringRecovery.Counts.EMPTY : strings.counts(stringsCommitted),
                    strings == null ? List.of() : strings.rows(owner, stringsCommitted));
        }
    }

    private static byte[] write(byte[] input, Map<MethodKey, MethodNode> replacements, Hierarchy hierarchy,
                                boolean computeFrames) {
        var reader = new ClassReader(input);
        var writer = new ClassWriter(reader, computeFrames ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String left, String right) {
                return hierarchy.common(left, right);
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                MethodNode replacement = replacements.get(new MethodKey(name, descriptor));
                if (replacement == null) return output;
                replacement.accept(output);
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }
}
