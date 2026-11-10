package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.AuditReader;
import com.saumay.payments.domain.audit.AuditEvent;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads audit history in global sequence order. */
public final class JdbcAuditReader implements AuditReader {

    private static final String FIND =
            "SELECT seq, scheme, entity_type, entity_id, actor, action, before_state, after_state,"
                    + " occurred_at_micros, previous_hash, current_hash FROM audit_event"
                    + " WHERE entity_type = ? AND entity_id = ? AND seq > ? ORDER BY seq LIMIT ?";

    private final JdbcTemplate jdbc;

    public JdbcAuditReader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<AuditEvent> findByEntity(String entityType, String entityId, long afterSeq, int limit) {
        return jdbc.query(FIND, (rs, row) -> new AuditEvent(
                rs.getLong("seq"), rs.getInt("scheme"), rs.getString("entity_type"),
                rs.getString("entity_id"), rs.getString("actor"), rs.getString("action"),
                rs.getString("before_state"), rs.getString("after_state"), rs.getLong("occurred_at_micros"),
                rs.getString("previous_hash"), rs.getString("current_hash")),
                entityType, entityId, afterSeq, limit);
    }
}