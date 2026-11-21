package org.javadeobfuscatorx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

final class ProjectExport {
    static void write(Path root, Archive archive, Path sources, List<Path> libraries, int release) throws IOException {
        Path javaSources = Files.createDirectories(root.resolve("src/main/java"));
        for (var source : Exports.sourceFiles(sources).entrySet()) {
            Path target = javaSources.resolve(source.getKey());
            Files.createDirectories(target.getParent());
            Files.copy(source.getValue(), target);
        }
        Path resources = Files.createDirectories(root.resolve("src/main/resources"));
        var skipped = new ArrayList<String>();
        var paths = new HashMap<String, Entry>();
        for (String name : new TreeSet<>(archive.resources.keySet())) {
            String relative = Archive.classRootPath(name);
            Archive.checkName(relative);
            if (signature(relative)) {
                skipped.add(Inspection.row(name, "Original archive signature retained in resources.zip only"));
                continue;
            }
            if (!claim(paths, relative)) {
                skipped.add(Inspection.row(name, "Conflicting resource path; original retained in resources.zip"));
                continue;
            }
            Path target = resources.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, archive.read(archive.resources.get(name)), java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        if (!libraries.isEmpty()) {
            Path libs = Files.createDirectory(root.resolve("libs"));
            for (Path library : libraries) {
                Path folder = Files.createDirectory(libs.resolve(library.getParent().getFileName()));
                Files.copy(library, folder.resolve(library.getFileName()));
            }
        }

        writeManifest(archive.manifest, root.resolve("reconstructed-manifest.mf"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'reconstructed'\n");
        Files.writeString(root.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }

                def localLibraries = fileTree(dir: 'libs', include: '**/*.jar')
                def modular = file('src/main/java/module-info.java').isFile()

                dependencies {
                    compileOnly localLibraries
                }

                tasks.withType(JavaCompile).configureEach {
                    options.release = %d
                    options.encoding = 'UTF-8'
                    options.compilerArgs.add('-proc:none')
                    if (modular && !localLibraries.isEmpty()) {
                        options.compilerArgs.addAll(['--module-path', localLibraries.asPath])
                        classpath = files()
                    }
                }

                tasks.named('processResources') {
                    exclude 'META-INF/MANIFEST.MF'
                }

                tasks.named('jar', Jar) {
                    manifest.from('reconstructed-manifest.mf')
                }
                """.formatted(release));
        Files.writeString(root.resolve("README.md"), """
                # Reconstructed project

                Review the generated Java and diagnostics before building. Run with Gradle and a JDK that
                supports the source release in build.gradle. The project has no remote repositories.
                Place additional local compile dependencies in libs/.

                Loader-specific build logic, original Gradle plugins and Minecraft mapping namespaces
                must be configured separately when required. Original resources and signatures are available
                in the adjacent resources.zip. The reconstructed project cannot preserve the JAR signature.
                """);
        Exports.table(root.resolve("resource-issues.tsv"), "original_path\treason", skipped);
    }

    private record Entry(String name, boolean file) {}

    private static boolean claim(Map<String, Entry> paths, String path) {
        var prefixes = new ArrayList<Entry>();
        for (int split = path.indexOf('/'); split >= 0; split = path.indexOf('/', split + 1)) {
            prefixes.add(new Entry(path.substring(0, split), false));
        }
        prefixes.add(new Entry(path, true));
        for (Entry entry : prefixes) {
            Entry existing = paths.get(key(entry.name));
            if (existing != null && (existing.file || entry.file || !existing.name.equals(entry.name))) return false;
        }
        for (Entry entry : prefixes) paths.putIfAbsent(key(entry.name), entry);
        return true;
    }

    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static boolean signature(String path) {
        String name = path.toUpperCase(Locale.ROOT);
        if (!name.startsWith("META-INF/")) return false;
        name = name.substring("META-INF/".length());
        return !name.contains("/") && (name.startsWith("SIG-") || name.endsWith(".SF") || name.endsWith(".RSA")
                || name.endsWith(".DSA") || name.endsWith(".EC"));
    }

    private static void writeManifest(Manifest original, Path output) throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (String name : List.of("Main-Class", "Automatic-Module-Name", "MixinConfigs", "FMLModType")) {
            String value = original.getMainAttributes().getValue(name);
            if (value != null && value.length() <= 4096 && value.codePoints().noneMatch(Character::isISOControl)) {
                attributes.putValue(name, value);
            }
        }
        try (var stream = Files.newOutputStream(output)) {
            manifest.write(stream);
        }
    }
}
