package org.javadeobfuscatorx;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Main {
    private static final Set<String> COMMANDS = Set.of("analyze", "detect", "report", "decompile",
            "deobfuscate", "compare", "verify", "help");

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream output, PrintStream progress) {
        try {
            var options = Options.parse(args);
            if (options.command.equals("help")) {
                output.println("""
                        JavaDeobfuscatorX
                        jdx <analyze|detect|report|decompile|deobfuscate|compare|verify|help> [input.jar] [options]

                          --out PATH           New output directory (default: jdx-out/<name>-<mode>)
                          --release N          Select multi-release view (8–25, default 21)
                          --compile            Compile generated source with javac (release 8–21)
                          --sources PATH       Verify explicit Java source directory (verify)
                          --classpath JAR      Local dependency JAR (repeat up to 16 times)
                          --engine NAME        auto, vineflower or cfr (default auto)
                          --timeout SECONDS    Per worker (1–1800, default 180)
                          --worker-heap MIB    Per worker (128–2048, default 512)
                          --max-entries N      ZIP entry limit (default 40000)
                          --max-entry-mib N    Expanded bytes per entry (default 16 MiB)
                          --max-total-mib N    Total expanded archive bytes (default 256 MiB)
                          --max-file-mib N     Compressed archive file bytes (default 128 MiB)

                        Examples:
                          jdx deobfuscate plugin.jar --compile --classpath paper-api.jar
                          jdx verify --sources reconstructed/src --out verification
                        Exit codes: 0 successful, 2 input/usage/I/O error, 3 partial or failed verification.
                        """.stripTrailing());
                return 0;
            }
            Archive archive = null;
            try {
                if (options.input != null) {
                    progress.println("Inspecting archive entries and checking ZIP integrity");
                    archive = Archive.open(options.input, options.limits, options.release);
                }
                return process(options, archive, output, progress);
            } finally {
                if (archive != null) archive.close();
            }
        } catch (IOException | IllegalArgumentException failure) {
            progress.println("Error: " + Inspection.row(String.valueOf(failure.getMessage())));
            return 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            progress.println("Interrupted");
            return 2;
        }
    }

    private static int process(Options options, Archive archive, PrintStream output, PrintStream progress)
            throws IOException, InterruptedException {
        boolean reconstruct = Set.of("decompile", "deobfuscate", "compare").contains(options.command);
        boolean rewrite = options.command.equals("deobfuscate");
        boolean verify = reconstruct || options.command.equals("verify");
        Inspection inspection = null;
        if (archive != null) {
            progress.println(verify ? "Inspecting bytecode and running static verification" : "Inspecting bytecode and metadata");
            inspection = new Inspection(archive, verify);
        }
        Files.createDirectories(options.output.getParent());
        Path destination = Files.createDirectory(options.output).toRealPath();
        var report = new ArrayList<String>();
        report.add("JavaDeobfuscatorX | " + options.command);
        report.add("Input: " + (options.input == null ? "(source only)" : Inspection.row(options.input.getFileName().toString())));
        boolean partial = false;
        if (inspection != null) {
            describe(options, archive, inspection, destination, report);
            partial = !inspection.problems.isEmpty() || inspection.parsed == 0;
        }

        if (reconstruct || options.sources != null) {
            Path work = Files.createTempDirectory(destination, ".work-");
            try {
                var libraries = Exports.dependencies(options.classpath, work, options.limits, options.release);
                if (reconstruct && archive != null) {
                    partial |= reconstruct(options, archive, rewrite, destination, work, libraries, report, progress);
                } else if (options.sources != null) {
                    progress.println("Staging supplied source files");
                    Path sources = Exports.stageSources(options.sources, work);
                    partial |= !compile(options, sources, destination, work, libraries, "provided", report, progress);
                }
            } finally {
                Exports.deleteWork(work);
            }
        }
        progress.println("Writing report");
        report.add("Outcome: " + (partial ? "partial or failed verification" : "completed"));
        report.add("Output: " + destination);
        Files.write(destination.resolve("report.txt"), report);
        report.forEach(output::println);
        return partial ? 3 : 0;
    }

    private static void describe(Options options, Archive archive, Inspection inspection, Path destination,
                                 List<String> report) throws IOException {
        report.add("Archive files: " + archive.entries.size() + " (" + archive.resources.size() + " resources)");
        report.add("Class entries with recognized headers: " + archive.classes.size());
        report.add("Classfile major.minor versions: " + String.join(", ", inspection.versions));
        report.add("Effective Java release view: " + options.release);
        report.add("Selected classes parsed: " + inspection.parsed + "/" + archive.selected.size());
        if (Set.of("decompile", "deobfuscate", "compare", "verify").contains(options.command)) {
            report.add("Selected input classes passing ASM structural and basic data-flow checks: " + inspection.verified);
        }
        report.add("Obfuscator attribution: " + (inspection.attribution.isEmpty() ? "unknown" : "metadata evidence only"));
        inspection.attribution.forEach(item -> report.add("  " + Inspection.row(item)));
        inspection.findings.forEach((signal, count) -> report.add("  " + signal + ": " + count));
        report.add("Literal Base64 payloads decoded as UTF-8: " + inspection.strings.size());
        report.add("Identifier suggestions: " + inspection.names.size());
        if (inspection.evidenceTruncated) report.add("Evidence table limit reached; some rows were omitted");
        report.add("Class issues: " + inspection.problems.size());
        inspection.minecraft.signals.forEach(signal -> report.add("  Minecraft: " + Inspection.row(signal)));
        Exports.table(destination.resolve("minecraft.tsv"), "descriptor\tkey\tvalue", inspection.minecraft.metadata);
        Exports.table(destination.resolve("mappings.tsv"), "kind\tclass\tmember\tdescriptor\tsuggestion\tconfidence", inspection.names);
        Exports.table(destination.resolve("references.tsv"), "from_class\tto_class", inspection.references);
        Exports.table(destination.resolve("strings.tsv"), "class\tmethod\tdescriptor\tcodec\ttext", inspection.strings);
        Exports.table(destination.resolve("class-issues.tsv"), "issue",
                inspection.problems.stream().map(problem -> Inspection.row(problem)).toList());
    }

    private static boolean reconstruct(Options options, Archive archive, boolean rewrite, Path destination,
                                       Path work, List<Path> libraries, List<String> report, PrintStream progress)
            throws IOException, InterruptedException {
        progress.println(rewrite ? "Applying and checking conservative bytecode rewrites" : "Staging a class-only archive");
        Path stage = (rewrite ? destination : work).resolve("bytecode.jar");
        var staged = Exports.stage(archive, stage, rewrite);
        progress.println("Preserving archive resources");
        Exports.resources(archive, destination.resolve("resources.zip"));
        staged.changes().forEach((pattern, count) -> report.add("  Applied " + pattern + ": " + count));
        if (rewrite) report.add("Rewrite skips or rollbacks: " + staged.rollbacks().size());
        Exports.table(destination.resolve("rewrite-issues.tsv"), "class\treason", staged.rollbacks());
        if (staged.classes() == 0) {
            report.add("Decompilation skipped: no parseable classes in the selected view");
            return true;
        }

        boolean comparison = options.command.equals("compare");
        boolean vineflower = comparison || !options.engine.equals("cfr");
        Path primary = destination.resolve(comparison ? "vineflower" : "sources");
        var first = decompile(options, vineflower ? "vineflower" : "cfr", stage, primary, destination, libraries, report, progress);
        boolean failed = first.code() != 0;
        if (comparison || failed && options.engine.equals("auto")) {
            Path alternative = destination.resolve("cfr");
            var second = decompile(options, "cfr", stage, alternative, destination, libraries, report, progress);
            if (comparison) {
                Exports.compare(primary, alternative, destination.resolve("comparison.tsv"));
                failed |= second.code() != 0;
                if (options.compile) failed |= !compile(options, alternative, destination, work, libraries, "cfr", report, progress);
            } else if (second.code() == 0) {
                failed = false;
                primary = alternative;
                report.add("CFR fallback completed; primary output retained for review");
            }
        }
        if (options.compile) {
            failed |= !compile(options, primary, destination, work, libraries, comparison ? "vineflower" : "primary", report, progress);
        } else {
            report.add("Source compilation: not requested");
        }
        return failed;
    }

    private static Exports.SourceRun decompile(Options options, String engine, Path input, Path sources, Path destination,
                                               List<Path> libraries, List<String> report, PrintStream progress)
            throws IOException, InterruptedException {
        progress.println("Decompiling with " + engine);
        var result = Exports.run(engine, input, sources, destination.resolve(engine + "-diagnostics.tsv"), libraries,
                options.timeout, options.heap, options.release, progress);
        report.add(engine + ": " + result.files() + " Java source files (" + result.status() + ")");
        return result;
    }

    private static boolean compile(Options options, Path input, Path destination, Path work, List<Path> libraries,
                                   String label, List<String> report, PrintStream progress) throws IOException, InterruptedException {
        progress.println("Checking source compilation with javac (" + label + ")");
        var result = Exports.run("compile", input, work.resolve("compiled-" + label),
                destination.resolve("javac-" + label + ".tsv"), libraries,
                options.timeout, options.heap, options.release, progress);
        report.add("javac " + label + ": " + result.status());
        return result.code() == 0;
    }

    private static final class Options {
        final String command;
        Path input;
        Path output;
        Path sources;
        final List<Path> classpath = new ArrayList<>();
        boolean compile;
        String engine = "auto";
        int release = 21;
        int timeout = 180;
        int heap = 512;
        Archive.Limits limits = Archive.Limits.defaults();

        Options(String command) {
            this.command = command;
        }

        static Options parse(String[] args) {
            String command = args.length == 0 || args[0].equals("--help") || args[0].equals("-h") ? "help" : args[0];
            if (!COMMANDS.contains(command)) throw new IllegalArgumentException("Unknown command. Run jdx help.");
            var options = new Options(command);
            if (command.equals("help")) return options;
            var seen = new java.util.HashSet<String>();
            int entries = options.limits.entries();
            int entryMiB = options.limits.entryBytes() / Archive.MIB;
            long expanded = options.limits.totalBytes() / Archive.MIB;
            long compressed = options.limits.fileBytes() / Archive.MIB;
            for (int i = 1; i < args.length; i++) {
                String flag = args[i];
                if (!flag.startsWith("--")) {
                    if (options.input != null) throw new IllegalArgumentException("Specify exactly one input archive.");
                    options.input = absolute(flag);
                    continue;
                }
                if (!flag.equals("--classpath") && !seen.add(flag)) throw new IllegalArgumentException("Duplicate option " + flag);
                if (flag.equals("--compile")) {
                    options.compile = true;
                    continue;
                }
                if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for " + flag);
                String value = args[i];
                switch (flag) {
                    case "--out" -> options.output = absolute(value);
                    case "--sources" -> options.sources = absolute(value);
                    case "--classpath" -> options.classpath.add(absolute(value));
                    case "--engine" -> options.engine = value;
                    case "--release" -> options.release = integer(flag, value, 8, 25);
                    case "--timeout" -> options.timeout = integer(flag, value, 1, 1800);
                    case "--worker-heap" -> options.heap = integer(flag, value, 128, 2048);
                    case "--max-entries" -> entries = integer(flag, value, 1, 100_000);
                    case "--max-entry-mib" -> entryMiB = integer(flag, value, 1, 32);
                    case "--max-total-mib" -> expanded = integer(flag, value, 1, 1024);
                    case "--max-file-mib" -> compressed = integer(flag, value, 1, 256);
                    default -> throw new IllegalArgumentException("Unknown option " + flag);
                }
            }
            if (options.input == null && options.sources == null) throw new IllegalArgumentException("Specify an input archive or verify --sources.");
            boolean generatesSources = Set.of("decompile", "deobfuscate", "compare").contains(command);
            if (options.sources != null && !command.equals("verify")
                    || options.compile && !generatesSources
                    || !options.classpath.isEmpty() && !(generatesSources || options.sources != null)
                    || options.classpath.size() > 16
                    || seen.contains("--engine") && !Set.of("decompile", "deobfuscate").contains(command)
                    || !Set.of("auto", "vineflower", "cfr").contains(options.engine)
                    || (options.sources != null || options.compile) && options.release > 21) {
                throw new IllegalArgumentException("Options are incompatible with this command or exceed its limits. Run jdx help.");
            }
            options.limits = new Archive.Limits(entries, entryMiB * Archive.MIB,
                    expanded * Archive.MIB, compressed * Archive.MIB);
            if (options.output == null) {
                Path source = options.input == null ? options.sources : options.input;
                String basename = source.getFileName() == null ? "input" : source.getFileName().toString()
                        .replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase(Locale.ROOT);
                options.output = absolute("jdx-out").resolve(basename + "-" + command);
            }
            if (options.output.getParent() == null) throw new IllegalArgumentException("Output must be a new directory.");
            return options;
        }

        private static int integer(String flag, String value, int min, int max) {
            int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(flag + " expects an integer");
            }
            if (parsed < min || parsed > max) throw new IllegalArgumentException(flag + " expects " + min + " to " + max);
            return parsed;
        }

        private static Path absolute(String value) {
            return Path.of(value).toAbsolutePath().normalize();
        }
    }
}
