# Phase 5 approval configuration

Each configuration version contains a complete map of currency codes to
approval thresholds in minor units. A missing currency uses the built-in
default of 1,000,000 minor units. Version 0 represents that built-in default
before the first stored configuration exists.

A configuration update appends a new version and its threshold rows. It also
appends a CONFIG_CHANGED audit event in the same transaction. Existing
versions and threshold rows cannot be updated, deleted or truncated.

Concurrent configuration writers serialize on transaction-scoped advisory lock
(7003, 1). The audit-chain lock remains the last lock taken by the transaction.

A version is sealed after its threshold rows are inserted. Once sealed, no
additional currency can be inserted into it. Readers only use sealed versions.

The ADMIN endpoint replaces the complete map with a new version. Each
resolution reads one snapshot; a pending approval records that version. A
configuration update that commits later does not alter that request.