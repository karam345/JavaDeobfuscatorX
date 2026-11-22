package org.javadeobfuscatorx;

import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Resolves small decoder owners from the selected input view without loading a class. */
final class DecoderIndex implements Opcodes {
    record Owner(Decoder decoder, boolean inertInitialization) {}
    static final class Budget {
        private int steps = 8_000_000;
        boolean takeStep() {
            if (steps == 0) return false;
            steps--;
            return true;
        }
    }

    private final Archive archive;
    private final Map<String, Owner> owners = new HashMap<>();
    private final Budget budget = new Budget();
    private long remainingBytes = 32L * Archive.MIB;

    DecoderIndex(Archive archive) {
        this.archive = archive;
    }

    Owner find(String name) {
        if (owners.containsKey(name)) return owners.get(name);
        var entry = archive.selected.get(name);
        if (entry == null || owners.size() >= 4096) return null;
        Owner result = null;
        long bytes = entry.entry().getSize();
        if (bytes <= 64 * 1024 && bytes <= remainingBytes) {
            remainingBytes -= bytes;
            try {
                var node = Bytecode.parse(archive.read(entry.entry()));
                if ((node.access & ACC_PUBLIC) != 0 && (node.access & ACC_INTERFACE) == 0
                        && node.methods.stream().anyMatch(method -> (method.access & (ACC_PUBLIC | ACC_STATIC))
                        == (ACC_PUBLIC | ACC_STATIC) && method.desc.endsWith(")Ljava/lang/String;"))) {
                    Bytecode.verify(node);
                    boolean inert = "java/lang/Object".equals(node.superName) && node.interfaces.isEmpty()
                            && node.methods.stream().noneMatch(method -> method.name.equals("<clinit>"));
                    result = new Owner(new Decoder(node, budget), inert);
                }
            } catch (IOException | org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException | StackOverflowError invalid) {
                // The input inspection reports verification failures. Never evaluate an unverified owner.
            }
        }
        owners.put(name, result);
        return result;
    }
}
