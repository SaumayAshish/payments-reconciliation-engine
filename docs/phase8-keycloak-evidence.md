# Phase 8 local Keycloak smoke-test evidence

## Scope

On 2026-11-18, the API was manually exercised against Keycloak 26.2.5
using a local HTTPS endpoint, a locally generated test certificate, and a
separate PostgreSQL test container. The application code tested was at
commit `cfc7bba`.

The Keycloak realm and its credentials were synthetic and local to the
test. Keycloak was configured to issue RFC 9068-typed access tokens. The
API used the test certificate in a dedicated JVM truststore and an explicit
client-ID allowlist.

## Observed results

| Request | Expected | Observed |
| --- | --- | --- |
| Anonymous request to `/api/v1/exceptions` | 401 | 401 |
| Allowed ADMIN client to `/api/v1/exceptions` | 200 | 200 |
| Allowed ANALYST client to `/api/v1/exceptions` | 200 | 200 |
| ANALYST client to `/actuator/info` | 403 | 403 |
| Unapproved client to `/api/v1/exceptions` | 401 | 401 |

The full GitHub Actions run for `cfc7bba` also completed successfully.

## Limits

This was a manual smoke test, not an automated regression suite. It used
synthetic Keycloak service accounts and test role mappings. It did not
exercise browser sign-in, authorization code flow with PKCE, human identity
proofing, or maker-checker decisions involving two verified people.

This result does not establish compatibility with a future production
identity provider. Provider selection, production configuration, role
issuance, stable person subjects, and deployment security verification
remain open. The initial deployment is planned without real historical
approvals; no automatic legacy identity migration is provided.