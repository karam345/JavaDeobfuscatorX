package org.javadeobfuscatorx;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

final class Hierarchy {
    private static final String OBJECT = "java/lang/Object";
    private final Map<String, Header> headers = new HashMap<>();
    private final Archive archive;

    record Header(String parent, String[] interfaces, boolean isInterface) {}

    Hierarchy(Archive archive) {
        this.archive = archive;
    }

    String common(String left, String right) {
        if (assignable(left, right)) return left;
        if (assignable(right, left)) return right;
        if (left.startsWith("[") && right.startsWith("[")) {
            String a = component(left);
            String b = component(right);
            if (a != null && b != null) {
                String type = common(a, b);
                return "[" + (type.startsWith("[") ? type : "L" + type + ";");
            }
        }
        if (left.startsWith("[") || right.startsWith("[") || header(left).isInterface()
                || header(right).isInterface()) {
            return OBJECT;
        }
        String parent = left;
        var seen = new HashSet<String>();
        while ((parent = header(parent).parent()) != null && seen.add(parent)) {
            if (assignable(parent, right)) return parent;
        }
        throw new IllegalArgumentException("Inconsistent class hierarchy");
    }

    private boolean assignable(String target, String value) {
        if (target.equals(value) || OBJECT.equals(target)) return true;
        if (value.startsWith("[")) {
            if (target.equals("java/lang/Cloneable") || target.equals("java/io/Serializable")) return true;
            if (!target.startsWith("[")) return false;
            String a = component(target);
            String b = component(value);
            return a != null && b != null && assignable(a, b);
        }
        if (target.startsWith("[")) return false;
        var pending = new ArrayDeque<String>();
        var seen = new HashSet<String>();
        pending.add(value);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (current.equals(target)) return true;
            if (!seen.add(current)) continue;
            if (seen.size() > 256) throw new IllegalArgumentException("Class hierarchy exceeds analysis budget");
            var header = header(current);
            if (header.parent() != null) pending.addLast(header.parent());
            for (String name : header.interfaces()) pending.addLast(name);
        }
        return false;
    }

    private static String component(String array) {
        return switch (array.charAt(1)) {
            case '[' -> array.substring(1);
            case 'L' -> array.substring(2, array.length() - 1);
            default -> null;
        };
    }

    private Header header(String name) {
        return headers.computeIfAbsent(name, this::readHeader);
    }

    private Header readHeader(String name) {
        try {
            Archive.checkName(name + ".class");
            var entry = archive.selected.get(name);
            if (entry != null) return header(new ClassReader(archive.read(entry.entry())));
            try (var input = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                if (input != null) return header(new ClassReader(input));
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read hierarchy for " + name, failure);
        }
        throw new IllegalArgumentException("Missing hierarchy for " + name);
    }

    private static Header header(ClassReader reader) {
        return new Header(reader.getSuperName(), reader.getInterfaces(),
                (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
    }
}
