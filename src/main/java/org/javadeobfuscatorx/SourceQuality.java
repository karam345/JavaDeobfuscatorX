package org.javadeobfuscatorx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class SourceQuality {
    record Unit(Path path, int missingDeclarations, boolean syntax, boolean stub,
                boolean compilation, boolean unresolved, boolean missingDependency) {
        String issue() {
            if (path == null) return "missing source";
            var issues = new ArrayList<String>();
            if (missingDeclarations != 0) issues.add("missing declarations " + missingDeclarations);
            if (syntax) issues.add("syntax error");
            if (stub) issues.add("decompiler failure marker");
            if (compilation) issues.add("compilation error");
            if (unresolved) issues.add("unresolved symbol");
            if (missingDependency) issues.add("missing dependency");
            return issues.isEmpty() ? "no unit issues detected" : String.join(", ", issues);
        }

        int compare(Unit other) {
            if (path == null || other.path == null) return Boolean.compare(path == null, other.path == null);
            int result = Boolean.compare(syntax, other.syntax);
            if (result == 0) result = Integer.compare(missingDeclarations, other.missingDeclarations);
            if (result == 0) result = Boolean.compare(stub, other.stub);
            if (result == 0) result = Boolean.compare(compilation, other.compilation);
            if (result == 0) result = Boolean.compare(unresolved, other.unresolved);
            return result;
        }
    }

    private record Diagnostic(String category, String path) {}

    final Map<String, Unit> units = new LinkedHashMap<>();
    final int missing;
    final int syntaxErrors;
    final int placeholders;
    final int compilationErrors;
    final int unresolved;
    final int missingDependencies;
    final boolean checked;
    final boolean compiled;

    SourceQuality(Path source, Map<String, Set<String>> expected, Path syntaxReport, Path compileReport,
                  boolean parsedCompletely, boolean compiled) throws IOException {
        var files = Exports.sourceFiles(source);
        var types = new HashMap<String, Set<String>>();
        var diagnostics = new HashSet<Diagnostic>();
        load(syntaxReport, types, diagnostics);
        load(compileReport, types, diagnostics);
        checked = parsedCompletely;
        this.compiled = compiled;
        var paths = new TreeSet<>(expected.keySet());
        paths.addAll(files.keySet());
        int coverage = 0;
        int invalidSyntax = 0;
        int stubs = 0;
        int compile = 0;
        int unresolvedSymbols = 0;
        int dependency = 0;
        for (String name : paths) {
            Path file = files.get(name);
            var found = types.getOrDefault(name, Set.of());
            int absent = (int) expected.getOrDefault(name, Set.of()).stream().filter(type -> !found.contains(type)).count();
            if (absent != 0) coverage++;
            boolean syntax = diagnostics.contains(new Diagnostic("syntax", name));
            boolean compilation = diagnostics.contains(new Diagnostic("compilation", name));
            boolean unresolved = diagnostics.contains(new Diagnostic("unresolved-symbol", name));
            boolean missingDependency = diagnostics.contains(new Diagnostic("missing-dependency", name));
            boolean stub = file != null && hasFailureMarker(file);
            if (syntax) invalidSyntax++;
            if (compilation) compile++;
            if (unresolved) unresolvedSymbols++;
            if (missingDependency) dependency++;
            if (stub) stubs++;
            units.put(name, new Unit(file, absent, syntax, stub, compilation, unresolved, missingDependency));
        }
        missing = coverage;
        syntaxErrors = invalidSyntax;
        placeholders = stubs;
        compilationErrors = compile + (diagnostics.contains(new Diagnostic("compilation", "")) ? 1 : 0);
        unresolved = unresolvedSymbols + (diagnostics.contains(new Diagnostic("unresolved-symbol", "")) ? 1 : 0);
        missingDependencies = dependency + (diagnostics.contains(new Diagnostic("missing-dependency", "")) ? 1 : 0);
    }

    boolean recoveryNeeded() {
        return !checked || missing != 0 || syntaxErrors != 0 || placeholders != 0 || compilationErrors != 0 || unresolved != 0;
    }

    Set<String> recoveryUnits(Set<String> expected) {
        var needed = new TreeSet<String>();
        for (String name : expected) {
            var unit = units.get(name);
            if (unit == null || unit.path == null || unit.missingDeclarations != 0 || unit.syntax || unit.stub) needed.add(name);
        }
        return needed;
    }

    String summary() {
        return missing + " incomplete source units, " + syntaxErrors + " with syntax errors, "
                + placeholders + " with decompiler failure markers, " + compilationErrors + " with compilation errors, "
                + unresolved + " with unresolved symbols, " + missingDependencies + " with missing dependencies"
                + (checked ? "" : " (syntax pass incomplete or failed)");
    }

    static Map<String, Path> select(SourceQuality primary, SourceQuality alternative,
                                    Set<String> expected, boolean compileRequested) {
        var choices = new LinkedHashMap<String, Path>();
        boolean wholeAlternative = !alternative.recoveryNeeded() && compileRequested && alternative.compiled
                && !primary.compiled;
        primary.units.forEach((name, unit) -> {
            if (!wholeAlternative && unit.path != null) choices.put(name, unit.path);
        });
        alternative.units.forEach((name, unit) -> {
            Unit original = primary.units.get(name);
            if (unit.path != null && (wholeAlternative || expected.contains(name)
                    && (original == null || unit.compare(original) < 0))) choices.put(name, unit.path);
        });
        return choices;
    }

    static boolean copySelection(Map<String, Path> chosen, Map<String, Path> primary, Path output) throws IOException {
        if (chosen.equals(primary)) return false;
        Files.createDirectory(output);
        var taken = new HashSet<String>();
        for (var file : chosen.entrySet()) {
            String name = file.getKey();
            Archive.checkName(name);
            if (!taken.add(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
                    .toLowerCase(java.util.Locale.ROOT))) throw new IOException("Colliding selected source paths");
            Path target = output.resolve(name);
            Files.createDirectories(target.getParent());
            Files.copy(file.getValue(), target);
        }
        return true;
    }

    private static void load(Path path, Map<String, Set<String>> names, Set<Diagnostic> diagnostics) throws IOException {
        if (path == null || !Files.isRegularFile(path)) return;
        try (var rows = Files.lines(path)) {
            for (String row : (Iterable<String>) rows::iterator) {
                String[] values = row.split("\t", 4);
                if (values.length < 3) continue;
                if (values[0].equals("unit")) names.computeIfAbsent(values[1], ignored -> new HashSet<>()).add(values[2]);
                else diagnostics.add(new Diagnostic(values[0], values[1]));
            }
        }
    }

    private static boolean hasFailureMarker(Path path) throws IOException {
        try (var lines = Files.lines(path)) {
            return lines.map(String::strip).anyMatch(line -> line.startsWith("// $VF: Couldn't be decompiled")
                    || line.startsWith("// $VF: Unable to simplify")
                    || line.startsWith("/* Unable to fully structure code")
                    || line.startsWith("* Unable to fully structure code")
                    || line.startsWith("/* Exception decompiling")
                    || line.startsWith("* Exception decompiling")
                    || line.startsWith("* This method has failed to decompile."));
        }
    }
}
