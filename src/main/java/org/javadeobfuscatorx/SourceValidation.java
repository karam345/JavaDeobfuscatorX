package org.javadeobfuscatorx;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class SourceValidation {
    static boolean run(boolean parseOnly, Path input, Path output, int release, List<Path> dependencies,
                       List<String> messages) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            messages.add(Inspection.row("environment", "", "no-jdk", "A full JDK is required"));
            return false;
        }
        var paths = Exports.sourceFiles(input).values();
        if (paths.isEmpty()) {
            messages.add(Inspection.row("environment", "", "no-sources", "No Java source files"));
            return false;
        }

        int runtime = Runtime.version().feature();
        if (!parseOnly && release > runtime) {
            messages.add(Inspection.row("environment", "", "unsupported-release",
                    "Compilation requires JDK " + release + " or newer; found " + runtime));
            return false;
        }
        var empty = Files.createDirectory(output.resolve("empty"));
        boolean modular = Files.isRegularFile(input.resolve("module-info.java"));
        String libraries = dependencies.isEmpty() ? empty.toString()
                : String.join(java.io.File.pathSeparator, dependencies.stream().map(Path::toString).toList());
        var options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "--release",
                Integer.toString(parseOnly ? Math.min(release, runtime) : release), "-encoding", "UTF-8",
                "--source-path", modular ? input.toString() : empty.toString(),
                "--class-path", modular ? empty.toString() : libraries,
                "-d", output.toString(), "-Xmaxerrs", "2000"));
        if (modular) options.addAll(List.of("--module-path", libraries));
        if (parseOnly && Math.min(release, runtime) == runtime) options.add("--enable-preview");
        var diagnosticSites = new TreeSet<String>();
        var dependencyHints = new TreeSet<String>();
        var invalidPaths = new TreeSet<String>();
        int[] errors = {0};
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostic -> {
                if (diagnostic.getKind() != Diagnostic.Kind.ERROR) return;
                errors[0]++;
                JavaFileObject unit = diagnostic.getSource();
                String path = unit == null ? "" : relative(input, unit);
                String code = diagnostic.getCode();
                String message = diagnostic.getMessage(Locale.ROOT);
                String category = parseOnly ? "syntax" : classify(code, message);
                if (parseOnly) invalidPaths.add(path);
                String site = Inspection.row(category, path, code, Long.toString(diagnostic.getLineNumber()), message);
                if (diagnosticSites.size() < 4000 && diagnosticSites.add(site)) {
                    messages.add(site);
                    if (category.equals("missing-dependency")) {
                        String hint = Minecraft.dependencyHint(message);
                        if (hint != null && dependencyHints.add(hint)) {
                            messages.add(Inspection.row("dependency-hint", path, "minecraft-api", "", hint));
                        }
                    }
                }
            }, options, null, manager.getJavaFileObjectsFromPaths(paths));
            if (!parseOnly) {
                boolean compiled = task.call();
                if (errors[0] >= 2000) messages.add(Inspection.row("environment", "", "diagnostic-limit",
                        "", "javac reached the 2000 error limit"));
                return compiled;
            }
            int characters = 0;
            for (CompilationUnitTree unit : task.parse()) {
                String path = relative(input, unit.getSourceFile());
                messages.add(Inspection.row("parsed", path, ""));
                String prefix = unit.getPackageName() == null ? "" : unit.getPackageName() + ".";
                var names = new TreeSet<String>();
                if (unit.getModule() != null) names.add("module-info");
                if (path.endsWith("package-info.java") && unit.getPackageName() != null) names.add(prefix + "package-info");
                for (Tree declaration : unit.getTypeDecls()) addTypes(declaration, prefix, names);
                for (String name : names) {
                    String row = Inspection.row("unit", path, name);
                    characters += row.length();
                    if (characters > 8 * Archive.MIB) {
                        messages.add(Inspection.row("environment", "", "declaration-budget", "Source inventory report exceeded its budget"));
                        return false;
                    }
                    messages.add(row);
                }
            }
            for (String path : invalidPaths) messages.add(Inspection.row("invalid-syntax", path, ""));
            messages.add(Inspection.row("parse-complete", "", ""));
            return invalidPaths.isEmpty();
        }
    }

    static String classify(String code, String text) {
        if (code.equals("compiler.err.doesnt.exist") || code.equals("compiler.err.module.not.found")
                || code.equals("compiler.err.cant.access") && text.contains("class file for") && text.contains("not found")) {
            return "missing-dependency";
        }
        if (code.startsWith("compiler.err.cant.resolve")) return "unresolved-symbol";
        return "compilation";
    }

    private static void addTypes(Tree declaration, String prefix, Set<String> names) {
        if (!(declaration instanceof ClassTree type) || names.size() >= 4096) return;
        if (type.getSimpleName().length() == 0) return;
        String name = prefix + type.getSimpleName();
        if (name.length() > 1024) return;
        names.add(name);
        for (Tree member : type.getMembers()) addTypes(member, name + "$", names);
    }

    static String path(Path relative) {
        return relative.toString().replace(java.io.File.separatorChar, '/');
    }

    private static String relative(Path root, JavaFileObject unit) {
        return path(root.relativize(Path.of(unit.toUri())));
    }
}
