package com.saumay.payments.ingestion.parse;

/** Why a whole file was refused. A refused file creates no ingestion row (FRD EC-01.5). */
public enum FileErrorCode {
    EMPTY_FILE,
    NO_DATA_ROWS,
    UNSUPPORTED_ENCODING,
    MALFORMED_CSV,
    INVALID_HEADER
}