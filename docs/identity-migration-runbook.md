# Approval identity-migration assessment

Status: assessment procedure. This document does not define or authorize an
identity migration, direct database edits, or an alternative approval path.

## Initial deployment decision

The initial deployment is planned as a fresh deployment with no real
historical approvals. Confirm this assumption for the target environment
before rollout. If legacy approvals are present, this runbook supports
assessment only; a migration or operational resolution must be designed
and verified separately.

## When to use this

Use this procedure when an approval decision returns
`IDENTITY_MIGRATION_REQUIRED`, or before switching an existing deployment
from historical actor IDs to OIDC identities.

The current application refuses a pending approval decision when maker and
checker provenance cannot safely be compared. A legacy maker is not
automatically linked to an OIDC subject. A different issuer or identity
scheme does not prove that two identifiers represent different people.

## Before assessing

- Use an authorized read-only database connection and the appropriate
  environment.
- Record the application version, environment, assessment time, and database
  source. A replica may be behind the primary.
- Treat maker identifiers, issuer values, approval reasons, and audit data as
  sensitive operational information.
- Do not update provenance, approval status, audit rows, or identity values
  directly. V14 intentionally protects approval provenance from changes.

## Read-only inventory

Count pending approvals by maker identity scheme and issuer:

    SELECT maker_identity_scheme, maker_issuer, count(*) AS pending_count
    FROM approval_request
    WHERE status = 'PENDING'
    GROUP BY maker_identity_scheme, maker_issuer
    ORDER BY maker_identity_scheme, maker_issuer;

Inspect pending legacy approvals:

    SELECT id, case_id, action, maker, requested_at
    FROM approval_request
    WHERE status = 'PENDING'
      AND maker_identity_scheme = 'LEGACY'
    ORDER BY requested_at, id;

Inspect one approval by its known ID, replacing the placeholder with a
parameter supplied by the database client:

    SELECT id, case_id, action, maker, requested_at, status,
           maker_identity_scheme, maker_issuer, checker, decided_at
    FROM approval_request
    WHERE id = :approval_id;

The `:approval_id` notation is a placeholder, not literal SQL accepted by
every client. Use your database client's parameter mechanism.

## Inspect refusal evidence

The application records a provenance mismatch as an
`APPROVAL_IDENTITY_MISMATCH` audit event for a pending request. Inspect an
approval's events through the authorized audit API where practical, or use a
read-only database query:

    SELECT seq, actor, action, occurred_at_micros, before_state, after_state
    FROM audit_event
    WHERE entity_type = 'approval_request'
      AND entity_id = :approval_id_text
    ORDER BY seq;

Use the approval ID as text for `:approval_id_text`. An audit write can
itself fail; absence of an event is not proof that no attempt occurred.
Investigate application logs and monitoring alongside the audit record.

## Handling a blocked legacy approval

1. Keep the approval pending and record the affected case and approval IDs.
2. Escalate the business decision and identity-mapping question to the
   responsible operators and identity owners.
3. Establish whether the proposed maker and checker represent different
   people using trusted identity-provider and organizational evidence.
   This assessment alone does not alter the application's data.
4. Use a separately designed, reviewed, tested, and authorized migration or
   operational procedure when one is available. The current application
   does not provide an automatic legacy-to-OIDC mapping.

Do not bypass the refusal by changing the issuer, subject, actor header,
stored maker, provenance, or status. Do not assume that two different
subjects are different people unless the identity provider guarantees the
required stable subject contract across all accepted clients and audiences.

## Evidence and follow-up

Record the inventory counts, affected IDs, assessment time, application
version, and outcome in the organization's normal operational records. Avoid
copying full tokens or unnecessary personal data. Verify the audit chain
using the authorized audit verification process and investigate any failure
before relying on the records.

The JWK token-purpose and stable-subject requirements are documented in
[Phase 8 security](phase8-security.md).