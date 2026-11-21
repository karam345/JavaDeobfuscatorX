package org.javadeobfuscatorx;

import java.nio.file.Files;
import java.util.Locale;
import java.util.TreeMap;

public final class Benchmark {
    private Benchmark() {}

    public static void main(String[] arguments) throws Exception {
        int classes = arguments.length == 0 ? 1000 : Integer.parseInt(arguments[0]);
        if (classes < 1 || classes > 20_000) throw new IllegalArgumentException("Expected 1 to 20,000 classes");
        var entries = new TreeMap<String, byte[]>();
        for (int i = 0; i < classes; i++) {
            String name = "benchmark/Example" + i;
            entries.put(name + ".class", Fixtures.constants(name, i));
        }
        var directory = Files.createTempDirectory("jdx-benchmark-");
        try {
            var jar = Fixtures.jar(directory.resolve("input.jar"), entries);
            for (int iteration = 0; iteration < 4; iteration++) {
                long start = System.nanoTime();
                try (var archive = Archive.open(jar, Archive.Limits.defaults(), 21)) {
                    var inspection = new Inspection(archive, true);
                    if (inspection.verified != classes) throw new IllegalStateException("Fixture verification failed");
                    var result = Exports.stage(archive, directory.resolve("stage.jar"), true);
                    if (result.classes() != classes || !result.rollbacks().isEmpty()) {
                        throw new IllegalStateException("Fixture rewrite failed");
                    }
                    long elapsed = System.nanoTime() - start;
                    System.out.printf(Locale.ROOT, "%s %,d classes, %,d archive bytes, %.1f ms%n",
                            iteration == 0 ? "Warm-up" : "Measured", classes, Files.size(jar), elapsed / 1_000_000.0);
                }
            }
        } finally {
            Exports.deleteWork(directory);
        }
    }
}
