package com.saumay.payments.ingestion.normalise;

/**
 * One parsed but not yet validated input row, all fields as text (any may be null). CSV, camt.053 and the
 * ledger REST path all produce this shape, which is how FRD-03 guarantees identical canonical records.
 */
public record RawLine(
        String account,
        String bookingDate,
        String valueDate,
        String amount,
        String currency,
        String reference,
        String counterparty,
        String narrative) {
}