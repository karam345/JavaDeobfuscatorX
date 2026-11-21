package org.javadeobfuscatorx;

import org.objectweb.asm.tree.ClassNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class SourceInventory {
    private record Owner(String internalName, boolean namedMember) {}

    private final Map<String, Owner> classes = new TreeMap<>();

    void add(ClassNode node) {
        Owner owner = new Owner(node.outerClass, false);
        for (var inner : node.innerClasses) {
            if (inner.name.equals(node.name) && inner.outerName != null) {
                owner = new Owner(inner.outerName, inner.innerName != null);
                break;
            }
        }
        classes.put(node.name, owner);
    }

    Map<String, Set<String>> expected() {
        var units = new TreeMap<String, Set<String>>();
        for (var entry : classes.entrySet()) {
            String name = entry.getKey();
            String root = outermost(name);
            var declarations = units.computeIfAbsent(root + ".java", ignored -> new TreeSet<>());
            declarations.add(root.replace('/', '.'));
            if (!name.equals(root) && declaresMember(name, root)) declarations.add(name.replace('/', '.'));
        }
        return units;
    }

    private String outermost(String name) {
        String current = name;
        var seen = new HashSet<String>();
        for (int depth = 0; depth < 64 && seen.add(current); depth++) {
            String outer = classes.get(current).internalName();
            if (outer == null || !classes.containsKey(outer)) return current;
            current = outer;
        }
        return name;
    }

    private boolean declaresMember(String name, String root) {
        while (!name.equals(root)) {
            Owner owner = classes.get(name);
            if (!owner.namedMember() || !name.startsWith(owner.internalName() + "$")) return false;
            name = owner.internalName();
        }
        return true;
    }
}
