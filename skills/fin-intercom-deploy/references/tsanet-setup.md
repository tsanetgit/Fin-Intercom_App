# TSANet-side setup

What the member needs from TSANet, and what the deployment registers against TSANet
Connect. TSANet staff drive the first half; the connector's deploy flow drives the
second.

## 1. Member provisioning (TSANet-side, once)

An integrated member needs, in this order (the order matters — the API user's name is
the primary name in the Entra registration):

1. The standard member setup: account, admin user, process form, group assignments.
2. An **API user** on the member account.
3. Credentials for that user, one of two modes:
   - **password** — username/email + password, exchanged for a bearer token at login.
     Simplest, and what most integrations run today.
   - **client_credentials** — a Microsoft Entra app registration; the member
     authenticates with client id + secret against TSANet's tenant. Needs the tenant id
     and the environment-specific audience (BETA and production audiences differ; do
     not carry one environment's audience to the other).

Members always land on **BETA first** (`https://connect2.tsanet.net`) and migrate to
production (`https://connect2.tsanet.org`) after validation. Configure the connector's
`TSANET_API_URL` accordingly, and remember a credential set belongs to exactly one
environment.

A TSANet self-service integration portal (member self-provisioned credentials, IT
contacts, auth-method selection) is in the September 2026 release; until a member is on
it, credentials arrive through TSANet staff.

## 2. The registered-domain rule (tell the member's agents)

The Connect API rejects submitter and engineer emails that are not on the member's
registered email domains. No status code carries that rule; the connector surfaces the
rejection text next to the identity fields, prefills identities from the acting
teammate, and keeps them editable precisely because a teammate's Intercom login can be
off-domain. Onboarding should tell agents: the submitter/engineer email must be on the
member's registered domain, whatever they log into Intercom with.

## 3. The webhook subscription (v2 CloudEvents, one per deployment)

Inbound push arrives as v2 CloudEvents at `https://<connector-fqdn>/webhooks/tsanet`,
signed `x-hub-signature-256`. Facts that shape the flow:

- **Register v2, never v1.** v1 is removed in the first part of 2027 and carries no
  event-level idempotency key. The connector refuses non-v2 bodies loudly (still
  answering 200, since redelivery cannot fix a shape) — so a wrong-version registration
  surfaces on the first delivery instead of as silence.
- **The API mints the subscription secret at creation.** Capture it into
  `TSANET_WEBHOOK_SECRET` and roll the deployment. This is why a first-ever deploy is
  two passes: the app must exist to have a URL, then the subscription is created
  against that URL and the minted secret fed back in. `scripts/azure-poc-deploy.sh`
  automates the list-then-create and the capture.
- **Deleting a subscription happens on the v1 path** (`DELETE /v1/webhooks/{id}`); the
  v2 path 404s (probed 2026-08-11). Do this at teardown so the environment stops
  receiving delivery attempts.
- Push is effectively single-attempt end to end: TSANet treats 4xx as permanent, and
  the connector fast-acks 200 on every branch. The reconciliation poller (on by
  default) is what turns a lost delivery into a late ticket instead of a lost one.
  Leave it on.

## 4. `TSANET_TEST_SUBMISSION` — the go-live flag

Defaults **true**: every case the connector creates is flagged as a test submission,
which partners can filter out. Setting it false makes the connector create REAL partner
cases and is a deliberate, per-deployment decision at pilot time, taken with the member
and TSANet — not a config knob to flip while debugging.

Reading note: the flag is written as `testSubmission` and read back as `testCase`.
Anything auditing cases must filter on the read name.

## 5. What TSANet operates against a running deployment

The connector serves a TSANet-internal admin plane (`/admin/*`, read + validate only)
for the Connect_Gateway Admin Console: status, config with secrets as presence markers,
log/audit ring tails, and credential validation that probes live but applies nothing.
It authenticates with its own signed scheme under `CONNECTOR_ADMIN_API_SECRET` — a
console↔adapter secret, never a member credential. Leaving it blank disables the admin
plane entirely (fail closed); nothing member-facing depends on it.
