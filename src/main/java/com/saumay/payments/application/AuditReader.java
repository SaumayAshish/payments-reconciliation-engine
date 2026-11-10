package com.saumay.payments.application;

import com.saumay.payments.domain.audit.AuditEvent;
import java.util.List;

/** Reads a bounded page of events belonging to one entity. */
public interface AuditReader {

    List<AuditEvent> findByEntity(String entityType, String entityId, long afterSeq, int limit);
}