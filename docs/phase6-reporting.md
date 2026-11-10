# Phase 6 reporting and SLA operations

## Report interpretation

Reports use the current stored case snapshot. The caller supplies an as-of
date for age and SLA calculations. A past as-of date does not reconstruct
which cases were open or assigned on that past date.

Age is the number of business days since the case's created business date.
The existing BusinessCalendar and SlaPolicy define weekend handling. Holiday
calendars are outside the current v1 implementation.

Ageing buckets are:

- 0-1 business days
- 2-5 business days
- 6-10 business days
- greater than 10 business days

SLA status uses the SLA duration stored on the case. With the standard policy,
a case is AT_RISK at 75 percent or more consumed and BREACHED after its due
business date. On its due date it is not yet breached.

## Automatic escalation

The scheduled job runs at 00:05 Asia/Kolkata each day. It scans overdue
OPEN, ASSIGNED and INVESTIGATING cases in bounded ID-ordered pages. Each
transition uses the case version as an optimistic lock and writes the existing
CASE_ESCALATED audit event in the same transaction. An overlapping run that
loses the version race does not write a second escalation.

System escalation remains allowed while an approval is pending. In that case
the case must return to INVESTIGATING before the pending approval can resolve
it, under the existing state machine.

The in-process sla_breach_count metric counts successful job transitions.
A process failure after a database commit but before the counter update can
lose an increment; the audit event is the durable evidence.

## Scope of the first implementation step

The first step adds pure-Java ageing and SLA evaluation. The ageing and SLA report reads the current unresolved cases in one database
query and groups them by team and exception kind. Rows and groups are ordered
deterministically. Resolved cases are excluded. The report is an aggregate,
not a historical reconstruction.

The scheduled escalation job and its counter have passed targeted tests,
including overlapping runs and a pending-approval scenario. A full Phase 6
build and coverage gate is still required. The resolution-time report groups the current resolved cases by their current
team and exception kind. It returns counts and whole elapsed seconds between
case creation and resolution. It separately counts rows whose resolution time
precedes creation and excludes those rows from duration totals. It does not
reconstruct past case state or use business-day SLA time.

The resolution-time report has passed targeted unit and HTTP integration tests.

The match report reads one completed run and counts its stored matches by tier
and band. It returns the run's stored input and match counts alongside those
groups. It does not calculate a match rate: a denominator must be defined
before a rate can be interpreted reliably. The match report has passed
targeted HTTP and database tests.

The match report has passed targeted HTTP and database tests.

The workload report groups the current unresolved case snapshot by team and
status. It excludes resolved cases and returns groups in team and status order.
Its targeted HTTP and database tests passed.

## Metrics

The application exposes Prometheus and metrics actuator endpoints. Request
timers measure the full HTTP request for statement ingestion, ledger
ingestion and reconciliation-run creation, including failed requests.
The outcome tag is success or failure; no account, case or user IDs are tags.

Queue-depth gauges are grouped by team and status. They are refreshed from one
workload query and represent the last successful refresh, not an immediately
transactional count. They start at zero until the first successful refresh.

The SLA counter is process-local. Audit events are the durable evidence of
escalations.

The latest-run match-rate gauge divides distinct auto-confirmed statement
lines by the stored statement-line count. It is NaN when no completed run
exists or the denominator is zero. Tier gauges count stored matches in the
latest completed run; exception-kind gauges count current unresolved cases.
These gauges represent the last successful refresh, not a live transaction.

Console logs use Logstash JSON. Timed request logs carry the existing
correlation ID and do not include account, case or user IDs as metric tags.
The metrics and structured-logging implementation passed targeted tests.
The full local Phase 6 build passed 496 unit tests and 210 integration tests,
with all configured coverage checks met. GitHub CI and gate review are pending.