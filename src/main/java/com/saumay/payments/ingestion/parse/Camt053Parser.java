package com.saumay.payments.ingestion.parse;

import com.saumay.payments.ingestion.RejectedLine;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import com.saumay.payments.ingestion.normalise.RawLine;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reader for simplified camt.053 statements (FRD 4.4). Hardened: DTDs and external entities are refused before the
 * XML parser sees the document. A pure function of the bytes.
 *
 * <p>Supported paths: Stmt/Acct/Id/IBAN (or Othr/Id), and per Ntry: Amt@Ccy, CdtDbtInd, BookgDt, ValDt,
 * NtryDtls/TxDtls/Refs/EndToEndId, RltdPties/Dbtr|Cdtr/Nm, RmtInf/Ustrd. Other elements inside an Ntry are ignored
 * and counted. Entries are numbered in document order across all statements in the file.
 */
public final class Camt053Parser {

    private static final Set<String> SUPPORTED = Set.of(
            "Amt", "CdtDbtInd", "BookgDt", "ValDt", "Dt", "DtTm", "NtryDtls", "TxDtls", "Refs", "EndToEndId",
            "RltdPties", "Dbtr", "Cdtr", "Pty", "Nm", "RmtInf", "Ustrd");
    private static final String NOT_PROVIDED = "NOTPROVIDED";

    private Camt053Parser() {
    }

    public static ParsedFile parse(byte[] content) {
        Objects.requireNonNull(content, "content");
        if (content.length == 0) {
            throw new FileRejectedException(FileErrorCode.EMPTY_FILE, "The file is empty");
        }
        String text = Utf8.decode(content);
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY")) {
            throw new FileRejectedException(FileErrorCode.MALFORMED_XML, "DTDs and entities are not allowed");
        }

        Walker walker = new Walker();
        XMLStreamReader reader = null;
        try {
            reader = hardenedFactory().createXMLStreamReader(new StringReader(text));
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> walker.start(reader);
                    case XMLStreamConstants.CHARACTERS -> walker.text(reader.getText());
                    case XMLStreamConstants.END_ELEMENT -> walker.end();
                    default -> { }
                }
            }
        } catch (XMLStreamException e) {
            throw new FileRejectedException(FileErrorCode.MALFORMED_XML, "The file is not well-formed XML");
        } finally {
            close(reader);
        }

        if (walker.entryCount == 0) {
            throw new FileRejectedException(FileErrorCode.NO_DATA_ROWS, "The file contains no statement entries");
        }
        return new ParsedFile(walker.rows, walker.rejected, walker.ignored);
    }

    private static XMLInputFactory hardenedFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException e) {
            // Not every StAX implementation knows this property; DTDs are already refused above.
        }
        return factory;
    }

    private static void close(XMLStreamReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (XMLStreamException e) {
                // Nothing useful to do: the document has already been fully consumed or rejected.
            }
        }
    }

    /** Tracks where we are in the document and collects entries. */
    private static final class Walker {

        private final List<String> stack = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final List<ParsedRow> rows = new ArrayList<>();
        private final List<RejectedLine> rejected = new ArrayList<>();
        private int stmtDepth = -1;
        private int ntryDepth = -1;
        private String account = "";
        private Entry entry;
        private int entryCount;
        private int ignored;

        void start(XMLStreamReader reader) {
            String name = reader.getLocalName();
            stack.add(name);
            text.setLength(0);
            int depth = stack.size() - 1;
            if (ntryDepth >= 0) {
                if (!SUPPORTED.contains(name)) {
                    ignored++;
                }
                if (relative(ntryDepth).equals("Amt")) {
                    String currency = reader.getAttributeValue(null, "Ccy");
                    if (currency != null) {
                        entry.currency = currency.strip();
                    }
                }
            } else if (stmtDepth >= 0) {
                if (name.equals("Ntry")) {
                    ntryDepth = depth;
                    entryCount++;
                    entry = new Entry(entryCount, account);
                }
            } else if (name.equals("Stmt")) {
                stmtDepth = depth;
                account = "";
            }
        }

        void text(String characters) {
            text.append(characters);
        }

        void end() {
            int depth = stack.size() - 1;
            String value = text.toString().strip();
            if (ntryDepth >= 0 && depth > ntryDepth) {
                entry.accept(relative(ntryDepth), value);
            } else if (stmtDepth >= 0 && ntryDepth < 0 && depth > stmtDepth) {
                String path = relative(stmtDepth);
                if (path.equals("Acct/Id/IBAN") || path.equals("Acct/Id/Othr/Id")) {
                    account = value;
                }
            } else if (depth == ntryDepth) {
                finishEntry();
                ntryDepth = -1;
                entry = null;
            } else if (depth == stmtDepth) {
                stmtDepth = -1;
            }
            stack.remove(depth);
            text.setLength(0);
        }

        private String relative(int base) {
            return String.join("/", stack.subList(base + 1, stack.size()));
        }

        private void finishEntry() {
            String excerpt = "Ntry " + entry.number + " amt=" + entry.amount + " ccy=" + entry.currency
                    + " ref=" + entry.reference;
            String signedAmount;
            String counterparty;
            switch (entry.indicator) {
                case "CRDT" -> {
                    signedAmount = entry.amount;
                    counterparty = entry.debtor;
                }
                case "DBIT" -> {
                    signedAmount = entry.amount.isEmpty() ? "" : "-" + entry.amount;
                    counterparty = entry.creditor;
                }
                default -> {
                    rejected.add(new RejectedLine(entry.number, LineErrorCode.INVALID_AMOUNT,
                            "credit/debit indicator must be CRDT or DBIT", excerpt));
                    return;
                }
            }
            String reference = entry.reference.equalsIgnoreCase(NOT_PROVIDED) ? "" : entry.reference;
            RawLine raw = new RawLine(entry.account, entry.booking, entry.value, signedAmount, entry.currency,
                    reference, counterparty, entry.narrative);
            rows.add(new ParsedRow(entry.number, raw, null, excerpt));
        }
    }

    /** Fields of one Ntry as text. For single-valued fields the first non-empty value wins. */
    private static final class Entry {

        final int number;
        final String account;
        String amount = "";
        String currency = "";
        String indicator = "";
        String booking = "";
        String value = "";
        String reference = "";
        String debtor = "";
        String creditor = "";
        String narrative = "";

        Entry(int number, String account) {
            this.number = number;
            this.account = account;
        }

        void accept(String path, String v) {
            switch (path) {
                case "Amt" -> amount = first(amount, v);
                case "CdtDbtInd" -> indicator = first(indicator, v);
                case "BookgDt/Dt", "BookgDt/DtTm" -> booking = first(booking, dateOnly(v));
                case "ValDt/Dt", "ValDt/DtTm" -> value = first(value, dateOnly(v));
                case "NtryDtls/TxDtls/Refs/EndToEndId" -> reference = first(reference, v);
                case "NtryDtls/TxDtls/RltdPties/Dbtr/Nm", "NtryDtls/TxDtls/RltdPties/Dbtr/Pty/Nm" ->
                        debtor = first(debtor, v);
                case "NtryDtls/TxDtls/RltdPties/Cdtr/Nm", "NtryDtls/TxDtls/RltdPties/Cdtr/Pty/Nm" ->
                        creditor = first(creditor, v);
                case "NtryDtls/TxDtls/RmtInf/Ustrd" -> {
                    if (!v.isEmpty()) {
                        narrative = narrative.isEmpty() ? v : narrative + " " + v;
                    }
                }
                default -> { }
            }
        }

        private static String first(String current, String candidate) {
            return current.isEmpty() ? candidate : current;
        }

        private static String dateOnly(String v) {
            int t = v.indexOf('T');
            return t < 0 ? v : v.substring(0, t);
        }
    }
}