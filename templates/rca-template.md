# RCA: <short incident title>

| Field | Value |
| --- | --- |
| RCA ID | RCA-YYYY-NNN |
| Severity | SEV1 / SEV2 / SEV3 |
| Status | Draft / In review / Final |
| Incident start (UTC) | |
| Detected (UTC) | |
| Resolved (UTC) | |
| Duration | |
| Author | |
| Reviewers | |
| Related | ticket, runbook section, ADR, PR |

> Blameless format: describe what the system and process allowed, not who made a mistake. Use synthetic or anonymised data only.

## 1. Summary

Two to four sentences: what happened, who or what was affected, and how it was fixed. A reader should be able to stop here.

## 2. Impact

- **Business impact**: which reconciliations, how many lines, which SLAs.
- **Financial or control impact**: any mismatch, missed approval, or audit-chain concern. State "none" explicitly if none.
- **Data impact**: any data lost, duplicated or corrupted. State how this was checked.
- **Duration and scope**: how long, how many runs or users.

## 3. Detection

- How was it detected (alert, metric, user report)? Time to detect.
- Should it have been detected sooner? What signal was missing?

## 4. Timeline (UTC)

| Time | Event | Source |
| --- | --- | --- |
| | | |

## 5. Root cause analysis

### 5.1 What happened (technical)
Describe the failing mechanism precisely, with identifiers (`runId`, `ingestionId`, `correlationId`).

### 5.2 Five whys
1. Why did the failure occur?
2. Why did that happen?
3. Why did that happen?
4. Why did that happen?
5. Why did that happen? (the systemic cause)

### 5.3 Contributing factors
Process, tooling, testing, monitoring, documentation, or requirement gaps.

### 5.4 Why existing controls did not prevent it
Tests, reviews, constraints, alerts that should have caught it, and why they did not.

## 6. Resolution

- **Immediate fix**: what was done to restore service, and whether it was safe to repeat.
- **Verification**: evidence the fix worked (counts, hashes, metrics, `audit verify` result).
- **Data correction**: any controlled correction made, with approval references. Never direct database edits.

## 7. Corrective and preventive actions

| ID | Action | Type (fix / detect / prevent) | Owner | Due | Status |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

Each action must be specific and testable. Prefer an automated test, constraint or alert over a reminder.

## 8. Requirements and documentation impact

- Does this reveal a gap in the BRD or FRD? Link the FR/AC to add or change.
- Does an ADR need amending?
- Runbook updated? Link the section.
- Traceability matrix updated?

## 9. Lessons learned

What went well, what went badly, where we got lucky.

## 10. Appendix

Logs (redacted), query output, graphs, the failing test added.
