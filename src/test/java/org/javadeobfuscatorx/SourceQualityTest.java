package org.javadeobfuscatorx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SourceQualityTest {
    @TempDir Path temp;

    @Test
    void selectsDeclaredAndCompilableUnitsOverLargerBackendOutput() throws Exception {
        Path primary = Files.createDirectory(temp.resolve("primary"));
        Path alternative = Files.createDirectory(temp.resolve("alternative"));
        Files.writeString(primary.resolve("One.java"), "class One { /* stub */ }\n// $VF: Couldn't be decompiled\n");
        Files.writeString(primary.resolve("Two.java"), "class Unrelated {}");
        Files.writeString(primary.resolve("Three.java"), "class Three {}");
        Files.writeString(alternative.resolve("One.java"), "class One {}");
        Files.writeString(alternative.resolve("Two.java"), "class Two {}");
        var expected = Map.of("One.java", Set.of("One"), "Two.java", Set.of("Two"), "Three.java", Set.of("Three"));
        Path syntaxPrimary = syntax(primary, "primary-syntax");
        Path syntaxAlternative = syntax(alternative, "alternative-syntax");
        var first = new SourceQuality(primary, expected, syntaxPrimary, null, true, false);
        var second = new SourceQuality(alternative, expected, syntaxAlternative, null, true, false);
        assertEquals(1, first.missing);
        assertEquals(1, first.placeholders);
        assertEquals(1, second.missing);
        var selected = SourceQuality.select(first, second, expected.keySet(), false);
        assertEquals(alternative.resolve("One.java"), selected.get("One.java"));
        assertEquals(alternative.resolve("Two.java"), selected.get("Two.java"));
        assertEquals(primary.resolve("Three.java"), selected.get("Three.java"));
        assertTrue(SourceQuality.copySelection(selected, Exports.sourceFiles(primary), temp.resolve("merged")));
        assertEquals(3, Exports.sourceFiles(temp.resolve("merged")).size());
    }

    @Test
    void syntaxWorkerFindsBrokenCodeAndNamedInnerDeclarations() throws Exception {
        Path source = Files.createDirectory(temp.resolve("src"));
        Files.writeString(source.resolve("Outer.java"), "class Outer { static class Inner {} }");
        Files.writeString(source.resolve("Broken.java"), "class Broken { void bad( }");
        Path out = Files.createDirectory(temp.resolve("syntax"));
        var lines = new java.util.ArrayList<String>();
        assertFalse(SourceValidation.run(true, source, out, 21, List.of(), lines));
        assertTrue(lines.contains("unit\tOuter.java\tOuter$Inner"));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("syntax\tBroken.java\tcompiler.err.")));
    }

    private Path syntax(Path sources, String name) throws Exception {
        var messages = new java.util.ArrayList<String>();
        assertTrue(SourceValidation.run(true, sources, Files.createDirectory(temp.resolve(name)), 21, List.of(), messages));
        return Files.write(temp.resolve(name + ".tsv"), messages);
    }
}
