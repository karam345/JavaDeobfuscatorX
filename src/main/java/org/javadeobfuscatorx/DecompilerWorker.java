package org.javadeobfuscatorx;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

public final class DecompilerWorker {
    private static final int DIAGNOSTICS_LIMIT = 100;

    private DecompilerWorker() {}

    public static void main(String[] args) throws IOException {
        String mode = args[0];
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);
        Path diagnostics = Path.of(args[3]);
        List<Path> dependencies = Arrays.stream(args).skip(5).map(Path::of).toList();
        var messages = new ArrayList<String>();
        int code = 0;
        try {
            if (mode.equals("compile")) {
                code = compile(input, output, args[4], dependencies, messages) ? 0 : 1;
            } else {
                var saver = new SourceSaver(output, messages);
                switch (mode) {
                    case "vineflower" -> vineflower(input, dependencies, saver);
                    case "cfr" -> cfr(input, dependencies, saver);
                    default -> throw new IllegalArgumentException("Unknown worker mode");
                }
                if (saver.files.isEmpty() || !messages.isEmpty()) code = 1;
            }
        } catch (RuntimeException | StackOverflowError failure) {
            message(messages, "worker failed", failure.toString());
            code = 1;
        }
        Files.write(diagnostics, messages, StandardCharsets.UTF_8);
        if (code != 0) System.exit(code);
    }

    private static void vineflower(Path input, List<Path> dependencies, SourceSaver saver) {
        var preferences = Map.<String, Object>of(
                IFernflowerPreferences.THREADS, "1",
                IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "0",
                IFernflowerPreferences.INCLUDE_JAVA_RUNTIME, "0",
                IFernflowerPreferences.SKIP_EXTRA_FILES, "1",
                IFernflowerPreferences.REMOVE_SYNTHETIC, "0",
                IFernflowerPreferences.REMOVE_BRIDGE, "1",
                IFernflowerPreferences.RENAME_ENTITIES, "0",
                IFernflowerPreferences.ASCII_STRING_CHARACTERS, "1",
                IFernflowerPreferences.DUMP_EXCEPTION_ON_ERROR, "0");
        var logger = new IFernflowerLogger() {
            @Override
            public void writeMessage(String text, Severity severity) {
                if (severity == Severity.ERROR) message(saver.messages, "Vineflower", text);
            }

            @Override
            public void writeMessage(String text, Severity severity, Throwable failure) {
                writeMessage(text + ": " + failure.getClass().getSimpleName(), severity);
            }
        };
        var decompiler = new Fernflower(saver, preferences, logger);
        try {
            decompiler.addSource(input.toFile());
            for (Path dependency : dependencies) decompiler.addLibrary(dependency.toFile());
            decompiler.decompileContext();
        } finally {
            decompiler.clearContext();
        }
    }

    private static void cfr(Path input, List<Path> dependencies, SourceSaver saver) throws IOException {
        try (var jar = new ZipFile(input.toFile()); var libraries = new Libraries(dependencies)) {
            var source = new ClassFileSource() {
                @Override
                public void informAnalysisRelativePathDetail(String path, String classPath) {}

                @Override
                public Collection<String> addJar(String path) {
                    return input.toString().equals(path) ? jar.stream().map(entry -> entry.getName()).toList() : List.of();
                }

                @Override
                public String getPossiblyRenamedPath(String path) {
                    return path;
                }

                @Override
                public Pair<byte[], String> getClassFileContent(String path) throws IOException {
                    Archive.checkName(path);
                    if (!path.endsWith(".class")) throw new IOException("Expected a class entry");
                    byte[] data = read(jar, path);
                    if (data == null) {
                        for (ZipFile library : libraries.jars) {
                            data = read(library, path);
                            if (data != null) break;
                        }
                    }
                    if (data == null) {
                        try (var standard = ClassLoader.getPlatformClassLoader().getResourceAsStream(path)) {
                            if (standard != null) data = standard.readAllBytes();
                        }
                    }
                    if (data == null) throw new IOException("Class unavailable: " + path);
                    return Pair.make(data, path);
                }
            };
            var output = new OutputSinkFactory() {
                @Override
                public List<SinkClass> getSupportedSinks(SinkType type, Collection<SinkClass> classes) {
                    return switch (type) {
                        case JAVA -> List.of(SinkClass.DECOMPILED, SinkClass.DECOMPILED_MULTIVER);
                        case EXCEPTION -> List.of(SinkClass.EXCEPTION_MESSAGE, SinkClass.STRING);
                        default -> List.of(SinkClass.STRING);
                    };
                }

                @Override
                public <T> Sink<T> getSink(SinkType type, SinkClass format) {
                    if (type == SinkType.EXCEPTION) return value -> {
                        if (value instanceof SinkReturns.ExceptionMessage error) {
                            message(saver.messages, "CFR", error.getMessage() + ": " + error.getThrownException());
                        } else message(saver.messages, "CFR", value.toString());
                    };
                    if (type != SinkType.JAVA) return value -> {};
                    return value -> {
                        var unit = (SinkReturns.Decompiled) value;
                        String packagePath = unit.getPackageName().replace('.', '/');
                        String path = (packagePath.isEmpty() ? "" : packagePath + "/") + unit.getClassName() + ".java";
                        saver.write(path, unit.getJava());
                    };
                }
            };
            new CfrDriver.Builder().withClassFileSource(source).withOutputSink(output)
                    .withOptions(Map.of("silent", "true")).build().analyse(List.of(input.toString()));
        }
    }

    private static byte[] read(ZipFile jar, String name) throws IOException {
        var entry = jar.getEntry(name);
        if (entry == null) return null;
        try (var stream = jar.getInputStream(entry)) {
            return stream.readAllBytes();
        }
    }

    private static boolean compile(Path input, Path output, String release, List<Path> dependencies,
                                   List<String> messages) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            message(messages, "javac", "A full JDK is required");
            return false;
        }
        List<Path> paths;
        try (var stream = Files.walk(input)) {
            paths = stream.filter(path -> path.toString().endsWith(".java") && Files.isRegularFile(path))
                    .sorted().toList();
        }
        if (paths.isEmpty()) {
            message(messages, "javac", "No Java source files");
            return false;
        }
        var empty = Files.createDirectory(output.resolve("empty"));
        var options = List.of("-proc:none", "-implicit:none", "--release", release, "-encoding", "UTF-8",
                "--source-path", empty.toString(), "--class-path", dependencies.isEmpty() ? empty.toString()
                : String.join(java.io.File.pathSeparator, dependencies.stream().map(Path::toString).toList()),
                "-d", output.toString());
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            return compiler.getTask(null, manager, diagnostic -> {
                JavaFileObject unit = diagnostic.getSource();
                String location = unit == null ? "" : Path.of(unit.toUri()).getFileName() + ":" + diagnostic.getLineNumber();
                message(messages, diagnostic.getKind() + " " + location, diagnostic.getMessage(Locale.ROOT));
            }, options, null, manager.getJavaFileObjectsFromPaths(paths)).call();
        }
    }

    private static void message(List<String> messages, String label, String text) {
        if (messages.size() < DIAGNOSTICS_LIMIT) {
            messages.add(Inspection.row(label, text.substring(0, Math.min(text.length(), 1000))));
        }
    }

    private static final class Libraries implements AutoCloseable {
        final List<ZipFile> jars = new ArrayList<>();

        Libraries(List<Path> paths) throws IOException {
            try {
                for (Path path : paths) jars.add(new ZipFile(path.toFile()));
            } catch (IOException failure) {
                try {
                    close();
                } catch (IOException closing) {
                    failure.addSuppressed(closing);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (ZipFile jar : jars) {
                try {
                    jar.close();
                } catch (IOException closing) {
                    if (failure == null) failure = closing;
                    else failure.addSuppressed(closing);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static final class SourceSaver implements IResultSaver {
        private final Path root;
        private final List<String> messages;
        private final HashSet<String> files = new HashSet<>();
        private long bytes;

        SourceSaver(Path root, List<String> messages) {
            this.root = root;
            this.messages = messages;
        }

        private synchronized void write(String name, String source) {
            if (source == null || !name.endsWith(".java")) return;
            try {
                Archive.checkName(name);
                if (source.length() > 4 * Archive.MIB) throw new IOException("Source unit exceeds character limit");
                if (!files.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate output entry");
                byte[] content = source.getBytes(StandardCharsets.UTF_8);
                bytes += content.length;
                if (files.size() > 40_000 || bytes > 128L * Archive.MIB) {
                    throw new IOException("Source export exceeds its budget");
                }
                Path destination = root.resolve(name).normalize();
                if (!destination.startsWith(root)) throw new IOException("Unsafe source path");
                Files.createDirectories(destination.getParent());
                Files.write(destination, content, StandardOpenOption.CREATE_NEW);
            } catch (IOException failure) {
                message(messages, "source output skipped", name + ": " + failure.getMessage());
            }
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entry, String source, int[] mapping) {
            write(entry, source);
        }

        @Override
        public void saveClassEntry(String path, String archive, String qualifiedName, String entry, String source) {
            write(entry, source);
        }

        @Override public void saveFolder(String path) {}
        @Override public void copyFile(String source, String path, String entry) {}
        @Override public void createArchive(String path, String archive, Manifest manifest) {}
        @Override public void saveDirEntry(String path, String archive, String entry) {}
        @Override public void copyEntry(String source, String path, String archive, String entry) {}
        @Override public void closeArchive(String path, String archive) {}
    }
}
