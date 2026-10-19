package com.saumay.payments.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * NFR-05 (no floating point in money paths) and ADR-0003 (no wall clock, no randomness in deterministic code),
 * enforced by scanning the source of the pure core. ArchUnit sees fields and signatures but not local variables,
 * which is why this complements it.
 */
class SourcePolicyTest {

    private static final Path MAIN = Path.of("src/main/java/com/saumay/payments");
    private static final List<String> PURE_PACKAGES = List.of("domain", "matching", "ingestion");

    @Test
    void pureCoreContainsNoFloatingPointClockOrRandomness() {
        List<String> violations = new ArrayList<>();
        for (String pkg : PURE_PACKAGES) {
            Path dir = MAIN.resolve(pkg);
            assertTrue(Files.isDirectory(dir), "expected package directory " + dir);
            violations.addAll(JavaSourceScanner.violationsIn(dir, JavaSourceScanner.CORE_POLICIES));
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void scannerFlagsRealViolations() {
        String source = "class X { double d = 1.0; long t = System.currentTimeMillis();"
                + " java.time.Instant i = java.time.Instant.now(); }";
        assertTrue(JavaSourceScanner.violationsInSource("X", source, JavaSourceScanner.CORE_POLICIES).size() >= 3);
    }

    @Test
    void scannerIgnoresCommentsAndStringLiterals() {
        String source = """
                // a double is not allowed here, nor is Random
                /* float and now() in a block comment */
                class Y { String s = "double float now() Random"; char c = 'x'; String t = \"\"\"
                    double in a text block
                    \"\"\"; }
                """;
        assertFalse(JavaSourceScanner.violationsInSource("Y", source, JavaSourceScanner.CORE_POLICIES).size() > 0);
    }
}