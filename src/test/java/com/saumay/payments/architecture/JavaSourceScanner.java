package com.saumay.payments.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans Java source for forbidden constructs after removing comments and literals, so words in documentation or
 * strings never cause false alarms. Used for rules ArchUnit cannot express, such as local variables of type
 * {@code double} (NFR-05) or reads of the wall clock (ADR-0003).
 */
final class JavaSourceScanner {

    record Policy(String name, Pattern pattern) {
    }

    static final List<Policy> CORE_POLICIES = List.of(
            new Policy("floating point type (NFR-05, ADR-0001)", Pattern.compile("\\b(double|float|Double|Float)\\b")),
            new Policy("wall clock read (ADR-0003)", Pattern.compile("\\bnow\\s*\\(")),
            new Policy("wall clock read (ADR-0003)", Pattern.compile("\\b(currentTimeMillis|nanoTime)\\b")),
            new Policy("randomness (ADR-0003)", Pattern.compile("\\b(Random|SecureRandom|ThreadLocalRandom|SplittableRandom)\\b")),
            new Policy("random identifier (ADR-0003)", Pattern.compile("\\brandomUUID\\b")));

    private JavaSourceScanner() {
    }

    static List<String> violationsIn(Path root, List<Policy> policies) {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                violations.addAll(violationsInSource(file.toString(), Files.readString(file), policies));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return violations;
    }

    static List<String> violationsInSource(String label, String source, List<Policy> policies) {
        String stripped = stripCommentsAndLiterals(source);
        List<String> violations = new ArrayList<>();
        String[] lines = stripped.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Policy policy : policies) {
                if (policy.pattern().matcher(lines[i]).find()) {
                    violations.add(label + ":" + (i + 1) + " " + policy.name());
                }
            }
        }
        return violations;
    }

    static String stripCommentsAndLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i += 2;
            } else if (src.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < n && !src.startsWith("\"\"\"", i)) {
                    if (src.charAt(i) == '\\') {
                        i++;
                    } else if (src.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i += 3;
                out.append("\"\"");
            } else if (c == '"') {
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                out.append("\"\"");
            } else if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                out.append("''");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}