# Glossary

Terms used in the BRD, FRD, ADRs and code. Where a term has a project-specific meaning, that meaning wins.

| Term | Meaning |
| --- | --- |
| **Account** | Synthetic identifier of a bank account being reconciled, for example `NOSTRO-GBP-001`. Never a real account number |
| **Adjustment** | A correcting entry that changes the books to explain a difference (for example a bank fee not yet booked). Controlled action above the threshold |
| **Ageing** | How long an exception has been open, measured in business days from creation to an explicit as-of business date. Reported in buckets `0-1`, `2-5`, `6-10`, `>10` |
| **Ageing clock** | The business-date counter that starts when an exception is created. Derived, not stored (see architecture.md section 6) |
| **Audit event** | One immutable record of who did what to which entity, with before and after state and a hash linking it to the previous event |
| **Auto-confirm** | A match whose score is at or above the auto threshold (default 0.95) and is accepted without human review |
| **Booking date** | The date the bank or ledger recorded the entry. Can differ from the value date |
| **Break** | A reconciliation difference: an item on one side with no acceptable counterpart on the other. In this system a break becomes an exception case of kind `BREAK` |
| **Business day** | Monday to Friday. Holiday calendars are out of scope for v1 |
| **camt.053** | ISO 20022 bank-to-customer statement message. v1 supports a simplified subset |
| **Canonical record** | The normalised internal form of a statement line or ledger entry (amount in minor units, ISO currency, dates, cleaned reference and counterparty) |
| **Candidate** | A ledger entry (or statement line) that could plausibly match a given line under a tier's rules |
| **Checker** | The second person who approves or rejects a controlled action. Must differ from the maker |
| **Claim** | The act of binding a candidate to a match so it cannot be used by another. A candidate has at most one active claim |
| **Configuration version** | An immutable snapshot of thresholds, windows, SLA targets and maker-checker limits. Every run records the version it used |
| **Content hash** | SHA-256 of a file's raw bytes. The identity of an ingestion |
| **Controlled action** | A write-off or adjustment whose absolute amount is above the configured threshold, requiring maker-checker |
| **Counterparty** | The other party to a payment as named on the line (payer or payee) |
| **Credit / debit** | Statement convention: a credit is money into the account. Canonical sign: in is positive, out is negative (open question Q-01) |
| **Deterministic replay** | Re-running a past reconciliation from its recorded inputs and configuration and obtaining a byte-identical output hash |
| **Exception case** | The unit of work for an unmatched or low-confidence item. Has type, state, owner, team, priority, SLA and timeline |
| **Escalation** | Moving an exception up because its SLA is breached or it exceeds the owner's authority. The original owner remains on record |
| **Fuzzy match** | Tier 3. Same amount and currency, similar counterparty and narrative, scored by token and edit-distance blend |
| **Hash chain** | Each audit event includes the hash of the previous one, so altering any past event invalidates every later hash |
| **Idempotency** | Doing an operation more than once has the same effect as doing it once. For ingestion: same bytes, same single result |
| **Ledger entry** | A record from the internal books for the account being reconciled (not a general-ledger posting engine) |
| **Lookback window** | How many business days back from the run date ledger entries remain eligible candidates (default 10) |
| **Maker** | The person who initiates a controlled action |
| **Maker-checker** | A control requiring two different people to complete a sensitive action: one proposes, another approves. Also called the four-eyes principle or separation of duties |
| **Manual match** | A match created by an analyst, tier `MANUAL`, score 1.00, always audited |
| **Match** | A confirmed or proposed pairing of one or more statement lines with one or more ledger entries, with tier, score and reason |
| **Match rate** | Auto-confirmed statement lines divided by total statement lines in a run (Q-02) |
| **Minor units** | The smallest currency unit as an integer: pence for GBP, cents for USD, yen for JPY. `GBP 12.50` is `1250` |
| **Nostro** | "Our account with them": a bank's account held at another bank, in the other bank's location and usually in a foreign currency. Reconciling nostro statements against internal books is the classic use case |
| **Vostro** | "Your account with us": the same account seen from the holding bank's side. The two are mirror images, which is why sign convention (Q-01) matters |
| **One-to-many** | Tier 4. One line equals the sum of several on the other side (batch settlements), searched over at most 8 candidates |
| **Outbox** | A table written in the same transaction as a business change, from which events are later published to Kafka, so database and broker cannot diverge |
| **Output hash** | SHA-256 of a run's canonical output. Stored and compared on replay |
| **Reconciliation run** | One execution of the engine for a business date with defined inputs and configuration |
| **Reference (normalised)** | Payment reference uppercased with everything except `A-Z0-9` removed. Leading zeros kept |
| **Rejected line** | An input line failing validation, stored with an error code rather than dropped |
| **Resolved** | Terminal exception state: closed by a manual match, split, adjustment or write-off |
| **Review band** | Scores from the review threshold up to (not including) the auto threshold, default 0.70 to 0.949. Human confirmation required |
| **Reversal** | An entry with the opposite sign cancelling an earlier one. Not automatically netted against the original |
| **Score** | Match confidence stored as a scaled integer `scoreMilli` from 0 to 1000 |
| **SLA** | Service-level agreement: the time allowed to resolve an exception by priority (P1 1 day to P4 10 days, proposed) |
| **Statement line** | One transaction row from a bank statement file |
| **Tie** | Two candidates with the same best score for a line, or one candidate equally best for two lines. Never auto-confirmed |
| **Tolerance match** | Tier 2. Same reference, amount and currency, value dates within the configured window |
| **Value date** | The date from which funds are considered available or interest accrues. Used as the primary match date |
| **Write-off** | Accepting a difference as a loss and closing the exception without finding the counterpart. Controlled action |
