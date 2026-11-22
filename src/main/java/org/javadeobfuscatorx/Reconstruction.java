package org.javadeobfuscatorx;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class Reconstruction {
    record Candidate(Path path, int backendCode, SourceQuality quality) {
        boolean partial(boolean compileRequested) {
            return backendCode != 0 || quality.recoveryNeeded() || compileRequested && !quality.compiled;
        }
    }

    static boolean run(Main.Options options, Archive archive, boolean rewrite, Path destination, Path work,
                       List<Path> libraries, List<String> report, PrintStream progress)
            throws IOException, InterruptedException {
        progress.println(rewrite ? "Applying and verifying bytecode rewrite passes" : "Staging a class-only archive");
        Path stage = (rewrite ? destination : work).resolve("bytecode.jar");
        var staged = Exports.stage(archive, stage, rewrite, libraries);
        progress.println("Preserving archive resources");
        Exports.resources(archive, destination.resolve("resources.zip"));
        staged.changes().forEach((pattern, count) -> report.add("  Applied " + pattern + ": " + count));
        if (rewrite) {
            var strings = staged.strings();
            report.add("Rewrite skips or rollbacks: " + staged.rollbacks().size());
            report.add("String candidates detected: " + strings.detected());
            report.add("String candidates decoded: " + strings.decoded());
            report.add("Verified string replacements: " + strings.replaced());
            report.add("String candidates skipped: " + strings.skipped());
            report.add("String candidates failed or rolled back: " + strings.failed());
            if (staged.stringSites().size() < strings.detected()) report.add("String site diagnostics truncated");
            Exports.table(destination.resolve("string-rewrites.tsv"), "class\tmethod\tdescriptor\tinstruction\tstatus\tdetail\ttext",
                    staged.stringSites());
        }
        Exports.table(destination.resolve("rewrite-issues.tsv"), "class\treason", staged.rollbacks());
        if (staged.classes() == 0) {
            report.add("Decompilation skipped: no parseable classes in the selected view");
            return true;
        }

        var expected = staged.inventory().expected();
        int release = options.sourceRelease(archive);
        report.add("Source target: Java " + release);
        if (release > Runtime.version().feature()) {
            report.add("Syntax checking uses JDK " + Runtime.version().feature() + "; compiling this target requires JDK " + release);
        }
        report.add("Expected source units from class metadata: " + expected.size());
        boolean compare = options.command.equals("compare");
        String primaryName = compare || !options.engine.equals("cfr") ? "vineflower" : "cfr";
        Path primaryPath = destination.resolve(compare ? "vineflower" : "sources");
        var primary = decompile(options, primaryName, compare ? primaryName : "primary", stage, primaryPath,
                expected, destination, work, libraries, release, report, progress);
        Path selected = primaryPath;
        Candidate second = null;
        Map<String, Path> selection = Exports.sourceFiles(primaryPath);
        boolean partial = primary.partial(options.compile);
        if (compare || options.engine.equals("auto") && (primary.backendCode != 0 || primary.quality.recoveryNeeded())) {
            Path cfrPath = destination.resolve("cfr");
            Path cfrInput = stage;
            List<Path> cfrLibraries = libraries;
            var cfrExpected = expected;
            var needed = primary.quality.recoveryUnits(expected.keySet());
            if (!compare && !options.compile && !needed.isEmpty() && needed.size() < expected.size()) {
                var roots = new TreeSet<String>();
                var subset = new java.util.TreeMap<String, Set<String>>();
                for (String name : needed) {
                    roots.add(name.substring(0, name.length() - ".java".length()) + ".class");
                    subset.put(name, expected.get(name));
                }
                Path targets = work.resolve("cfr-targets.jar");
                if (Exports.subset(stage, targets, roots)) {
                    cfrInput = targets;
                    cfrLibraries = new ArrayList<>(libraries);
                    cfrLibraries.addFirst(stage);
                    cfrExpected = subset;
                    report.add("Targeted CFR fallback for " + needed.size() + " source units");
                }
            }
            second = decompile(options, "cfr", "cfr", cfrInput, cfrPath, cfrExpected, destination, work, cfrLibraries,
                    release, report, progress);
            Map<String, Path> original = selection;
            selection = SourceQuality.select(primary.quality, second.quality, expected.keySet(), options.compile);
            if (compare) {
                Exports.compare(primaryPath, cfrPath, destination.resolve("comparison.tsv"));
                partial |= second.partial(options.compile);
            }
            Path merged = work.resolve("selected");
            if ((!compare || options.project) && SourceQuality.copySelection(selection, original, merged)) {
                SourceQuality verified = assess(options, merged, expected, destination, work, libraries, release,
                        "selected", report, progress);
                boolean acceptable = verified.missing <= primary.quality.missing
                        && verified.syntaxErrors <= primary.quality.syntaxErrors
                        && verified.placeholders <= primary.quality.placeholders
                        && verified.compilationErrors <= primary.quality.compilationErrors
                        && verified.unresolved <= primary.quality.unresolved
                        && verified.missingDependencies <= primary.quality.missingDependencies
                        && (!primary.quality.checked || verified.checked)
                        && (!primary.quality.compiled || verified.compiled);
                if (acceptable) {
                    if (compare) {
                        selected = merged;
                    } else {
                        Files.move(primaryPath, destination.resolve("vineflower"));
                        Files.move(merged, primaryPath);
                        partial = verified.recoveryNeeded() || options.compile && !verified.compiled;
                        report.add("Selected sources include " + selection.values().stream()
                                .filter(path -> path.startsWith(cfrPath)).count() + " CFR source units");
                    }
                } else {
                    selection = original;
                    report.add("Kept primary source tree after verification of the combined output");
                }
            }
        }

        qualityTable(destination.resolve("source-quality.tsv"), expected.keySet(), primaryName, primary, second, selection);
        if (!options.compile) report.add("Source compilation: not requested");
        if (options.project) {
            progress.println("Reconstructing an offline Gradle project");
            ProjectExport.write(destination.resolve("project"), archive, selected, libraries, release);
            report.add("Gradle project: project/");
        }
        return partial;
    }

    private static Candidate decompile(Main.Options options, String engine, String label, Path input, Path sources,
                                       Map<String, Set<String>> expected, Path destination, Path work, List<Path> libraries,
                                       int release, List<String> report, PrintStream progress)
            throws IOException, InterruptedException {
        progress.println("Decompiling with " + engine);
        var run = Exports.run(engine, input, sources, destination.resolve(engine + "-diagnostics.tsv"), libraries,
                options.timeout, options.heap, release, progress);
        report.add(engine + ": " + run.files() + " Java source files (" + run.status() + ")");
        return new Candidate(sources, run.code(), assess(options, sources, expected, destination, work,
                libraries, release, label, report, progress));
    }

    private static SourceQuality assess(Main.Options options, Path sources, Map<String, Set<String>> expected,
                                        Path destination, Path work, List<Path> libraries, int release, String label,
                                        List<String> report, PrintStream progress) throws IOException, InterruptedException {
        progress.println("Checking source declarations and syntax (" + label + ")");
        Path syntaxReport = destination.resolve("syntax-" + label + ".tsv");
        var syntax = Exports.run("syntax", sources, work.resolve("syntax-" + label), syntaxReport, List.of(),
                options.timeout, options.heap, release, progress);
        Path compileReport = options.compile ? destination.resolve("javac-" + label + ".tsv") : null;
        boolean compiled = options.compile && compile(options, sources, destination, work, libraries, release,
                label, report, progress);
        var assessment = new SourceQuality(sources, expected, syntaxReport, compileReport, syntax.code() == 0, compiled);
        report.add("Source quality " + label + ": " + assessment.summary());
        return assessment;
    }

    static boolean compile(Main.Options options, Path input, Path destination, Path work, List<Path> libraries,
                           int release, String label, List<String> report, PrintStream progress)
            throws IOException, InterruptedException {
        progress.println("Checking source compilation with javac (" + label + ")");
        Path diagnostics = destination.resolve("javac-" + label + ".tsv");
        var result = Exports.run("compile", input, work.resolve("compiled-" + label), diagnostics,
                libraries, options.timeout, options.heap, release, progress);
        report.add("javac " + label + ": " + result.status());
        if (Files.isRegularFile(diagnostics)) {
            try (var lines = Files.lines(diagnostics)) {
                var categories = lines.map(line -> line.split("\t", 2)[0])
                        .collect(java.util.stream.Collectors.groupingBy(category -> category,
                                java.util.TreeMap::new, java.util.stream.Collectors.counting()));
                categories.forEach((category, count) -> report.add("  javac " + label + " " + category + ": " + count));
            }
        }
        return result.code() == 0;
    }

    private static void qualityTable(Path file, Set<String> expected, String primaryName, Candidate primary,
                                     Candidate secondary, Map<String, Path> selection) throws IOException {
        var names = new TreeSet<>(primary.quality.units.keySet());
        if (secondary != null) names.addAll(secondary.quality.units.keySet());
        var rows = new ArrayList<String>();
        for (String name : names) {
            var first = primary.quality.units.get(name);
            var second = secondary == null ? null : secondary.quality.units.get(name);
            Path chosen = selection.get(name);
            String backend = chosen == null ? "" : chosen.startsWith(primary.path) ? primaryName : "cfr";
            rows.add(Inspection.row(name, expected.contains(name) ? "expected" : "additional",
                    first == null ? "missing source" : first.issue(),
                    second == null ? "not attempted" : second.issue(), backend));
        }
        Exports.table(file, "source\tinventory\t" + primaryName + "\t"
                + (secondary == null ? "alternate" : "cfr") + "\tselected", rows);
    }
}
