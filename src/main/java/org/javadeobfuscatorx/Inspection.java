package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

final class Inspection implements Opcodes {
    private static final int ROW_LIMIT = 50_000;
    private static final String[] VENDORS = {
        "Allatori", "Zelix KlassMaster", "Stringer", "DashO", "ProGuard", "R8", "yGuard",
        "Skidfuscator", "ClassGuard", "Radon", "Recaf"
    };

    final Map<String, Integer> findings = new TreeMap<>();
    final List<String> versions = new ArrayList<>();
    final List<String> attribution = new ArrayList<>();
    final List<String> problems = new ArrayList<>();
    final List<String> references = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final List<String> strings = new ArrayList<>();
    final Minecraft minecraft;
    int parsed;
    int verified;
    boolean evidenceTruncated;
    private int inferenceIndex;
    private int evidenceChars;

    Inspection(Archive archive, boolean verify) throws IOException {
        minecraft = new Minecraft(archive);
        archive.classes.stream().sorted(java.util.Comparator.comparingInt(Archive.ClassEntry::major)
                        .thenComparingInt(Archive.ClassEntry::minor))
                .map(entry -> entry.major() + "." + entry.minor() + " (Java " + (entry.major() - 44)
                        + (entry.minor() == 65535 ? " preview" : "") + ")").distinct().forEach(versions::add);
        problems.addAll(archive.problems);
        inspectMetadata(archive);
        for (var entry : archive.selected.values()) {
            try {
                byte[] bytes = archive.read(entry.entry());
                ClassNode node = Bytecode.parse(bytes);
                inspectClass(node);
                parsed++;
                if (verify) {
                    Bytecode.verify(bytes);
                    verified++;
                }
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException | StackOverflowError failure) {
                problems.add(entry.entry().getName() + ": " + failure(failure));
            }
        }
        minecraft.finish(archive.selected.keySet());
    }

    private void inspectMetadata(Archive archive) {
        for (String key : List.of("Obfuscator", "X-Obfuscator")) {
            String value = archive.manifest.getMainAttributes().getValue(key);
            if (value == null) continue;
            String normalized = value.strip().toLowerCase(Locale.ROOT);
            for (String vendor : VENDORS) {
                String label = vendor.toLowerCase(Locale.ROOT);
                if (normalized.equals(label) || normalized.startsWith(label + " ")) {
                    attribution.add("Declared: " + vendor + " (manifest " + key + "; self-reported)");
                }
            }
        }
        for (String name : archive.resources.keySet().stream().sorted().toList()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.matches("meta-inf/(allatori|zelix|klassmaster|stringer|dasho|proguard|r8|yguard|skidfuscator|radon)([./_-].*)?")) {
                attribution.add("Possible vendor reference in " + name);
                if (attribution.size() == 32) break;
            }
        }
    }

    private void inspectClass(ClassNode node) {
        String shortName = node.name.substring(node.name.lastIndexOf('/') + 1);
        var targets = new HashSet<String>();
        Consumer<String> reference = target -> {
            if (target != null && !target.equals(node.name) && targets.size() < 4096) targets.add(target);
        };
        reference.accept(node.superName);
        node.interfaces.forEach(reference);
        minecraft.inspectClass(node);
        if (!shortName.contains("$") && suspicious(shortName)) {
            count("short or source-incompatible class name");
            String purpose = minecraft.role(node);
            suggest("class", node.name, "", "", purpose, purpose.equals("Type") ? "low" : "medium");
        }
        node.fields.forEach(field -> {
            Type type = Type.getType(field.desc);
            reference(type, reference);
            if (suspicious(field.name)) {
                count("short or source-incompatible field name");
                String role = type.equals(Type.BOOLEAN_TYPE) ? "flag" : type.getSort() == Type.ARRAY ? "values" : "value";
                suggest("field", node.name, field.name, field.desc, role, "low");
            }
        });
        for (MethodNode method : node.methods) inspectMethod(node, method, reference);
        targets.stream().sorted().forEach(target -> add(references, row(node.name, target)));
    }

    private void inspectMethod(ClassNode node, MethodNode method, Consumer<String> reference) {
        for (Type type : Type.getArgumentTypes(method.desc)) reference(type, reference);
        reference(Type.getReturnType(method.desc), reference);
        method.exceptions.forEach(reference);
        if ((method.access & ACC_SYNTHETIC) != 0) count("synthetic method");
        method.tryCatchBlocks.forEach(handler -> {
            count(handler.type == null ? "catch-all handler" : "typed exception handler");
            reference.accept(handler.type);
        });
        int switches = 0;
        int calls = 0;
        int realInstructions = 0;
        int backwardEdges = 0;
        var seen = new HashSet<AbstractInsnNode>();
        FieldInsnNode accessor = null;
        for (var insn : method.instructions) {
            seen.add(insn);
            if (insn.getOpcode() < 0) continue;
            realInstructions++;
            if (insn instanceof MethodInsnNode call) {
                calls++;
                reference.accept(call.owner);
                minecraft.inspectCall(call);
                if (call.owner.startsWith("java/lang/reflect/") || call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                        || call.owner.equals("java/lang/Class") && call.name.equals("forName")) count("reflection or method-handle call");
                if (call.owner.startsWith("javax/crypto/")) count("crypto API call");
                decode(node, method, call);
            } else if (insn instanceof FieldInsnNode field) {
                accessor = field;
                reference.accept(field.owner);
                reference(Type.getType(field.desc), reference);
            } else if (insn instanceof TypeInsnNode type) {
                if (type.desc.startsWith("[")) reference(Type.getType(type.desc), reference);
                else reference.accept(type.desc);
            } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                count("invokedynamic");
                reference.accept(dynamic.bsm.getOwner());
                if (!Set.of("java/lang/invoke/StringConcatFactory", "java/lang/invoke/LambdaMetafactory",
                        "java/lang/runtime/ObjectMethods", "java/lang/runtime/SwitchBootstraps")
                        .contains(dynamic.bsm.getOwner())) count("custom invokedynamic bootstrap");
            } else if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                switches++;
                count("switch");
            } else if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump && seen.contains(jump.label)) {
                backwardEdges++;
            } else if (insn instanceof LdcInsnNode load && load.cst instanceof org.objectweb.asm.ConstantDynamic dynamic) {
                count("constant dynamic");
                reference.accept(dynamic.getBootstrapMethod().getOwner());
            }
        }
        if (switches > 0 && backwardEdges > 0) count("method with switch and backward edge");
        if (calls == 1 && realInstructions <= 10) count("short single-call method");
        if (suspicious(method.name) && !method.name.startsWith("<")) {
            count("short or source-incompatible method name");
            boolean getter = accessor != null && calls == 0
                    && (accessor.getOpcode() == GETFIELD || accessor.getOpcode() == GETSTATIC)
                    && Type.getArgumentTypes(method.desc).length == 0
                    && Type.getReturnType(method.desc).getDescriptor().equals(accessor.desc)
                    && realInstructions == (accessor.getOpcode() == GETFIELD ? 3 : 2);
            suggest("method", node.name, method.name, method.desc, getter ? "getValue" : "method",
                    getter ? "medium" : "low");
        }
    }

    private void decode(ClassNode node, MethodNode method, MethodInsnNode call) {
        if (!call.owner.equals("java/util/Base64$Decoder") || !call.name.equals("decode")
                || !call.desc.equals("(Ljava/lang/String;)[B") || call.getOpcode() != INVOKEVIRTUAL
                || !(call.getPrevious() instanceof LdcInsnNode load) || !(load.cst instanceof String literal)
                || literal.length() < 4 || literal.length() > 4096
                || !(load.getPrevious() instanceof MethodInsnNode factory)
                || !factory.owner.equals("java/util/Base64") || !factory.name.equals("getDecoder")
                || factory.getOpcode() != INVOKESTATIC || !factory.desc.equals("()Ljava/util/Base64$Decoder;")
                || strings.size() >= 1000) return;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getDecoder().decode(literal))).toString();
            if (!text.isBlank() && text.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
                add(strings, row(node.name, method.name, method.desc, "Base64 to UTF-8", text));
            }
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException ignored) {
            // A malformed literal or binary payload supplies no text evidence.
        }
    }

    private void suggest(String kind, String owner, String member, String desc, String role, String confidence) {
        add(names, row(kind, owner, member, desc, role + "_" + (++inferenceIndex), confidence));
    }

    private static boolean suspicious(String name) {
        return name.codePointCount(0, name.length()) <= 2 || !SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name);
    }

    private static void reference(Type type, Consumer<String> target) {
        if (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) target.accept(type.getInternalName());
    }

    private void count(String finding) {
        findings.merge(finding, 1, Integer::sum);
    }

    private void add(List<String> destination, String line) {
        if (destination.size() >= ROW_LIMIT || line.length() > 8 * Archive.MIB - evidenceChars) {
            evidenceTruncated = true;
            return;
        }
        evidenceChars += line.length();
        destination.add(line);
    }

    static String row(String... fields) {
        return String.join("\t", java.util.Arrays.stream(fields)
                .map(field -> field.length() <= 4096 ? field : field.substring(0, 4096) + " [truncated]")
                .map(field -> field.replace("\\", "\\\\").replace("\t", "\\t")
                        .replace("\r", "\\r").replace("\n", "\\n")
                        .replaceAll("[\\p{Cc}\\p{Cf}]", "?"))
                .toList());
    }

    static String failure(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        String first = message.lines().findFirst().orElse("");
        return failure.getClass().getSimpleName() + ": " + first.substring(0, Math.min(first.length(), 500));
    }
}
