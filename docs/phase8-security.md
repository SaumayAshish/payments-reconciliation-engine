# Phase 8: authentication and authorization

**Status: in progress.** The OIDC profile is experimental. The unsecured
development compatibility chain and header-based demo actor registry have
been removed. A security and identity-migration review is still required
before the Phase 8 gate.

## Runtime behavior

The application acts as an OAuth 2.0 resource server when the `oidc` profile
is active. The profile requires these deployment properties:

- `payments.security.issuer`
- `payments.security.audience`
- `payments.security.jwk-set-uri`

The configured decoder accepts RS256 signatures and uses
`TrustedJwtValidator` to check the configured issuer and audience, standard
time constraints, an expiry, and a nonblank subject. Tokens must contain a
JSON `roles` array with exactly one recognized application role: `ADMIN`,
`ANALYST`, `TEAM_LEAD`, `CONTROLLER`, or `AUDITOR`.

No production identity-provider credentials or signing keys belong in this
repository. Configure a trusted issuer, audience, and JWK endpoint for the
deployment.

When `oidc` is not active, the application denies HTTP access by default,
including Actuator. Selecting `dev` or supplying the former
`payments.security.allow-insecure-dev` property does not re-enable the
removed permit-all chain.

## Authorization and actor identity

The OIDC security chain specifies policies for the implemented route shapes
and denies requests that do not match a permitted rule. The audit, report,
and Actuator policies list their current route shapes; variable path
segments remain variable. New routes and methods require an explicit policy
and tests.

The actor comes from the authenticated JWT. `OidcActorId` derives an opaque
identifier from the exact, case-sensitive issuer and subject.
`SecurityContextActorContext` and the exception actor resolver ignore the
caller-provided `X-Actor` value for identity. A missing, unauthenticated, or
non-JWT security context cannot establish an actor.

Security errors use the API error shape and correlation ID. Authentication
failures include a Bearer challenge, and security error responses are
marked `no-store`.

## Approval identity provenance

New approval submissions record the maker's identity scheme and, for OIDC,
the verified issuer. The JDBC store writes that provenance with the approval
and its audit evidence in the same transaction. Database migration V14
classifies existing rows as `LEGACY` and prevents provenance from being
changed later.

A pending decision requires compatible maker and checker provenance:
`LEGACY` with `LEGACY`, or OIDC with the exact same issuer. Missing or
incompatible provenance is refused with `IDENTITY_MIGRATION_REQUIRED`.
Within a compatible context, the existing maker-checker rule rejects the
same actor and records the denied attempt.

A database provenance value is not authentication. Provenance mismatch
refusals do not currently create a separate denied-attempt audit event.

### Identity-provider requirement

The deployment must establish that one person has one stable subject across
all clients and audiences accepted by this API. Different subjects do not
prove different people if the provider can issue multiple subjects to one
person. This requirement is an operational identity-provider contract; the
application cannot establish it from a token alone.

Historical legacy identities are not automatically linked to OIDC subjects.
Existing legacy pending approvals need a separately designed and verified
migration or operational resolution. Do not infer that two identifiers
represent different people merely because their identity schemes or issuers
differ.

## Verification

The test suite includes:

- Signed-token validation, invalid signature, issuer, audience and expiry
  scenarios.
- An OIDC HTTP role matrix and representative unknown route and method
  checks.
- Actor-header spoofing and maker-checker HTTP scenarios.
- PostgreSQL tests for approval provenance, migration, and transactional
  rollback.
- Authenticated ingestion, reconciliation, case-intake, exception,
  controlled-resolution, and process-recovery workflows.
- Default-deny tests, including the `dev` profile with the former opt-in
  flag set.

`TestOidcIssuer` and the local JWK servers are test fixtures using ephemeral
keys; they are not production identity providers.

Targeted tests validate particular changes. The combined checkpoint requires
`mvn clean verify`, including configured coverage checks, followed by its
GitHub Actions result. Results should be attributed to the exact commit or
working-tree state that was tested.

## Remaining work

- Review the complete route policy and token contract against deployment
  requirements, including the identity provider's stable-subject guarantee.
- Design and verify a safe operational approach for historical legacy
  approvals and actor identity migration.
- Decide whether provenance-mismatch refusals need their own audit evidence.
- Complete the Phase 8 security review and verification gate before treating
  the OIDC profile as production-ready.