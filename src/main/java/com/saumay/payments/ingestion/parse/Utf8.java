package com.saumay.payments.ingestion.parse;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict UTF-8 decoding shared by the parsers (assumption A-04: anything else is refused, never guessed). */
final class Utf8 {

    private static final char BOM = '\uFEFF';

    private Utf8() {
    }

    static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new FileRejectedException(FileErrorCode.UNSUPPORTED_ENCODING, "The file is not valid UTF-8");
        }
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }
}