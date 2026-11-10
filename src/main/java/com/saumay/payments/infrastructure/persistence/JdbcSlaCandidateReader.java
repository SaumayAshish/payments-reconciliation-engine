package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.SlaCandidateReader;
import com.saumay.payments.domain.model.BusinessCalendar;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads overdue candidate IDs without holding locks during the scan. */
public final class JdbcSlaCandidateReader implements SlaCandidateReader {

    private static final String PAGE =
            "SELECT id, version FROM exception_case"
                    + " WHERE status IN ('OPEN', 'ASSIGNED', 'INVESTIGATING')"
                    + " AND due_business_date < ? AND id > ? ORDER BY id LIMIT ?";

    private final JdbcTemplate jdbc;

    public JdbcSlaCandidateReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<Candidate> overdueAfter(LocalDate asOf, long afterId, int limit) {
        return jdbc.query(PAGE, (rs, rowNum) -> new Candidate(rs.getLong("id"), rs.getLong("version")),
                BusinessCalendar.rollForward(asOf), afterId, limit);
    }
}