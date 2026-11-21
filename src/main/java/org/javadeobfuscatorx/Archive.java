package org.javadeobfuscatorx;

import org.objectweb.asm.ClassReader;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class Archive implements AutoCloseable {
    static final int MIB = 1024 * 1024;
    private static final String VERSIONS = "META-INF/versions/";
    private static final Pattern WINDOWS_DEVICE = Pattern.compile(
            "(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(\\..*)?");

    record Limits(int entries, int entryBytes, long totalBytes, long fileBytes) {
        static Limits defaults() {
            return new Limits(40_000, 16 * MIB, 256L * MIB, 128L * MIB);
        }
    }

    record ClassEntry(ZipEntry entry, String name, int release, int major, int minor) {
        String path() {
            return name + ".class";
        }
    }

    final List<ZipEntry> entries = new ArrayList<>();
    final List<ClassEntry> classes = new ArrayList<>();
    final List<String> problems = new ArrayList<>();
    final Map<String, ZipEntry> resources = new HashMap<>();
    final Map<String, ClassEntry> selected = new TreeMap<>();
    final Manifest manifest;
    private final ZipFile zip;
    private final Limits limits;

    static Archive open(Path path, Limits limits, int release) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > limits.fileBytes()) {
            throw new IOException("Input must be a regular archive within the compressed size limit.");
        }
        return new Archive(new ZipFile(path.toFile()), limits, release);
    }

    private Archive(ZipFile zip, Limits limits, int release) throws IOException {
        this.zip = zip;
        this.limits = limits;
        try {
            if (zip.size() > limits.entries()) {
                throw new IOException("Archive entry count exceeds the limit.");
            }
            long total = 0;
            var names = new HashSet<String>();
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                checkName(entry.isDirectory() ? name.substring(0, name.length() - 1) : name);
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Duplicate or case-colliding ZIP entry.");
                }
                if (entry.getSize() < 0 || entry.getSize() > limits.entryBytes()
                        || (total += entry.getSize()) > limits.totalBytes()) {
                    throw new IOException("Archive expanded size exceeds the limit.");
                }
                if (entry.isDirectory() && entry.getSize() != 0) {
                    throw new IOException("ZIP directory contains data.");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                entries.add(entry);
                boolean classFile = name.endsWith(".class");
                byte[] bytes = checkedRead(entry, classFile);
                if (classFile) {
                    indexClass(entry, bytes);
                } else {
                    resources.put(name, entry);
                }
            }

            ZipEntry metadata = resources.get("META-INF/MANIFEST.MF");
            if (metadata != null && metadata.getSize() > MIB) {
                throw new IOException("Manifest exceeds 1 MiB.");
            }
            manifest = metadata == null ? new Manifest() : new Manifest(new ByteArrayInputStream(read(metadata)));
            boolean multiRelease = "true".equalsIgnoreCase(
                    manifest.getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE));
            for (ClassEntry candidate : classes) {
                if (candidate.release() > 0 && (!multiRelease || candidate.release() > release)) {
                    continue;
                }
                var previous = selected.get(candidate.name());
                if (previous == null || candidate.release() > previous.release()) {
                    selected.put(candidate.name(), candidate);
                } else if (candidate.release() == previous.release()) {
                    throw new IOException("Ambiguous class in the selected release view: " + candidate.name());
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                zip.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    private void indexClass(ZipEntry entry, byte[] bytes) {
        try {
            if (bytes.length < 10 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe
                    || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe) {
                throw new IllegalArgumentException("Invalid class header");
            }
            String path = entry.getName();
            int release = 0;
            if (path.startsWith(VERSIONS)) {
                int end = path.indexOf('/', VERSIONS.length());
                release = Integer.parseInt(path.substring(VERSIONS.length(), end));
                if (release < 9 || !path.substring(VERSIONS.length(), end).equals(Integer.toString(release))) {
                    throw new IllegalArgumentException("Invalid multi-release directory");
                }
                path = path.substring(end + 1);
            } else {
                path = classRootPath(path);
            }
            var reader = new ClassReader(bytes);
            String name = reader.getClassName();
            if (!path.equals(name + ".class") || name.indexOf('.') >= 0
                    || name.indexOf('[') >= 0 || name.indexOf(';') >= 0) {
                throw new IllegalArgumentException("Class name does not match its archive entry");
            }
            classes.add(new ClassEntry(entry, name, release, reader.readUnsignedShort(6),
                    reader.readUnsignedShort(4)));
        } catch (RuntimeException failure) {
            problems.add(entry.getName() + ": " + failure.getClass().getSimpleName());
        }
    }

    static String classRootPath(String path) {
        if (path.startsWith("BOOT-INF/classes/")) return path.substring("BOOT-INF/classes/".length());
        if (path.startsWith("WEB-INF/classes/")) return path.substring("WEB-INF/classes/".length());
        return path;
    }

    static void checkName(String path) throws IOException {
        if (path.isEmpty() || path.length() > 1024
                || path.codePoints().anyMatch(c -> Character.isISOControl(c) || "<>:\"\\|?*".indexOf(c) >= 0)) {
            throw new IOException("Unsafe ZIP or output entry name.");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.endsWith(".") || part.endsWith(" ") || part.length() > 200
                    || WINDOWS_DEVICE.matcher(part.toUpperCase(Locale.ROOT)).matches()) {
                throw new IOException("Unsafe ZIP or output path component.");
            }
        }
    }

    byte[] read(ZipEntry entry) throws IOException {
        return checkedRead(entry, true);
    }

    private byte[] checkedRead(ZipEntry entry, boolean collect) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            var bytes = collect ? new ByteArrayOutputStream((int) entry.getSize()) : null;
            var crc = new CRC32();
            var buffer = new byte[8192];
            long count = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                count += length;
                if (count > entry.getSize() || count > limits.entryBytes()) {
                    throw new IOException("Expanded entry exceeds declared size or limit.");
                }
                crc.update(buffer, 0, length);
                if (bytes != null) {
                    bytes.write(buffer, 0, length);
                }
            }
            if (count != entry.getSize() || crc.getValue() != entry.getCrc()) {
                throw new IOException("Invalid ZIP entry size or CRC.");
            }
            return bytes == null ? null : bytes.toByteArray();
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
