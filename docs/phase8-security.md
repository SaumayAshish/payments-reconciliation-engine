# Phase 8: authentication and authorization

> Current status: the OIDC approval path carries verified issuer provenance.
> The historical sections below describe earlier checkpoints; where they say
> that every OIDC decision is blocked or that provenance is not wired, that
> statement has been superseded by the "OIDC approval provenance" section.

Status: in progress. Authentication and RBAC are not yet enforced across
the API.

## Selected approach

The application will act as an OAuth 2.0 resource server and accept JWT
access tokens from a configured, trusted identity provider. Token signature,
issuer, audience and applicable time constraints must be validated before
claims are used.

No production issuer, credential or signing key is stored in this repository.
The issuer and audience will be supplied by deployment configuration.

## Actor identity

`OidcActorId` derives an opaque identifier from the exact, case-sensitive
issuer and subject values. It must only be called with claims from a
successfully validated token. It does not validate JWTs.

The experimental OIDC HTTP profile connects this identity function to the
security context. The compatibility mode still retains the earlier
header-based behavior and is not secured.

Existing historical actor IDs were recorded under the earlier identity
scheme. Switching schemes does not automatically link those historical IDs
to OIDC identities. Deployment and maker-checker migration behavior must be
resolved and tested before enabling the new identity scheme against existing
data.

## Authorization requirements

Every implemented HTTP route and management endpoint needs an explicit policy.
The API catalogue is the starting role matrix. The supported roles are
ADMIN, ANALYST, TEAM_LEAD, CONTROLLER and AUDITOR.

The authenticated identity, not `X-Actor` or a request body, must be used
for audit and maker-checker decisions. Denied roles must not perform side
effects. Unknown routes and methods must fail closed.

## Verification still required

- Signed access tokens: valid, expired, malformed, wrong issuer, wrong
  audience and invalid signature.
- Role allow and deny cases for each implemented endpoint and HTTP method.
- Management endpoint policies and unknown-route behavior.
- Actor-header spoofing and maker-checker separation.
- Migration of existing API integration tests to authenticated requests.
- Full Maven verification, coverage and GitHub Actions.

## Signed-token validation foundation

`TrustedJwtValidator` applies the configured issuer and standard timestamp
validation, requires an expiry and subject, and requires the configured
audience. It is intended to be attached to a signature-verifying JWT
decoder. It does not verify signatures by itself.

`TrustedJwtValidatorTest` uses ephemeral RSA keys and signed tokens to test
the decoder and validator together. This foundation is not yet wired into
HTTP requests and does not provide endpoint protection.

## Temporary approval-decision boundary

Approval records currently contain a maker string but no trusted identity
scheme or verified mapping to an OIDC subject. During Phase 8, an OIDC-shaped
checker ID is therefore refused for every pending approval decision, including
new approvals. The API maps this refusal to HTTP 409 with code
`IDENTITY_MIGRATION_REQUIRED`.

This is a temporary availability restriction, not a complete authorization
control. The prefix check is not proof of authentication and must never be used
to authenticate a caller. The HTTP security migration must supply verified
identity, record trustworthy provenance, and replace this temporary block with
tested maker-checker logic before OIDC approval decisions are enabled.

This refusal currently makes no approval decision and records no separate
denied-attempt audit event.

## Verified JWT actor mapping

`VerifiedJwtActorMapper` maps an already authenticated JWT to the existing
single-role `Actor` model. Its initial token contract requires a JSON `roles`
array with exactly one recognized application role. Missing, unknown,
incorrectly typed, or multiple roles are denied.

The mapper derives the actor ID from the exact verified issuer and subject.
It does not verify a signature or establish authentication. It must only be
called with a JWT obtained from the configured Spring Security authentication
flow.

The experimental OIDC filter chain uses this mapper after JWT validation.
Compatibility-mode endpoint access remains unchanged.

## Authenticated actor context

`SecurityContextActorContext` resolves an actor from an authenticated JWT
principal held by Spring Security. It ignores the caller-provided actor header.

The adapter is registered by the experimental OIDC profile. Its unit tests
use a synthetic context; separate OIDC HTTP tests exercise the actual
signature-verifying filter chain.

## Exception-controller actor transition

Exception actions use the authenticated JWT actor when a security context
exists. The caller's X-Actor value cannot override that JWT identity.
An unauthenticated or non-JWT security context is rejected.

Until the HTTP security filter chain is installed, requests with no security
context still use the previous X-Actor behavior. This is a transitional
compatibility path, not authentication. It must be removed when JWT
authentication becomes mandatory.


## Experimental OIDC HTTP profile

The `oidc` profile enables the Spring Security JWT resource-server chain.
It requires all three deployment properties:

- `payments.security.issuer`
- `payments.security.audience`
- `payments.security.jwk-set-uri`

The decoder is configured for RS256 and uses `TrustedJwtValidator` for
issuer, audience, expiry and subject checks. The token must contain exactly
one recognized application role in its `roles` array.

The OIDC chain has explicit route rules and denies unlisted routes. The
authenticated JWT supplies actor identity. The temporary approval guard
continues to block OIDC approval and rejection decisions until identity
provenance is implemented.

**The OIDC profile remains experimental.** Real-HTTP token, role-matrix and
actor-spoofing checks have passed in targeted runs. Identity migration and
the full verification gate remain outstanding.

Without `oidc` or `dev`, HTTP access is denied by default. The explicit `dev`
profile retains unsecured compatibility behavior for older functional tests;
it must not be used as a production security configuration.

The compatibility chain must be removed before the Phase 8 gate.

## OIDC real-HTTP integration test

`OidcSecurityHttpIT` starts the actual application with the `oidc` profile,
a local test-only JWK endpoint, generated RSA keys and PostgreSQL. It is
intended to exercise signed-token validation, selected role restrictions,
Actuator access and a spoofed actor header through HTTP.

The full endpoint-role matrix and identity-provenance migration remain
separate Phase 8 work. The compatibility mode remains unsecured.

## Endpoint role-matrix test

The OIDC HTTP integration suite includes a matrix for the listed implemented
routes and the five application roles. It checks that anonymous requests are
rejected, disallowed roles receive 403, and allowed roles pass the security
boundary. Write requests use malformed bodies to avoid business mutations.

This test does not replace business-operation tests, approval identity
migration, full verification or the removal of the unsecured compatibility
chain.

## Security error responses

The experimental OIDC filter chain uses the common API error shape for
authentication and authorization failures. Responses include the correlation
ID and do not echo bearer tokens. Authentication failures include a Bearer
challenge; security error responses are marked `no-store`.

The compatibility chain remains unsecured and is scheduled for removal.

## Default-deny HTTP behavior

When neither `oidc` nor `dev` is active, the application denies HTTP access,
including Actuator. The explicit `dev` profile temporarily retains the old
unsecured behavior for legacy functional tests.

`DefaultSecurityHttpIT` checks the default-deny chain over HTTP. The legacy
functional and process-recovery tests explicitly select `dev` until they are
migrated to authenticated requests. These tests do not prove OIDC security.

## Approval identity provenance schema

V14 records the maker identity scheme and, for OIDC makers, the issuer.
Existing approval rows are classified as LEGACY without guessing an OIDC
identity. The database prevents later changes to the recorded provenance.

The schema is only a foundation. Current application code does not yet write
or enforce OIDC provenance, and the temporary block on OIDC approval decisions
remains active. A database value alone is not proof that a caller was
authenticated.

The next implementation must write provenance in the approval transaction,
compare maker and checker only when their trusted identity contexts are
compatible, and test rollback and maker-checker separation over HTTP.

## Transactional provenance persistence

The approval persistence port now accepts an explicit maker identity scheme
and issuer. The existing submission constructor records LEGACY provenance.
The JDBC store inserts the approval and its provenance in the same transaction
as its activity and audit event.

The store can read the immutable provenance for a later decision. A failed
audit append must roll back the approval, its provenance and activity.

This is persistence wiring only. The HTTP approval path does not yet provide
OIDC provenance, and the temporary OIDC decision block remains active.


## OIDC approval provenance

The authenticated JWT mapper attaches the verified issuer to the actor. New
approval submissions persist that provenance with the approval transaction.
A pending approval can be decided only when maker and checker provenance match:
legacy with legacy, or OIDC with the exact same issuer. Missing provenance and
mixed schemes or issuers fail closed with `IDENTITY_MIGRATION_REQUIRED`.

Within a compatible identity context, the existing maker-checker rule rejects
the same actor and records the denied attempt. Compatibility of issuer and
subject identifiers is only a prerequisite: deployment must guarantee that
one person has one stable subject across all clients and audiences allowed to
use this API. Different subjects do not establish separation of duties if the
identity provider can issue multiple subjects to the same person.

Legacy approvals are not automatically linked to OIDC users. They require a
separately designed and verified identity migration. Provenance mismatch
refusals do not currently create a separate denied-attempt audit event.

Service-level tests cover submission provenance, same-actor denial, compatible
and incompatible issuers, mixed legacy/OIDC identities, and missing
provenance. Real-HTTP approval workflow tests remain required before the
Phase 8 security gate. The `dev` profile is unsecured and is not suitable for
production.

## OIDC approval HTTP verification

`OidcSecurityHttpIT` includes an approval workflow using signed JWTs, the
actual HTTP security chain, and PostgreSQL. It checks persisted maker
provenance, self-approval and self-rejection, spoofed actor headers, approval
by a different controller, and refusal to decide a legacy approval using an
OIDC identity.

Run the test and the full Maven verification gate before treating this
checkpoint as verified. The stable-subject requirement and other Phase 8
limitations described above remain applicable.