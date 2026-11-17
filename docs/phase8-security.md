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
repository. Configure a trusted issuer, audience, and HTTPS JWK endpoint for
the deployment.

The application validates the configured JWK URI at startup and requires
HTTPS. Local tests may set `payments.security.allow-insecure-loopback-jwks=true`
to permit a configured HTTP URI only for the literal IPv4 address
`127.0.0.1`. This flag does not permit configured remote HTTP URLs and must
not be enabled in production. URI user information and fragments are rejected.

The JWK HTTP client disables automatic redirects and rejects redirect
responses, including a redirect response that contains a JWK body. The
configured URI remains subject to the HTTPS policy above. The HTTP
integration suite checks that a redirect target is not contacted and that
the redirect response cannot authenticate a token.

The HTTP suite also checks that a token with a newly published key ID causes
another JWK fetch and can be authenticated. This does not establish immediate
revocation of a removed key: deployments must account for decoder caching,
token lifetime, and their identity provider's key-rotation procedures.

### Token-purpose limitation

The current validator does not independently require a token type, scope, or
authorized-party claim that distinguishes an access token from an ID token.
A token that satisfies the configured signature, issuer, audience, time,
subject, and role checks can be accepted regardless of its intended purpose.
The deployment must verify that its identity-provider and audience contract
prevents tokens issued for another purpose from satisfying these checks.
Token-purpose enforcement remains part of the Phase 8 security review.

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

A database provenance value is not authentication. A pending decision
refused because provenance is missing or incompatible records an
`APPROVAL_IDENTITY_MISMATCH` audit event. If that audit write fails, the
decision remains blocked and the error propagates. The OIDC HTTP integration
suite checks both approval and rejection refusals, stored audit details, and
a forced PostgreSQL audit-write failure.

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

The OpenAPI document declares the bearer JWT scheme. Its optional `X-Actor`
parameters are retained for compatibility with existing request shapes, but
the header is ignored for authentication and actor identity. Contract tests
also check documented roles for selected sensitive routes against the current
policy.

The test suite includes:

- Signed-token validation, invalid signature, issuer, audience, expiry,
  missing required claims, and future `nbf` scenarios.
- An OIDC HTTP role matrix and representative unknown route and method
  checks.
- Actor-header spoofing and maker-checker HTTP scenarios.
- PostgreSQL tests for approval provenance, migration, and transactional
  rollback.
- Authenticated ingestion, reconciliation, case-intake, exception,
  controlled-resolution, and process-recovery workflows.
- Default-deny tests, including the `dev` profile with the former opt-in
  flag set.
- A combined `dev,oidc` profile test checking authentication, role denial,
  and resistance to a spoofed actor header.

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
- Review the operational handling and monitoring of provenance-mismatch
  audit events and failed audit writes.
- Complete the Phase 8 security review and verification gate before treating
  the OIDC profile as production-ready.