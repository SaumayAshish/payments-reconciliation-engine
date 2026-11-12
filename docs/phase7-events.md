# Phase 7 events and transactional outbox

## Delivery contract

Events are written to PostgreSQL in the same transaction as the business
change. A separate publisher will later deliver them to Kafka at least once.
Consumers must use eventId for deduplication; exactly-once delivery is not
claimed.

The publisher will use short database leases. A crash after Kafka accepts an
event but before PostgreSQL records publication can cause a duplicate.
Undelivered events remain in the outbox for retry.

## First event

A successful reconciliation completion writes one RUN_COMPLETED event to
the topic `payments.run.completed.v1`. Its key is the run ID. The version 1
JSON envelope contains eventId, eventType, schemaVersion, runId and occurredAt.
It contains no account numbers, names or free-text narratives.

The event insert occurs after the run output is written and before the audit
append. A rollback removes the run completion, output, outbox event and audit
event together. Repeating completion of an already completed run is rejected.

The outbox schema includes delivery state and lease fields. The opt-in
publisher sends events to Kafka, and the opt-in run consumer opens cases.
The existing manual case-intake endpoint remains available.

## Case-created event

Each newly inserted run-origin case and each case opened by an unmatch writes a
CASE_CREATED event to `payments.exception.created.v1`, keyed by case ID. Its
version 1 envelope contains eventId, eventType, schemaVersion, caseId, origin
and occurredAt. It contains no account details or free-text narratives.

The event is written in the case transaction before the audit append. Repeated
case intake writes no CASE_CREATED event for an already existing case. If the
transaction rolls back, neither the case nor its event remains.

## Publisher leases

A publisher claims a bounded batch using `FOR UPDATE SKIP LOCKED`. The claim
transaction commits before any Kafka call. Every claim receives a lease token
and expiry; an expired claim can be reclaimed with a new token.

Publication and retry updates name both the event ID and lease token. A stale
worker cannot change an event after another worker has reclaimed it. Attempts
are counted when claimed. Failed deliveries return to PENDING with an explicit
next-attempt time. The publisher claims one event per poll and sends it after the claim
transaction commits. After acknowledgement, it marks the event PUBLISHED.
Failed sends are rescheduled with exponential backoff capped at five minutes.

The publisher is disabled by default. Enable it with
`--payments.events.enabled=true` after starting the Compose `events` profile
and provisioning the versioned topics. The outbox backlog gauge is available
even when publication is disabled. It reflects the last successful refresh.

The publisher tests include a real Kafka container and an unavailable broker.
The RUN_COMPLETED consumer uses a durable event marker and the same database
transaction as case intake. The Kafka listener commits offsets after successful
record processing and stops on an unhandled processing error.

## Transactional run-event processing

`processed_run_event` records the event ID and run ID for a successfully
processed RUN_COMPLETED event. The marker and case intake use the same
PostgreSQL transaction. A matching replay performs no second intake; an event
ID reused for a different run is rejected. A failure rolls back the marker,
cases, case activities, audit changes, and CASE_CREATED outbox events together.

The listener validates the event envelope, then calls the transactional
processor. Kafka auto-commit is disabled and the listener uses record-level
offset commits. If processing throws, the configured error handler stops the
listener rather than recovering and skipping the failed record. Investigate
the failure before restarting the application or listener.

The listener is enabled by `payments.events.enabled=true`, the same setting
as the publisher. Its default consumer group is `payments-case-intake-v1`;
an uncommitted group starts at the earliest available offset. Kafka retention
and the availability of the topic remain operational responsibilities.

`KafkaRunConsumerIT` exercises the configured listener container with real
Kafka and PostgreSQL. It checks duplicate delivery, transactional rollback,
restart after a processing failure, committed offsets, and listener stop on
malformed JSON. `KafkaApplicationContextIT` checks that the enabled application context wires
the publisher and run consumer and starts the listener. The full application
and Phase 7 verification gates remain outstanding until their checks pass.
## Operations and limits

- Event delivery is at least once. Consumers must tolerate duplicate events.
- The publisher and run consumer are disabled by default. Enabling
  `payments.events.enabled=true` enables both in the servlet application.
- Provision the versioned topics before enabling event delivery. The Compose
  `events` profile is intended for local development; its single broker is
  not a highly available production deployment.
- Failed publication is retried from the outbox. A stopped consumer needs
  investigation and restart; an invalid record will stop it again until the
  underlying problem is addressed.
- Preserve `processed_run_event` for as long as events may be replayed.
  Removing markers can allow an old event to be processed again.
- There is no global ordering guarantee across events or partitions.
- The existing manual case-intake endpoint remains available.
- The real Kafka listener test covers duplicate delivery, rollback, restart,
  offsets and malformed records. The application-context test checks that
  event components are wired and the listener starts.
- The full Maven verification and coverage gate passed before the
  application-context test cleanup adjustment; the adjusted tests passed
  targeted verification. CI verification remains pending.

## Remaining Phase 7 work

- Review the final changes and documentation.
- Commit and push, then verify the GitHub Actions result.