package com.saumay.payments.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ContentHashAndLineKeyTest {

    @Test
    void sha256MatchesTheStandardTestVectorForAbc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ContentHash.ofUtf8("abc").hex());
    }

    @Test
    void identicalBytesGiveIdenticalHashesAndDifferentBytesDoNot() {
        assertEquals(ContentHash.ofUtf8("file"), ContentHash.ofUtf8("file"));
        assertNotEquals(ContentHash.ofUtf8("file"), ContentHash.ofUtf8("file\n"));
    }

    @Test
    void rejectsMalformedHashes() {
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("ABC"));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash(null));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("A".repeat(64)));
    }

    @Test
    void lineKeyIsDeterministicAndDependsOnFileAndLineNumber() {
        ContentHash file = ContentHash.ofUtf8("file");
        assertEquals(LineKey.of(file, 1), LineKey.of(file, 1));
        assertNotEquals(LineKey.of(file, 1), LineKey.of(file, 2));
        assertNotEquals(LineKey.of(file, 1), LineKey.of(ContentHash.ofUtf8("other"), 1));
    }

    @Test
    void lineNumbersStartAtOne() {
        assertThrows(IllegalArgumentException.class, () -> LineKey.of(ContentHash.ofUtf8("file"), 0));
    }
}