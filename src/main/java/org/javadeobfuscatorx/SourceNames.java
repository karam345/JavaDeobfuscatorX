package org.javadeobfuscatorx;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Remaps JVM-only names in the bytecode supplied to the decompilers. */
final class SourceNames extends Remapper implements Opcodes {
    record View(SourceInventory inventory, List<String> rows, int aliases, int records, int recordCalls) {}
    private record Member(String name, String descriptor) {}
    private record Symbol(String owner, Member member) {}
    private record Definition(ClassNode header, Set<Member> fields, Set<Member> methods,
                              Set<String> methodShapes, String outer, String inner) {}
    private static final String UNRESOLVED = "";

    private final Map<String, Definition> classes = new TreeMap<>();
    private final Map<String, Definition> external = new HashMap<>();
    private final Map<String, List<String>> children = new HashMap<>();
    private final Map<String, List<RecordRecovery.Component>> records = new HashMap<>();
    private final Map<String, String> packages = new HashMap<>();
    private final Set<String> archivePackages = new HashSet<>();
    private final Map<String, String> types = new HashMap<>();
    private final Map<String, String> innerNames = new HashMap<>();
    private final Map<Symbol, String> fieldNames = new HashMap<>();
    private final Map<Symbol, String> methodNames = new HashMap<>();
    private final Set<Member> aliasedFields = new HashSet<>();
    private final Set<Member> aliasedMethods = new HashSet<>();
    private final Set<String> reserved = new HashSet<>();
    private final List<String> rows = new ArrayList<>();
    private final DecompilerWorker.Libraries libraries;
    private int next;
    private int declarationCount;

    private SourceNames(DecompilerWorker.Libraries libraries) {
        super(ASM9);
        this.libraries = libraries;
    }

    static View build(Path input, Path output, List<Path> dependencies) throws IOException {
        try (var source = new ZipFile(input.toFile()); var libraries = new DecompilerWorker.Libraries(dependencies)) {
            var names = new SourceNames(libraries);
            var entries = source.stream().filter(entry -> entry.getName().endsWith(".class"))
                    .sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            for (var entry : entries) names.index(DecompilerWorker.readClass(source, entry.getName()));
            names.plan();
            if (names.rows.isEmpty()) return null;

            var inventory = new SourceInventory();
            var paths = new HashSet<String>();
            int lowered = 0;
            try (var target = new ZipOutputStream(Files.newOutputStream(output))) {
                for (var entry : entries) {
                    byte[] bytes = DecompilerWorker.readClass(source, entry.getName());
                    var node = Bytecode.parse(bytes);
                    var record = names.records.get(node.name);
                    if (record != null) {
                        RecordRecovery.restore(node, record);
                        var definition = names.classes.get(node.name);
                        String simpleName = definition.inner == null ? node.name.substring(node.name.lastIndexOf('/') + 1)
                                : definition.inner;
                        lowered += RecordRecovery.lower(node, record, simpleName, () -> names.fresh("recordOperation"));
                    }
                    var outputClass = new ClassNode();
                    node.accept(new ClassRemapper(outputClass, names));
                    String path = outputClass.name + ".class";
                    Archive.checkName(path);
                    if (!paths.add(fold(path))) throw new IOException("Colliding reconstruction class paths");
                    var writer = new ClassWriter(new ClassReader(bytes), 0);
                    outputClass.accept(writer);
                    Exports.put(target, path, writer.toByteArray());
                    inventory.add(outputClass);
                }
            }
            int aliases = names.fieldNames.size() + names.methodNames.size() + names.types.size() + names.packages.size();
            return new View(inventory, names.rows, aliases, names.records.size(), lowered);
        }
    }

    private void index(byte[] bytes) throws IOException {
        var definition = declaration(new ClassReader(bytes));
        var header = definition.header;
        declarationCount += 1 + header.fields.size() + header.methods.size();
        if (declarationCount > 250_000) throw new IOException("Decompiler name index exceeds its declaration budget");
        definition.fields.forEach(member -> reserved.add(fold(member.name)));
        definition.methods.forEach(member -> reserved.add(fold(member.name)));
        for (String part : header.name.split("/")) reserved.add(fold(part));
        int slash = header.name.lastIndexOf('/');
        if (slash >= 0) archivePackages.add(header.name.substring(0, slash));
        if (definition.inner != null) reserved.add(fold(definition.inner));
        if ("java/lang/Record".equals(header.superName) && (header.recordComponents == null || header.recordComponents.isEmpty())) {
            var recovered = RecordRecovery.infer(Bytecode.parse(bytes));
            if (!recovered.isEmpty()) {
                records.put(header.name, recovered);
                recovered.forEach(component -> reserved.add(fold(component.name())));
            }
        }
        classes.put(header.name, definition);
        if (header.superName != null) children.computeIfAbsent(header.superName, ignored -> new ArrayList<>()).add(header.name);
        for (String name : header.interfaces) children.computeIfAbsent(name, ignored -> new ArrayList<>()).add(header.name);
    }

    private static Definition declaration(ClassReader reader) {
        var header = new ClassNode();
        reader.accept(header, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String outer = header.outerClass;
        String innerName = null;
        for (var inner : header.innerClasses) {
            if (!inner.name.equals(header.name)) continue;
            if (inner.outerName != null) outer = inner.outerName;
            innerName = inner.innerName;
            break;
        }
        var fields = new HashSet<Member>();
        var methods = new HashSet<Member>();
        var shapes = new HashSet<String>();
        header.fields.forEach(field -> fields.add(new Member(field.name, field.desc)));
        header.methods.forEach(method -> {
            methods.add(new Member(method.name, method.desc));
            shapes.add(parameterKey(method));
        });
        return new Definition(header, fields, methods, shapes, outer, innerName);
    }

    private void plan() {
        var invalidMethods = new HashMap<String, String>();
        for (var definition : classes.values()) {
            var header = definition.header;
            mapClass(header.name, 0);
            var fieldCounts = new HashMap<String, Integer>();
            var methodCounts = new HashMap<String, Integer>();
            header.fields.forEach(field -> fieldCounts.merge(field.name, 1, Integer::sum));
            header.methods.stream().filter(method -> (method.access & ACC_BRIDGE) == 0)
                    .forEach(method -> methodCounts.merge(parameterKey(method), 1, Integer::sum));
            var components = records.getOrDefault(header.name, List.of());
            if (!components.isEmpty()) rows.add(Inspection.row("record", header.name, "", "", Integer.toString(components.size())));
            for (var field : header.fields) {
                var component = components.stream().filter(candidate -> candidate.field().getName().equals(field.name)
                        && candidate.field().getDesc().equals(field.desc)).findFirst();
                if (component.isPresent()) {
                    rename(fieldNames, "field", header.name, field.name, field.desc, component.get().name());
                } else if (!isComponent(header, field)
                        && (!Identifiers.sourceName(field.name) || fieldCounts.get(field.name) > 1
                        || (field.access & ACC_ENUM) == 0 && (shortName(field.name)
                        || components.stream().anyMatch(candidate -> candidate.name().equals(field.name))))) {
                    rename(fieldNames, "field", header.name, field.name, field.desc, fresh("field"));
                }
            }
            for (var method : header.methods) {
                if (method.name.startsWith("<") || (header.access & ACC_ANNOTATION) != 0 || isAccessor(header, method)) continue;
                boolean localLinkage = (method.access & (ACC_STATIC | ACC_PRIVATE)) != 0;
                boolean duplicate = methodCounts.getOrDefault(parameterKey(method), 0) > 1;
                String shape = parameterKey(method);
                boolean closedCollision = duplicate && !localLinkage && (method.access & ACC_BRIDGE) == 0
                        && !hasInheritedMethod(header, shape) && !hasArchiveOverride(header, shape);
                if (closedCollision || localLinkage && (duplicate || (method.access & ACC_PRIVATE) != 0 && shortName(method.name))) {
                    rename(methodNames, "method", header.name, method.name, method.desc, fresh("method"));
                } else if (!Identifiers.sourceName(method.name)) {
                    rename(methodNames, "method", header.name, method.name, method.desc,
                            invalidMethods.computeIfAbsent(method.name, ignored -> fresh("method")));
                }
            }
        }
    }

    private static boolean shortName(String name) {
        return name.codePointCount(0, name.length()) <= 2;
    }

    private static boolean isComponent(ClassNode header, FieldNode field) {
        return header.recordComponents != null && header.recordComponents.stream()
                .anyMatch(component -> component.name.equals(field.name) && component.descriptor.equals(field.desc));
    }

    private static boolean isAccessor(ClassNode header, MethodNode method) {
        return header.recordComponents != null && header.recordComponents.stream()
                .anyMatch(component -> component.name.equals(method.name) && ("()" + component.descriptor).equals(method.desc));
    }

    private static String parameterKey(MethodNode method) {
        return method.name + method.desc.substring(0, method.desc.indexOf(')') + 1);
    }

    private String mapClass(String name, int depth) {
        String mapped = types.get(name);
        if (mapped != null) return mapped;
        var definition = classes.get(name);
        if (definition == null || depth >= 64 || name.equals("module-info")) return name;
        String outer = definition.outer;
        if (outer != null && name.startsWith(outer + "$") && classes.containsKey(outer)) {
            String suffix = name.substring(outer.length() + 1);
            if (definition.inner != null && (!Identifiers.sourceName(definition.inner)
                    || repeatsEnclosingName(outer, definition.inner))) {
                String inner = fresh("Type");
                suffix = suffix.endsWith(definition.inner) ? suffix.substring(0, suffix.length() - definition.inner.length()) + inner : inner;
                innerNames.put(name, inner);
            }
            mapped = mapClass(outer, depth + 1) + "$" + suffix;
        } else {
            int slash = name.lastIndexOf('/');
            String prefix = slash < 0 ? "" : mapPackageName(name.substring(0, slash)) + "/";
            String simple = name.substring(slash + 1);
            mapped = prefix + (simple.equals("package-info") || Identifiers.sourceName(simple) ? simple : fresh("Type"));
        }
        if (!name.equals(mapped)) {
            types.put(name, mapped);
            rows.add(Inspection.row("class", name, "", "", mapped));
        }
        return mapped;
    }

    private boolean repeatsEnclosingName(String outer, String inner) {
        for (int depth = 0; outer != null && depth < 64; depth++) {
            var definition = classes.get(outer);
            if (definition == null) return false;
            String simpleName = definition.inner == null ? outer.substring(outer.lastIndexOf('/') + 1) : definition.inner;
            if (inner.equals(simpleName)) return true;
            outer = definition.outer;
        }
        return false;
    }

    @Override public String mapPackageName(String name) {
        String mapped = packages.get(name);
        if (mapped != null) return mapped;
        if (!archivePackages.contains(name)) return name;
        String[] parts = name.split("/");
        String prefix = "";
        String result = "";
        for (String part : parts) {
            String before = prefix;
            String after = result;
            prefix += part;
            result = packages.get(prefix);
            if (result == null) {
                result = after + (Identifiers.sourceName(part) ? part : fresh("pkg"));
                if (!prefix.equals(result)) {
                    packages.put(prefix, result);
                    rows.add(Inspection.row("package", before + part, "", "", result));
                }
            }
            prefix += "/";
            result += "/";
        }
        return result.substring(0, result.length() - 1);
    }

    private String fresh(String prefix) {
        String name;
        do name = prefix + ++next; while (!reserved.add(fold(name)));
        return name;
    }

    private void rename(Map<Symbol, String> names, String kind, String owner, String name, String desc, String alias) {
        if (alias.equals(name)) return;
        var member = new Member(name, desc);
        names.put(new Symbol(owner, member), alias);
        (names == fieldNames ? aliasedFields : aliasedMethods).add(member);
        rows.add(Inspection.row(kind, owner, name, desc, alias));
    }

    @Override public String map(String name) { return types.getOrDefault(name, name); }

    @Override public String mapInnerClassName(String name, String owner, String innerName) {
        return innerNames.getOrDefault(name, innerName);
    }

    @Override public String mapRecordComponentName(String owner, String name, String descriptor) {
        return fieldNames.getOrDefault(new Symbol(owner, new Member(name, descriptor)), name);
    }

    @Override public String mapFieldName(String owner, String name, String descriptor) {
        return mappedMember(owner, new Member(name, descriptor), true);
    }

    @Override public String mapMethodName(String owner, String name, String descriptor) {
        if (name.startsWith("<") || methodNames.isEmpty()) return name;
        return mappedMember(owner, new Member(name, descriptor), false);
    }

    @Override public String mapInvokeDynamicMethodName(String name, String descriptor, Handle bootstrap, Object... args) {
        Type target = Type.getReturnType(descriptor);
        if (bootstrap.getTag() == H_INVOKESTATIC && !bootstrap.isInterface()
                && bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                && (bootstrap.getName().equals("metafactory") || bootstrap.getName().equals("altMetafactory"))
                && target.getSort() == Type.OBJECT && args.length >= 3
                && args[0] instanceof Type sam && sam.getSort() == Type.METHOD) {
            return mapMethodName(target.getInternalName(), name, sam.getDescriptor());
        }
        return name;
    }

    private String mappedMember(String owner, Member member, boolean field) {
        if (!(field ? aliasedFields : aliasedMethods).contains(member)) return member.name;
        String declaring = resolve(owner, member, field, new HashSet<>());
        return declaring == null ? member.name : (field ? fieldNames : methodNames)
                .getOrDefault(new Symbol(declaring, member), member.name);
    }

    private String resolve(String owner, Member member, boolean field, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return null;
        if (seen.size() > 128) return UNRESOLVED;
        var definition = classes.get(owner);
        if (definition == null && !owner.startsWith("[")) definition = external(owner);
        if (definition == null) return UNRESOLVED;
        if ((field ? definition.fields : definition.methods).contains(member)) return owner;
        if (!field) {
            String parent = resolve(definition.header.superName, member, false, seen);
            if (parent != null) return parent;
        }
        for (String name : definition.header.interfaces) {
            String match = resolve(name, member, field, seen);
            if (match != null) return match;
        }
        return field ? resolve(definition.header.superName, member, true, seen) : null;
    }

    private boolean hasInheritedMethod(ClassNode owner, String shape) {
        var pending = new java.util.ArrayDeque<>(owner.interfaces);
        if (owner.superName != null) pending.addFirst(owner.superName);
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String parent = pending.removeFirst();
            if (!seen.add(parent)) continue;
            if (seen.size() > 128) return true;
            var definition = classes.get(parent);
            if (definition == null) definition = external(parent);
            if (definition == null || definition.methodShapes.contains(shape)) return true;
            if (definition.header.superName != null) pending.addLast(definition.header.superName);
            pending.addAll(definition.header.interfaces);
        }
        return false;
    }

    private boolean hasArchiveOverride(ClassNode owner, String shape) {
        if ((owner.access & ACC_FINAL) != 0) return false;
        var pending = new java.util.ArrayDeque<>(children.getOrDefault(owner.name, List.of()));
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String child = pending.removeFirst();
            if (!seen.add(child)) continue;
            if (seen.size() > 128 || classes.get(child).methodShapes.contains(shape)) return true;
            pending.addAll(children.getOrDefault(child, List.of()));
        }
        return false;
    }

    private Definition external(String owner) {
        if (external.containsKey(owner)) return external.get(owner);
        if (external.size() >= 2048) return null;
        Definition definition = null;
        try {
            String path = owner + ".class";
            Archive.checkName(path);
            try (var standard = ClassLoader.getPlatformClassLoader().getResourceAsStream(path)) {
                if (standard != null) definition = declaration(new ClassReader(standard));
            }
            if (definition == null) {
                byte[] bytes = libraries.find(path);
                if (bytes != null) definition = declaration(new ClassReader(bytes));
            }
        } catch (IOException | RuntimeException | StackOverflowError unavailable) {
            // Keep the original reference if any class on its lookup path is unavailable.
        }
        external.put(owner, definition);
        return definition;
    }

    private static String fold(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT);
    }
}
