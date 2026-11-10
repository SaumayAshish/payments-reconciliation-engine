# Audit verification

Run the packaged application with `--audit.verify` to verify the stored audit
chain without starting the web server. Configure the database connection in
the same way as for the application.

```powershell
java -jar target\payments-reconciliation-0.1.0-SNAPSHOT-exec.jar --audit.verify
$LASTEXITCODE
```

An intact chain prints `audit.verify status=OK` with the event count and head,
and exits 0. A broken chain prints `audit.verify status=FAILED` with the first
bad sequence, reason and verified count, and exits 3. Startup or database
failures are process failures and do not indicate a successful verification.

A successful verification also logs `audit.head seq=<n> hash=<h>`. Retain this
line in an independently protected location if it is to serve as an external
anchor. The log line alone does not automatically compare future results.

Verification checks the rows present. It cannot detect a deleted tail without
comparison to an independently recorded head.

An expected anchor identifies an event by sequence and hash. A valid anchor
may be earlier than the current head: later valid events are allowed. The
verifier checks the complete chain first. If the chain is intact but does not
contain the expected anchor, it reports HEAD_MISMATCH and the verified head.
The genesis anchor is sequence 0 and the all-zero hash.

For the command-line check, supply both options:

    --audit.expected-seq=<sequence>
    --audit.expected-hash=<64 lower-case hexadecimal characters>

For the audit verification API, supply both query parameters:

    expectedSeq=<sequence>
    expectedHash=<64 lower-case hexadecimal characters>

A HEAD_MISMATCH result includes both the expected anchor and the verified
current head. The command exits 3 for a mismatch. Missing or invalid paired
inputs are rejected.