package org.javadeobfuscatorx;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Exports {
    record Staged(int classes, Map<String, Integer> changes, List<String> rollbacks) {}
    record SourceRun(int files, String status, int code) {}

    static Staged stage(Archive archive, Path output, boolean rewrite) throws IOException {
        var changes = new TreeMap<String, Integer>();
        var rollbacks = new ArrayList<String>();
        var hierarchy = new Hierarchy(archive);
        int classes = 0;
        try (var zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (var entry : archive.selected.values()) {
                byte[] data = archive.read(entry.entry());
                try {
                    Bytecode.parse(data);
                } catch (RuntimeException | StackOverflowError invalid) {
                    rollbacks.add(Inspection.row(entry.name(), "Unparseable class, excluded from staged view"));
                    continue;
                }
                if (rewrite) {
                    var result = Bytecode.transform(data, hierarchy);
                    data = result.bytes();
                    if (result.rollback() != null) rollbacks.add(Inspection.row(entry.name(), result.rollback()));
                    result.changes().forEach((key, count) -> changes.merge(key, count, Integer::sum));
                }
                put(zip, entry.path(), data);
                classes++;
            }
        }
        return new Staged(classes, changes, rollbacks);
    }

    static void resources(Archive archive, Path output) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (String name : new TreeSet<>(archive.resources.keySet())) {
                put(zip, name, archive.read(archive.resources.get(name)));
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    static List<Path> dependencies(List<Path> inputs, Path work, Archive.Limits limits, int release) throws IOException {
        var dependencies = new ArrayList<Path>();
        long total = 0;
        for (Path input : inputs) {
            try (var archive = Archive.open(input, limits, release)) {
                for (var entry : archive.selected.values()) {
                    total += entry.entry().getSize();
                }
                if (total > limits.totalBytes()) throw new IOException("Combined classpath exceeds expanded byte limit.");
                Path stage = work.resolve("dependency-" + dependencies.size() + ".jar");
                stage(archive, stage, false);
                dependencies.add(stage);
            }
        }
        return dependencies;
    }

    static Path stageSources(Path input, Path work) throws IOException {
        if (!Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source directory is unavailable.");
        Path output = Files.createDirectory(work.resolve("provided-sources"));
        long total = 0;
        int entries = 0;
        try (var stream = Files.walk(input, 64)) {
            for (var iterator = stream.iterator(); iterator.hasNext(); ) {
                Path path = iterator.next();
                if (++entries > 50_000) throw new IOException("Source directory entry budget exceeded.");
                if (Files.isSymbolicLink(path)) throw new IOException("Source directory contains a symbolic link.");
                if (!path.toString().endsWith(".java") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (path.startsWith(work.getParent())) continue;
                String relative = input.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                Archive.checkName(relative);
                long size = Files.size(path);
                if (size > 4L * Archive.MIB || (total += size) > 128L * Archive.MIB) {
                    throw new IOException("Provided source bytes exceed the budget.");
                }
                Path target = output.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
            }
        }
        return output;
    }

    static SourceRun run(String mode, Path input, Path output, Path diagnostics, List<Path> libraries,
                         int timeout, int heap, int release, PrintStream progress) throws IOException, InterruptedException {
        Files.createDirectory(output);
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                java.io.File.separatorChar == '\\' ? "java.exe" : "java");
        var command = new ArrayList<>(List.of(executable.toString(), "-Xmx" + heap + "m", "-XX:ActiveProcessorCount=2",
                "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), DecompilerWorker.class.getName(),
                mode, input.toString(), output.toString(), diagnostics.toString(), Integer.toString(release)));
        libraries.forEach(path -> command.add(path.toString()));
        var builder = new ProcessBuilder(command);
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")) {
            builder.environment().remove(variable);
        }
        var process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        long nextUpdate = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        boolean finished = false;
        try {
            while (System.nanoTime() < deadline) {
                if (process.waitFor(1, TimeUnit.SECONDS)) {
                    finished = true;
                    break;
                }
                if (System.nanoTime() >= nextUpdate) {
                    progress.println(mode + " worker still running");
                    nextUpdate += TimeUnit.SECONDS.toNanos(15);
                }
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        int units = mode.equals("compile") ? 0 : sourceFiles(output).size();
        if (!finished) return new SourceRun(units, "timed out", 1);
        int code = process.exitValue();
        return new SourceRun(units, code == 0 ? "passed" : "failed or partial (worker exit " + code + ")", code);
    }

    static void compare(Path vineflower, Path cfr, Path report) throws IOException {
        var left = sourceFiles(vineflower);
        var right = sourceFiles(cfr);
        var names = new TreeSet<>(left.keySet());
        names.addAll(right.keySet());
        try (var writer = Files.newBufferedWriter(report)) {
            writer.write("source\tresult\tvineflower_sha256\tcfr_sha256\n");
            for (String name : names) {
                String first = hash(left.get(name));
                String second = hash(right.get(name));
                String status = first.isEmpty() ? "CFR only" : second.isEmpty() ? "Vineflower only"
                        : first.equals(second) ? "identical UTF-8 bytes" : "different UTF-8 bytes";
                writer.write(Inspection.row(name, status, first, second));
                writer.newLine();
            }
        }
    }

    private static Map<String, Path> sourceFiles(Path directory) throws IOException {
        var files = new TreeMap<String, Path>();
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.filter(file -> file.toString().endsWith(".java") && Files.isRegularFile(file)).toList()) {
                files.put(directory.relativize(path).toString().replace(java.io.File.separatorChar, '/'), path);
            }
        }
        return files;
    }

    private static String hash(Path path) throws IOException {
        if (path == null) return "";
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; ) digest.update(buffer, 0, read);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void table(Path output, String header, List<String> rows) throws IOException {
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write(header);
            writer.newLine();
            for (String row : rows) {
                writer.write(row);
                writer.newLine();
            }
        }
    }

    static void deleteWork(Path work) throws IOException {
        try (var stream = Files.walk(work)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
