package com.saumay.payments.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saumay.payments.domain.audit.AuditDraft;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises the packaged verification command against disposable PostgreSQL. */
@Testcontainers
class AuditVerifyProcessIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @TempDir
    Path tempDir;

    @Test
    void theExecutableJarReturnsZeroForAnIntactChainAndThreeForTampering() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            JdbcAuditLog audit = new JdbcAuditLog(dataSource);
            TransactionTemplate transaction =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            transaction.executeWithoutResult(status -> audit.append(new AuditDraft(
                    "configuration", "test", "alice", "TEST_EVENT", "{}", "{}", 1L)));

            var firstHead = audit.head();
            assertTrue(audit.verify(firstHead)
                    instanceof com.saumay.payments.domain.audit.AuditVerification.Ok);

            transaction.executeWithoutResult(status -> audit.append(new AuditDraft(
                    "configuration", "test", "alice", "SECOND_EVENT", "{}", "{}", 2L)));
            assertTrue(audit.verify(firstHead)
                    instanceof com.saumay.payments.domain.audit.AuditVerification.Ok);
            assertTrue(audit.verify(com.saumay.payments.domain.audit.AuditChain.GENESIS)
                    instanceof com.saumay.payments.domain.audit.AuditVerification.Ok);

            var wrongHash = new com.saumay.payments.domain.audit.AuditHead(1, "f".repeat(64));
            var mismatch = audit.verify(wrongHash);
            assertTrue(mismatch instanceof com.saumay.payments.domain.audit.AuditVerification.AnchorMismatch);
            var details = (com.saumay.payments.domain.audit.AuditVerification.AnchorMismatch) mismatch;
            assertEquals("HEAD_MISMATCH", details.reason());
            assertEquals(wrongHash, details.expected());
            assertEquals(audit.head(), details.actual());

            var missing = new com.saumay.payments.domain.audit.AuditHead(3, "e".repeat(64));
            assertTrue(audit.verify(missing)
                    instanceof com.saumay.payments.domain.audit.AuditVerification.AnchorMismatch);

            ProcessResult anchored = runCommand("anchored.log",
                    "--audit.expected-seq=" + firstHead.seq(),
                    "--audit.expected-hash=" + firstHead.hash());
            assertEquals(0, anchored.exitCode(), anchored.output());

            ProcessResult wrongAnchor = runCommand("wrong-anchor.log",
                    "--audit.expected-seq=1", "--audit.expected-hash=" + "f".repeat(64));
            assertEquals(3, wrongAnchor.exitCode(), wrongAnchor.output());
            assertTrue(wrongAnchor.output().contains("reason=HEAD_MISMATCH"), wrongAnchor.output());

            ProcessResult intact = runCommand("intact.log");
            assertEquals(0, intact.exitCode(), intact.output());
            assertTrue(intact.output().contains("audit.verify status=OK count=2 headSeq=2"),
                    intact.output());

            jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER USER");
            try {
                jdbc.update("UPDATE audit_event SET actor = ? WHERE seq = 1", "tampered");
            } finally {
                jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER USER");
            }

            assertTrue(intact.output().contains("audit.head seq=2 hash="), intact.output());

            assertTrue(audit.verify(firstHead)
                    instanceof com.saumay.payments.domain.audit.AuditVerification.Failed);

            ProcessResult broken = runCommand("broken.log");
            assertEquals(3, broken.exitCode(), broken.output());
            assertTrue(!broken.output().contains("audit.head seq="), broken.output());
            assertTrue(broken.output().contains(
                    "audit.verify status=FAILED firstBadSeq=1 reason=HASH_MISMATCH"),
                    broken.output());
        }
    }

    private ProcessResult runCommand(String outputName, String... extraArgs) throws Exception {
        Path jar = Path.of("target", "payments-reconciliation-0.1.0-SNAPSHOT-exec.jar")
                .toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "Executable JAR was not built: " + jar);

        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isRegularFile(java)) {
            java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        }
        Path output = tempDir.resolve(outputName);
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", jar.toString(),
                "--audit.verify",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword());
        for (String arg : extraArgs) {
            builder.command().add(arg);
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());

        Process process = builder.start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS),
                    "Audit verification process did not exit within 90 seconds");
            return new ProcessResult(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}