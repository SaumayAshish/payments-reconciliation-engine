package com.saumay.payments.infrastructure.persistence;

import com.saumay.payments.application.ApprovalConfigurationStore;
import com.saumay.payments.application.AuditLog;
import com.saumay.payments.domain.approval.ApprovalThreshold;
import com.saumay.payments.domain.audit.AuditDraft;
import com.saumay.payments.domain.audit.AuditTime;
import com.saumay.payments.domain.audit.CanonicalJson;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL storage for append-only approval configuration. */
public final class JdbcApprovalConfigurationStore implements ApprovalConfigurationStore {

    private static final String LOCK_WRITERS = "SELECT pg_advisory_xact_lock(7003, 1)";
    private static final String CURRENT =
            "SELECT v.version, t.currency, t.threshold_minor FROM config_version v"
                    + " JOIN config_version_seal s ON s.config_version = v.version"
                    + " LEFT JOIN config_approval_threshold t ON t.config_version = v.version"
                    + " WHERE v.version = (SELECT max(config_version) FROM config_version_seal)"
                    + " ORDER BY t.currency";
    private static final String INSERT_VERSION =
            "INSERT INTO config_version (actor, created_at) VALUES (?, ?) RETURNING version";
    private static final String INSERT_THRESHOLD =
            "INSERT INTO config_approval_threshold (config_version, currency, threshold_minor)"
                    + " VALUES (?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AuditLog audit;

    public JdbcApprovalConfigurationStore(DataSource dataSource, AuditLog audit) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public Snapshot current() {
        List<Row> rows = jdbc.query(CURRENT, (rs, rowNum) -> {
            String currency = rs.getString("currency");
            return new Row(rs.getLong("version"), currency == null ? null : currency.trim(),
                    currency == null ? null : rs.getLong("threshold_minor"));
        });

        if (rows.isEmpty()) {
            return new Snapshot(0, ApprovalThreshold.defaults());
        }

        Map<String, Long> values = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.currency() != null) {
                values.put(row.currency(), row.thresholdMinor());
            }
        }
        return new Snapshot(rows.get(0).version(),
                new ApprovalThreshold(ApprovalThreshold.DEFAULT_MINOR, values));
    }

    @Override
    public Snapshot append(ApprovalThreshold threshold, String actor, Instant at) {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(at, "at");
        if (threshold.defaultMinor() != ApprovalThreshold.DEFAULT_MINOR) {
            throw new IllegalArgumentException("the default threshold is fixed");
        }
        if (actor == null || actor.isBlank() || actor.trim().length() > 100) {
            throw new IllegalArgumentException("actor must contain 1 to 100 characters");
        }
        String by = actor.trim();

        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.execute(LOCK_WRITERS);
            Snapshot before = current();
            long version = Objects.requireNonNull(jdbc.queryForObject(INSERT_VERSION, Long.class,
                    by, OffsetDateTime.ofInstant(at, ZoneOffset.UTC)));

            threshold.perCurrencyMinor().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> jdbc.update(INSERT_THRESHOLD, version, entry.getKey(), entry.getValue()));

            jdbc.update("INSERT INTO config_version_seal (config_version) VALUES (?)", version);
            Snapshot after = new Snapshot(version, threshold);
            audit.append(new AuditDraft("configuration", String.valueOf(version), by, "CONFIG_CHANGED",
                    state(before), state(after), AuditTime.micros(at)));
            return after;
        }));
    }

    private static String state(Snapshot snapshot) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", snapshot.version());
        state.put("defaultMinor", snapshot.threshold().defaultMinor());
        state.put("perCurrencyMinor", snapshot.threshold().perCurrencyMinor());
        return CanonicalJson.write(state);
    }

    private record Row(long version, String currency, Long thresholdMinor) {
    }
}