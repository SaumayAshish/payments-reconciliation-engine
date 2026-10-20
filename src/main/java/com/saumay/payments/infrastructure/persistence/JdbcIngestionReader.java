package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.domain.model.ContentHash;
import com.saumay.payments.domain.model.LineSource;
import com.saumay.payments.ingestion.FileFormat;
import com.saumay.payments.ingestion.IngestionReader;
import com.saumay.payments.ingestion.IngestionSummary;
import com.saumay.payments.ingestion.RejectedLine;
import com.saumay.payments.ingestion.normalise.LineErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads ingestions with plain SQL. Rejected lines are returned in line order, capped at {@value #MAX_REJECTED}. */
public final class JdbcIngestionReader implements IngestionReader {

    static final int MAX_REJECTED = 1000;

    private final JdbcTemplate jdbc;

    public JdbcIngestionReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<IngestionSummary> find(long id) {
        List<IngestionSummary> found = jdbc.query(
                "SELECT kind, format, file_hash, byte_size, accepted_count, rejected_count, ingested_at"
                        + " FROM ingestion_file WHERE id = ?",
                (rs, row) -> new IngestionSummary(
                        id,
                        LineSource.valueOf(rs.getString("kind")),
                        FileFormat.valueOf(rs.getString("format")),
                        new ContentHash(rs.getString("file_hash")),
                        rs.getLong("byte_size"),
                        rs.getInt("accepted_count"),
                        rs.getInt("rejected_count"),
                        rs.getObject("ingested_at", OffsetDateTime.class).toInstant(),
                        List.of()),
                id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<RejectedLine> rejected = jdbc.query(
                "SELECT line_number, error_code, error_message, raw_excerpt FROM rejected_line"
                        + " WHERE ingestion_id = ? ORDER BY line_number LIMIT " + MAX_REJECTED,
                (rs, row) -> new RejectedLine(
                        rs.getInt("line_number"),
                        LineErrorCode.valueOf(rs.getString("error_code")),
                        rs.getString("error_message"),
                        rs.getString("raw_excerpt")),
                id);
        IngestionSummary header = found.get(0);
        return Optional.of(new IngestionSummary(header.id(), header.kind(), header.format(), header.fileHash(),
                header.byteSize(), header.acceptedCount(), header.rejectedCount(), header.ingestedAt(), rejected));
    }
}