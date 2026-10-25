# ADR-0008: Routing, priority and SLA

- Status: Accepted
- Date: 2026-10-25
- Related: FR-06, FR-11; FRD-06, FRD-07, FRD-11; Q-10, EC-06.1

## Context

FRD-06 fixes an ordered list of six routing rules where the first match wins and the rule name is stored (AC-06.4). Q-10 fixes SLA lengths of P1 = 1, P2 = 2, P3 = 5 and P4 = 10 business days, with "at risk" at 75 percent consumed. Business configuration must not be hard-coded across the application.

## Decision

1. **Routing is a pure, ordered list of rules in the domain.** Input: exception kind, absolute amount in minor units, currency. Output: team, priority and the name of the rule that matched. The result is stored on the case.
2. **One configuration object**, `RoutingConfig`: the high-value threshold per currency in minor units (a sorted map, so iteration is deterministic), a default threshold, SLA business days per priority and the at-risk percentage. It comes from application properties for now. Versioned and audited configuration arrives in Phase 5.
3. **Missing threshold for a currency** uses the default and logs a warning (EC-06.1). The default is 1,000,000 minor units, a placeholder for synthetic data.
4. **Case age and SLA** are measured in business days from the business date of the run that first raised the case. The SLA length and due business date are stored on the case at creation.
5. **SLA status is derived, not stored.** `WITHIN_SLA`, `AT_RISK` and `BREACHED` are computed from the due date and an as-of business date. A stored flag would go stale between jobs, and a derived value works for any as-of date.
6. **Escalation** raises priority one level and moves the team to `TEAM_LEAD` (rule 6). It does not restart or shorten the SLA: the original due date stays, so a breach stays visible. The previous owner stays in `case_assignment`.
7. **`DUPLICATE_SUSPECT`** has a routing rule (RECON_ANALYSTS, P4 below the high-value threshold, otherwise P1 via rule 2) and is a valid case kind. Detection is deferred because it would change engine output and the Phase 3 hashes.
8. **The escalation job** that applies breaches is Phase 6. Phase 4 builds the transition it will call.

**In short.** routing and SLA are small pure functions over configuration, with the decision recorded on the case. a case must be able to explain why it sits in a team's queue, today and later, even if rules change.

## Alternatives considered

- Rejected: store SLA status and update it with a scheduled job. It goes stale, and it makes tests depend on time.

## Consequences

- Reports in Phase 6 reuse the same SLA function, so the report and the escalation job cannot disagree.
- The high-value default is a placeholder and must be reviewed with the business before any real use.

## Interview explanation

"Routing returns the rule name with the answer, so every assignment is explainable and replayable."
