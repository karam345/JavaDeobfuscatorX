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
import java.util.HashSet;
import java.util.Set;

final class Bytecode {
    private static final int PASSES = 6;
    record Result(byte[] bytes, Map<String, Integer> changes, String rollback,
                  StringRecovery.Counts strings, List<String> stringSites) {}
    private record MethodKey(String name, String descriptor) {}
    private record Replacement(MethodNode method, boolean computeFrames) {}
    private record Pass(Map<MethodKey, Replacement> replacements, Map<String, Integer> counts) {}
    private static final class FrameFailure extends RuntimeException {
        final MethodKey method;

        FrameFailure(MethodKey method, IllegalArgumentException cause) {
            super(cause);
            this.method = method;
        }
    }

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
        return transform(original, node, hierarchy, null);
    }

    static Result transform(byte[] original, ClassNode node, Hierarchy hierarchy, DecoderIndex decoders) {
        var changes = new TreeMap<String, Integer>();
        byte[] verified = original;
        String owner = node.name;
        StringRecovery strings = null;
        boolean stringsCommitted = false;
        var frameSkips = new java.util.ArrayList<String>();
        try {
            // Keep custom attributes and legacy subroutines byte-for-byte.
            if (node.attrs != null || node.fields.stream().anyMatch(field -> field.attrs != null)
                    || node.methods.stream().anyMatch(method -> method.attrs != null)) {
                return new Result(original, Map.of(), "Custom attributes", StringRecovery.Counts.EMPTY, List.of());
            }
            verify(node);
            boolean flow = (node.version & 0xffff) >= 51;
            var preserveFrames = new HashSet<MethodKey>();
            for (int pass = 0; pass < PASSES; pass++) {
                var numeric = rewrite(node, flow, null, preserveFrames);
                if (numeric.replacements.isEmpty()) break;
                byte[] candidate;
                while (true) {
                    if (numeric.replacements.isEmpty()) {
                        candidate = verified;
                        break;
                    }
                    try {
                        candidate = write(verified, numeric.replacements, hierarchy);
                        break;
                    } catch (FrameFailure failure) {
                        frameSkips.add(failure.method.name + failure.method.descriptor + ": " + Inspection.failure(failure.getCause()));
                        if (!preserveFrames.add(failure.method) || preserveFrames.size() >= 16) flow = false;
                        numeric = rewrite(parse(verified), flow, null, preserveFrames);
                    }
                }
                if (candidate == verified) break;
                node = parse(candidate);
                verify(node);
                verified = candidate;
                numeric.counts.forEach((key, value) -> changes.merge(key, value, Integer::sum));
            }

            // A branch removed by a verified pass can expose constant inputs at a later join.
            node = parse(verified);
            strings = new StringRecovery(node, decoders);
            var stringPass = rewrite(node, false, strings, Set.of());
            if (!stringPass.replacements.isEmpty()) {
                byte[] candidate = write(verified, stringPass.replacements, hierarchy);
                verify(candidate);
                verified = candidate;
                stringsCommitted = true;
                stringPass.counts.forEach((key, value) -> changes.merge(key, value, Integer::sum));
            }
            return new Result(verified, changes, frameSkips.isEmpty() ? null : String.join(" | ", frameSkips),
                    strings.counts(stringsCommitted), strings.rows(owner, stringsCommitted));
        } catch (AnalyzerException | RuntimeException | StackOverflowError failure) {
            return new Result(verified, changes, Inspection.failure(failure),
                    strings == null ? StringRecovery.Counts.EMPTY : strings.counts(stringsCommitted),
                    strings == null ? List.of() : strings.rows(owner, stringsCommitted));
        }
    }

    private static Pass rewrite(ClassNode node, boolean flow, StringRecovery strings, Set<MethodKey> preserveFrames)
            throws AnalyzerException {
        var replacements = new HashMap<MethodKey, Replacement>();
        var counts = new TreeMap<String, Integer>();
        for (MethodNode method : node.methods) {
            var key = new MethodKey(method.name, method.desc);
            boolean methodFlow = flow && !preserveFrames.contains(key);
            var applied = Rewrites.apply(node.name, method, methodFlow, strings);
            if (!applied.isEmpty()) {
                boolean changedEdges = applied.keySet().stream().anyMatch(change -> change.contains("jump")
                        || change.contains("branch") || change.contains("switch") || change.contains("handler")
                        || change.equals("unreachable instruction"));
                replacements.put(key, new Replacement(method, methodFlow && changedEdges));
                applied.forEach((change, value) -> counts.merge(change, value, Integer::sum));
            }
        }
        return new Pass(replacements, counts);
    }

    private static byte[] write(byte[] input, Map<MethodKey, Replacement> replacements, Hierarchy hierarchy) {
        var reader = new ClassReader(input);
        var writer = new ClassWriter(reader, 0) {
            @Override
            protected String getCommonSuperClass(String left, String right) {
                return hierarchy.common(left, right);
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                var key = new MethodKey(name, descriptor);
                var replacement = replacements.get(key);
                writer.setFlags(replacement == null ? 0 : replacement.computeFrames ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);
                MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (replacement == null) return output;
                try {
                    replacement.method.accept(output);
                } catch (IllegalArgumentException failure) {
                    if (!replacement.computeFrames) throw failure;
                    throw new FrameFailure(key, failure);
                }
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }
}
