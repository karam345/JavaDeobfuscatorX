package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class StringRecovery implements Opcodes {
    static final Type STRING = Type.getType(String.class);
    static final Object NULL = new Object();
    private static final int MAX_FRESH = 512;

    record Counts(long detected, long decoded, long replaced, long skipped, long failed) {
        static final Counts EMPTY = new Counts(0, 0, 0, 0, 0);

        Counts plus(Counts other) {
            return new Counts(detected + other.detected, decoded + other.decoded, replaced + other.replaced,
                    skipped + other.skipped, failed + other.failed);
        }
    }

    private record Outcome(Decoder.Text text, List<String> literals, String change, String reason, boolean failed) {
        Outcome(String reason, boolean failed) { this(null, List.of(), null, reason, failed); }
    }
    private record Cached(List<?> args, Outcome outcome) {}
    private record Site(String method, String descriptor, int index, String status, String detail, String text) {}

    private static final Outcome UNKNOWN = new Outcome("Operands are not proven constants", false);
    private static final Outcome BUDGET = new Outcome("Class string analysis budget exhausted", false);
    private final Decoder decoder;
    private final int version;
    private final Map<AbstractInsnNode, Cached> cache = new IdentityHashMap<>();
    private final List<Site> sites = new ArrayList<>();
    private int detected, decoded, proposed, skipped, failed;
    private int literalBudget = 512;
    private int freshUnits = 8192;

    StringRecovery(ClassNode node) {
        decoder = new Decoder(node);
        version = node.version & 0xffff;
    }

    Counts counts(boolean committed) {
        return new Counts(detected, decoded, committed ? proposed : 0, skipped, failed + (committed ? 0 : proposed));
    }

    List<String> rows(String owner, boolean committed) {
        return sites.stream().map(site -> Inspection.row(owner, site.method, site.descriptor, Integer.toString(site.index),
                site.status.equals("proposed") ? committed ? "replaced" : "rolled back" : site.status, site.detail,
                committed ? site.text : "")).toList();
    }

    private boolean candidate(AbstractInsnNode insn) {
        if (insn instanceof InvokeDynamicInsnNode indy) return StringConcat.candidate(indy);
        return insn instanceof MethodInsnNode call && decoder.target(call) != null && Type.getReturnType(call.desc).equals(STRING);
    }

    boolean hasCandidate(AbstractInsnNode[] instructions) {
        for (var instruction : instructions) {
            if (candidate(instruction) || Strings.literalSite(instruction) != null) return true;
        }
        return false;
    }

    String infer(AbstractInsnNode insn, List<? extends ConstantFlow.Fact> values) {
        if (!candidate(insn)) return null;
        var result = evaluate(insn, values);
        return result.text == null ? null : result.text.value();
    }

    private Outcome evaluate(AbstractInsnNode insn, List<? extends ConstantFlow.Fact> values) {
        var args = new ArrayList<>(values.size());
        for (var value : values) {
            Object constant = value.isNull() ? NULL : value.constant();
            if (constant == null) return UNKNOWN;
            args.add(constant);
        }
        Cached previous = cache.get(insn);
        if (previous != null) return previous.args.equals(args) ? previous.outcome : UNKNOWN;
        if (cache.size() >= 512) return BUDGET;
        Outcome result;
        try {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                String value = StringConcat.evaluate(indy, args);
                if (value == null || version < V9) {
                    result = new Outcome("Unsupported StringConcatFactory recipe, descriptor or classfile target", false);
                } else {
                    var literals = java.util.Arrays.stream(indy.bsmArgs).filter(String.class::isInstance).map(String.class::cast).toList();
                    result = new Outcome(new Decoder.Text(value, Decoder.Identity.FRESH), literals, "constant string concatenation", "", false);
                }
            } else {
                var call = (MethodInsnNode) insn;
                var evaluation = decoder.evaluate(decoder.target(call), args);
                result = new Outcome(evaluation.text(), evaluation.literals(), "static string decoder", "", false);
            }
        } catch (Decoder.Unsupported unsupported) {
            result = new Outcome(unsupported.getMessage(), false);
        } catch (AnalyzerException | RuntimeException invalid) {
            result = new Outcome("Static evaluation failed (" + invalid.getClass().getSimpleName() + ")", true);
        }
        cache.put(insn, new Cached(args, result));
        return result;
    }

    String apply(MethodNode method, AbstractInsnNode insn, Frame<ConstantFlow.Fact> frame, int index) {
        var literal = Strings.literalSite(insn);
        Outcome result;
        Type[] parameters = null;
        if (literal != null) {
            if (frame == null) {
                result = UNKNOWN;
            } else if (version < literal.minimumVersion()) {
                result = new Outcome("Codec is newer than the classfile target", false);
            } else if (literalBudget-- <= 0) {
                result = BUDGET;
            } else {
                try {
                    var value = literal.decode();
                    result = new Outcome(new Decoder.Text(value.text(), Decoder.Identity.FRESH), List.of(),
                            "literal string decoder", value.codec(), false);
                } catch (Decoder.Unsupported unsupported) {
                    result = new Outcome(unsupported.getMessage(), false);
                } catch (IllegalArgumentException invalid) {
                    result = new Outcome("Malformed codec input", true);
                }
            }
        } else if (candidate(insn)) {
            parameters = Type.getArgumentTypes(insn instanceof MethodInsnNode call ? call.desc : ((InvokeDynamicInsnNode) insn).desc);
            var args = new ArrayList<ConstantFlow.Fact>(parameters.length);
            if (frame != null) {
                for (int i = frame.getStackSize() - parameters.length; i < frame.getStackSize(); i++) args.add(frame.getStack(i));
            }
            result = frame == null ? UNKNOWN : evaluate(insn, args);
        } else {
            return null;
        }

        detected++;
        if (result.text == null) {
            if (result.failed) failed++; else skipped++;
            log(method, index, result.failed ? "failed" : "skipped", result.reason, "");
            return null;
        }
        decoded++;
        boolean emitsCharacters = result.text.identity() == Decoder.Identity.FRESH
                || result.text.identity() == Decoder.Identity.BUILDER_RESULT;
        int length = result.text.value().length();
        if (result.text.identity() == Decoder.Identity.BORROWED || Rewrites.annotated(insn)
                || emitsCharacters && (length > MAX_FRESH || length > freshUnits)
                || literal != null && !literal.replace(method.instructions, new Strings.Decoded(result.reason, result.text.value()))) {
            skipped++;
            log(method, index, "skipped", "Replacement exceeds size limits, could change identity, or removes an instruction annotation", "");
            return null;
        }
        if (emitsCharacters) freshUnits -= length;
        if (literal == null) replace(method.instructions, insn, parameters, result);
        proposed++;
        log(method, index, "proposed", result.change, result.text.value());
        return result.change;
    }

    private void log(MethodNode method, int index, String status, String detail, String text) {
        if (sites.size() < 128) sites.add(new Site(method.name, method.desc, index, status, detail, text));
    }

    private static void replace(InsnList code, AbstractInsnNode insn, Type[] parameters, Outcome result) {
        var text = result.text;
        var replacement = new InsnList();
        for (int i = parameters.length - 1; i >= 0; i--) {
            // Preserve operand evaluation, including side effects or exceptions.
            replacement.add(new InsnNode(parameters[i].getSize() == 2 ? POP2 : POP));
        }
        // These constants were resolved on the original path. Keep their interning side effects.
        for (String literal : result.literals) {
            if (text.identity() == Decoder.Identity.INTERNED && literal.equals(text.value())) continue;
            replacement.add(new LdcInsnNode(literal));
            replacement.add(new InsnNode(POP));
        }
        if (text.identity() == Decoder.Identity.FRESH) {
            replacement.add(new TypeInsnNode(NEW, "java/lang/String"));
            replacement.add(new InsnNode(DUP));
            replacement.add(Strings.characters(text.value()));
            replacement.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false));
        } else if (text.identity() == Decoder.Identity.BUILDER_RESULT) {
            // Keep the runtime's StringBuilder.toString identity behavior, especially for empty text.
            String builder = "java/lang/StringBuilder";
            replacement.add(new TypeInsnNode(NEW, builder));
            replacement.add(new InsnNode(DUP));
            replacement.add(new MethodInsnNode(INVOKESPECIAL, builder, "<init>", "()V", false));
            if (!text.value().isEmpty()) {
                replacement.add(Strings.characters(text.value()));
                replacement.add(new MethodInsnNode(INVOKEVIRTUAL, builder, "append", "([C)Ljava/lang/StringBuilder;", false));
            }
            replacement.add(new MethodInsnNode(INVOKEVIRTUAL, builder, "toString", "()Ljava/lang/String;", false));
        } else {
            replacement.add(new LdcInsnNode(text.value()));
        }
        code.insertBefore(insn, replacement);
        code.remove(insn);
    }
}
