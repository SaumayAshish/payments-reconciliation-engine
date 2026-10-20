package com.saumay.payments.ingestion.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saumay.payments.ingestion.normalise.LineErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Camt053ParserTest {

    private static final String ACCOUNT = "GB00SYNTH0001";

    @Test
    void aCreditEntryMapsEverySupportedField() {
        ParsedFile file = Camt053Parser.parse(document(statement(ACCOUNT,
                entry("1250.50", "GBP", "CRDT", "E2E-1"))));

        assertEquals(1, file.rows().size());
        ParsedRow row = file.rows().get(0);
        assertEquals(1, row.lineNumber());
        assertEquals(ACCOUNT, row.raw().account());
        assertEquals("1250.50", row.raw().amount());
        assertEquals("GBP", row.raw().currency());
        assertEquals("E2E-1", row.raw().reference());
        assertEquals("Acme Ltd", row.raw().counterparty());
        assertEquals("Invoice 42", row.raw().narrative());
        assertEquals("2026-10-09", row.raw().bookingDate());
        assertEquals("2026-10-10", row.raw().valueDate());
        assertEquals(0, file.ignoredElements());
    }

    @Test
    void aDebitEntryIsNegativeAndUsesTheCreditorAsCounterparty() {
        ParsedFile file = Camt053Parser.parse(document(statement(ACCOUNT,
                entry("10.00", "GBP", "DBIT", "E2E-2"))));

        assertEquals("-10.00", file.rows().get(0).raw().amount());
        assertEquals("Beta Ltd", file.rows().get(0).raw().counterparty());
    }

    @Test
    void entriesAreNumberedInDocumentOrderAcrossStatements() {
        ParsedFile file = Camt053Parser.parse(document(
                statement("ACC-A", entry("1.00", "GBP", "CRDT", "A1"), entry("2.00", "GBP", "CRDT", "A2")),
                statement("ACC-B", entry("3.00", "GBP", "CRDT", "B1"))));

        assertEquals(3, file.rows().size());
        assertEquals(1, file.rows().get(0).lineNumber());
        assertEquals("ACC-A", file.rows().get(1).raw().account());
        assertEquals(3, file.rows().get(2).lineNumber());
        assertEquals("ACC-B", file.rows().get(2).raw().account());
    }

    @Test
    void theAccountMayComeFromTheOtherIdentifier() {
        String xml = "<Document><Stmt><Acct><Id><Othr><Id>NOSTRO-GBP-001</Id></Othr></Id></Acct>"
                + entry("1.00", "GBP", "CRDT", "E1") + "</Stmt></Document>";
        ParsedFile file = Camt053Parser.parse(xml.getBytes(StandardCharsets.UTF_8));

        assertEquals("NOSTRO-GBP-001", file.rows().get(0).raw().account());
    }

    @Test
    void theNotProvidedPlaceholderCountsAsAMissingReference() {
        ParsedFile file = Camt053Parser.parse(document(statement(ACCOUNT,
                entry("1.00", "GBP", "CRDT", "NOTPROVIDED"))));

        assertEquals("", file.rows().get(0).raw().reference());
    }

    @Test
    void aBadIndicatorRejectsOnlyThatEntry() {
        ParsedFile file = Camt053Parser.parse(document(statement(ACCOUNT,
                entry("1.00", "GBP", "CRDT", "E1"),
                entry("2.00", "GBP", "XXXX", "E2"),
                entry("3.00", "GBP", "DBIT", "E3"))));

        assertEquals(2, file.rows().size());
        assertEquals(1, file.rows().get(0).lineNumber());
        assertEquals(3, file.rows().get(1).lineNumber());
        assertEquals(1, file.rejected().size());
        assertEquals(2, file.rejected().get(0).lineNumber());
        assertEquals(LineErrorCode.INVALID_AMOUNT, file.rejected().get(0).code());
    }

    @Test
    void unsupportedElementsAreIgnoredCountedAndNeverLeakIntoFields() {
        String noisy = entry("1.00", "GBP", "CRDT", "E1")
                .replace("</Ntry>", "<AcctSvcrRef>SECRET-X</AcctSvcrRef><Sts>BOOK</Sts></Ntry>");
        ParsedFile file = Camt053Parser.parse(document(statement(ACCOUNT, noisy)));

        assertEquals(2, file.ignoredElements());
        assertEquals("E1", file.rows().get(0).raw().reference());
        assertEquals("Invoice 42", file.rows().get(0).raw().narrative());
    }

    @Test
    void documentTypeDeclarationsAndExternalEntitiesAreRefused() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Document>&x;</Document>";

        assertFileError(FileErrorCode.MALFORMED_XML, xxe.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void malformedXmlIsRefused() {
        assertFileError(FileErrorCode.MALFORMED_XML, "<Document><Stmt>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void emptyFilesAndFilesWithoutEntriesAreRefused() {
        assertFileError(FileErrorCode.EMPTY_FILE, new byte[0]);
        assertFileError(FileErrorCode.NO_DATA_ROWS, "<Document></Document>".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidUtf8IsRefusedNotGuessed() {
        assertFileError(FileErrorCode.UNSUPPORTED_ENCODING, new byte[] {'<', (byte) 0xC3, (byte) 0x28, '>'});
    }

    @Test
    void sameBytesAlwaysGiveTheSameResult() {
        byte[] bytes = document(statement(ACCOUNT,
                entry("1.00", "GBP", "CRDT", "E1"), entry("2.00", "GBP", "BAD", "E2")));

        assertEquals(Camt053Parser.parse(bytes), Camt053Parser.parse(bytes));
    }

    // ---- helpers ----

    private static String entry(String amount, String currency, String indicator, String endToEndId) {
        return """
                <Ntry>
                  <Amt Ccy="%s">%s</Amt>
                  <CdtDbtInd>%s</CdtDbtInd>
                  <BookgDt><DtTm>2026-10-09T10:15:00</DtTm></BookgDt>
                  <ValDt><Dt>2026-10-10</Dt></ValDt>
                  <NtryDtls><TxDtls>
                    <Refs><EndToEndId>%s</EndToEndId></Refs>
                    <RltdPties><Dbtr><Nm>Acme Ltd</Nm></Dbtr><Cdtr><Nm>Beta Ltd</Nm></Cdtr></RltdPties>
                    <RmtInf><Ustrd>Invoice 42</Ustrd></RmtInf>
                  </TxDtls></NtryDtls>
                </Ntry>
                """.formatted(currency, amount, indicator, endToEndId);
    }

    private static String statement(String account, String... entries) {
        return "<Stmt><Acct><Id><IBAN>" + account + "</IBAN></Id></Acct>" + String.join("", entries) + "</Stmt>";
    }

    private static byte[] document(String... statements) {
        String xml = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\"><BkToCstmrStmt>"
                + String.join("", statements) + "</BkToCstmrStmt></Document>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertFileError(FileErrorCode expected, byte[] bytes) {
        FileRejectedException e = assertThrows(FileRejectedException.class, () -> Camt053Parser.parse(bytes));
        assertEquals(expected, e.code());
    }
}