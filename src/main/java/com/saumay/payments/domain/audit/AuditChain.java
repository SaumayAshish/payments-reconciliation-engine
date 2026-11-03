package com.saumay.payments.domain.audit;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Builds and re-computes the links of the audit chain (ADR-0004, ADR-0010).
 *
 * <p>Scheme 1 hashes {@code SHA-256(canonicalBytes || previousHash)} where {@code previousHash} is the raw 32
 * bytes of the previous event's hash (32 zero bytes for the first event) and {@code canonicalBytes} is, in this
 * order: scheme (4 bytes, big endian), seq (8 bytes), then entityType, entityId, actor, action, beforeState and
 * afterState each as a 4-byte big-endian length followed by its UTF-8 bytes, then occurredAtMicros (8 bytes).
 * Length prefixes mean no choice of field values can shift a boundary and collide with another event. Hashes are
 * stored and compared as 64 lower-case hex characters.
 *
 * <p>This layout is a compatibility contract pinned by a golden-vector test whose expected values were computed
 * by an independent implementation. A different layout must get a new scheme number; old events keep verifying
 * under the scheme they were written with.
 *
 * <p>Pure and deterministic: no clock, no randomness, no I/O.
 */
public final class AuditChain {

    public static final int CURRENT_SCHEME = 1;
    public static final String GENESIS_HASH = "0".repeat(64);
    /** Head of the empty chain: the first event is seq 1 and links to the genesis hash. */
    public static final AuditHead GENESIS = new AuditHead(0, GENESIS_HASH);

    private AuditChain() {
    }

    public static boolean supports(int scheme) {
        return scheme == CURRENT_SCHEME;
    }

    /** The event that follows {@code head}: next sequence number, linked to the head, hashed under the current scheme. */
    public static AuditEvent append(AuditHead head, AuditDraft draft) {
        long seq = Math.addExact(head.seq(), 1L);
        String hash = hash(CURRENT_SCHEME, seq, draft.entityType(), draft.entityId(), draft.actor(),
                draft.action(), draft.beforeState(), draft.afterState(), draft.occurredAtMicros(), head.hash());
        return new AuditEvent(seq, CURRENT_SCHEME, draft.entityType(), draft.entityId(), draft.actor(),
                draft.action(), draft.beforeState(), draft.afterState(), draft.occurredAtMicros(), head.hash(),
                hash);
    }

    /** The hash this event should carry, computed from its content and its stored previous hash. */
    public static String recompute(AuditEvent event) {
        if (!supports(event.scheme())) {
            throw new IllegalArgumentException("unsupported audit scheme " + event.scheme());
        }
        return hash(event.scheme(), event.seq(), event.entityType(), event.entityId(), event.actor(),
                event.action(), event.beforeState(), event.afterState(), event.occurredAtMicros(),
                event.previousHash());
    }

    public static AuditHead headOf(AuditEvent event) {
        return new AuditHead(event.seq(), event.currentHash());
    }

    private static String hash(int scheme, long seq, String entityType, String entityId, String actor,
            String action, String beforeState, String afterState, long occurredAtMicros, String previousHash) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(scheme).array());
        bytes.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(seq).array());
        writeText(bytes, entityType);
        writeText(bytes, entityId);
        writeText(bytes, actor);
        writeText(bytes, action);
        writeText(bytes, beforeState);
        writeText(bytes, afterState);
        bytes.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(occurredAtMicros).array());
        bytes.writeBytes(HexFormat.of().parseHex(previousHash));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }

    private static void writeText(ByteArrayOutputStream bytes, String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        bytes.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(utf8.length).array());
        bytes.writeBytes(utf8);
    }
}