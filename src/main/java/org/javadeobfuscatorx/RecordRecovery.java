package org.javadeobfuscatorx;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.Label;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RecordRecovery implements Opcodes {
    private static final String BOOTSTRAP = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";
    private static final Handle OBJECT_METHODS = new Handle(H_INVOKESTATIC, "java/lang/runtime/ObjectMethods",
            "bootstrap", BOOTSTRAP, false);
    private static final String BUILDER = "java/lang/StringBuilder";
    private static final String OBJECTS = "java/util/Objects";
    private static final Set<String> RESERVED = Set.of("clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");
    record Component(String name, Handle field) {}

    static List<Component> infer(ClassNode node) {
        if (!"java/lang/Record".equals(node.superName) || (node.access & ACC_FINAL) == 0
                || node.recordComponents != null && !node.recordComponents.isEmpty()) return List.of();
        var fields = new HashSet<Handle>();
        for (var field : node.fields) {
            if ((field.access & ACC_STATIC) != 0) continue;
            if ((field.access & (ACC_PRIVATE | ACC_FINAL)) != (ACC_PRIVATE | ACC_FINAL)) return List.of();
            fields.add(new Handle(H_GETFIELD, node.name, field.name, field.desc, false));
        }
        if (fields.isEmpty() || fields.size() > 254) return List.of();
        List<Component> evidence = null;
        for (var method : node.methods) {
            if (!(method.name.equals("toString") && method.desc.equals("()Ljava/lang/String;")
                    || method.name.equals("hashCode") && method.desc.equals("()I")
                    || method.name.equals("equals") && method.desc.equals("(Ljava/lang/Object;)Z"))) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode indy) || !indy.name.equals(method.name)
                        || !indy.bsm.equals(OBJECT_METHODS) || !indy.desc.equals(descriptor(node.name, indy.name))) continue;
                var args = indy.bsmArgs;
                if (args.length != fields.size() + 2 || !Type.getObjectType(node.name).equals(args[0])
                        || !(args[1] instanceof String names)) return List.of();
                String[] namesInOrder = names.split(";", -1);
                if (namesInOrder.length != fields.size()) return List.of();
                var components = new ArrayList<Component>();
                var usedNames = new HashSet<String>();
                var usedFields = new HashSet<Handle>();
                for (int i = 0; i < namesInOrder.length; i++) {
                    String name = namesInOrder[i];
                    if (!Identifiers.sourceName(name) || RESERVED.contains(name) || !usedNames.add(name)
                            || !(args[i + 2] instanceof Handle field) || !fields.contains(field) || !usedFields.add(field)) return List.of();
                    components.add(new Component(name, field));
                }
                if (evidence != null && !evidence.equals(components)) return List.of();
                evidence = components;
            }
        }
        if (evidence == null) return List.of();
        String constructor = Type.getMethodDescriptor(Type.VOID_TYPE,
                evidence.stream().map(component -> Type.getType(component.field.getDesc())).toArray(Type[]::new));
        if (node.methods.stream().noneMatch(method -> method.name.equals("<init>") && method.desc.equals(constructor))) return List.of();
        return evidence;
    }

    static void restore(ClassNode node, List<Component> components) {
        node.access |= ACC_RECORD;
        node.recordComponents = new ArrayList<>();
        for (var component : components) {
            var field = node.fields.stream().filter(f -> f.name.equals(component.field.getName())
                    && f.desc.equals(component.field.getDesc())).findFirst().orElseThrow();
            // Attribute names must match the pre-remap field keys. The remapper supplies the component names.
            node.recordComponents.add(new RecordComponentNode(field.name, field.desc, field.signature));
        }
    }

    static int lower(ClassNode node, List<Component> components, String simpleName, java.util.function.Supplier<String> names) {
        var helpers = new java.util.LinkedHashMap<String, MethodNode>();
        String labels = String.join(";", components.stream().map(Component::name).toList());
        int count = 0;
        for (var method : node.methods) {
            for (var insn : method.instructions.toArray()) {
                if (!(insn instanceof InvokeDynamicInsnNode indy) || Rewrites.annotated(indy)
                        || !indy.bsm.equals(OBJECT_METHODS) || !indy.desc.equals(descriptor(node.name, indy.name))
                        || indy.bsmArgs.length != components.size() + 2 || !Type.getObjectType(node.name).equals(indy.bsmArgs[0])
                        || !labels.equals(indy.bsmArgs[1])) continue;
                boolean matches = true;
                for (int i = 0; i < components.size(); i++) matches &= components.get(i).field.equals(indy.bsmArgs[i + 2]);
                if (!matches) continue;
                var helper = helpers.computeIfAbsent(indy.name,
                        key -> helper(node.name, simpleName, components, key, indy.desc, names.get()));
                method.instructions.set(indy, new MethodInsnNode(INVOKESTATIC, node.name, helper.name, indy.desc, false));
                count++;
            }
        }
        node.methods.addAll(helpers.values());
        return count;
    }

    private static String descriptor(String owner, String operation) {
        String receiver = "(L" + owner + ";";
        return switch (operation) {
            case "equals" -> receiver + "Ljava/lang/Object;)Z";
            case "hashCode" -> receiver + ")I";
            case "toString" -> receiver + ")Ljava/lang/String;";
            default -> "";
        };
    }

    private static MethodNode helper(String owner, String simpleName, List<Component> components,
                                     String operation, String descriptor, String name) {
        var code = new MethodNode(ACC_PRIVATE | ACC_STATIC, name, descriptor, null, null);
        code.visitCode();
        switch (operation) {
            case "equals" -> {
                var different = new Label();
                var equal = new Label();
                code.visitVarInsn(ALOAD, 0);
                code.visitVarInsn(ALOAD, 1);
                code.visitJumpInsn(IF_ACMPEQ, equal);
                code.visitVarInsn(ALOAD, 1);
                code.visitTypeInsn(INSTANCEOF, owner);
                code.visitJumpInsn(IFEQ, different);
                for (var component : components) {
                    box(code, field(code, component, 0, null));
                    box(code, field(code, component, 1, owner));
                    code.visitMethodInsn(INVOKESTATIC, OBJECTS, "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                    code.visitJumpInsn(IFEQ, different);
                }
                code.visitLabel(equal);
                code.visitFrame(F_SAME, 0, null, 0, null);
                code.visitInsn(ICONST_1);
                code.visitInsn(IRETURN);
                code.visitLabel(different);
                code.visitFrame(F_SAME, 0, null, 0, null);
                code.visitInsn(ICONST_0);
                code.visitInsn(IRETURN);
            }
            case "hashCode" -> {
                code.visitInsn(ICONST_0);
                for (var component : components) {
                    code.visitIntInsn(BIPUSH, 31);
                    code.visitInsn(IMUL);
                    box(code, field(code, component, 0, null));
                    code.visitMethodInsn(INVOKESTATIC, OBJECTS, "hashCode", "(Ljava/lang/Object;)I", false);
                    code.visitInsn(IADD);
                }
                code.visitInsn(IRETURN);
            }
            case "toString" -> {
                code.visitTypeInsn(NEW, BUILDER);
                code.visitInsn(DUP);
                code.visitMethodInsn(INVOKESPECIAL, BUILDER, "<init>", "()V", false);
                for (int i = 0; i < components.size(); i++) {
                    var component = components.get(i);
                    code.visitLdcInsn((i == 0 ? simpleName + "[" : ", ") + component.name + "=");
                    append(code, "Ljava/lang/String;");
                    Type type = field(code, component, 0, null);
                    if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) append(code, "Ljava/lang/Object;");
                    else append(code, type.equals(Type.BYTE_TYPE) || type.equals(Type.SHORT_TYPE) ? "I" : type.getDescriptor());
                }
                code.visitLdcInsn("]");
                append(code, "Ljava/lang/String;");
                code.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "toString", "()Ljava/lang/String;", false);
                code.visitInsn(ARETURN);
            }
            default -> throw new IllegalArgumentException("Unexpected record operation " + operation);
        }
        code.visitMaxs(5, Type.getArgumentTypes(descriptor).length);
        code.visitEnd();
        return code;
    }

    private static Type field(MethodNode code, Component component, int slot, String cast) {
        var field = component.field;
        code.visitVarInsn(ALOAD, slot);
        if (cast != null) code.visitTypeInsn(CHECKCAST, cast);
        code.visitFieldInsn(GETFIELD, field.getOwner(), field.getName(), field.getDesc());
        return Type.getType(field.getDesc());
    }

    private static void box(MethodNode code, Type type) {
        String wrapper = switch (type.getSort()) {
            case Type.BOOLEAN -> "Boolean";
            case Type.BYTE -> "Byte";
            case Type.CHAR -> "Character";
            case Type.SHORT -> "Short";
            case Type.INT -> "Integer";
            case Type.LONG -> "Long";
            case Type.FLOAT -> "Float";
            case Type.DOUBLE -> "Double";
            default -> null;
        };
        if (wrapper != null) {
            String owner = "java/lang/" + wrapper;
            code.visitMethodInsn(INVOKESTATIC, owner, "valueOf", "(" + type.getDescriptor() + ")L" + owner + ";", false);
        }
    }

    private static void append(MethodNode code, String descriptor) {
        code.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "append", "(" + descriptor + ")L" + BUILDER + ";", false);
    }
}
