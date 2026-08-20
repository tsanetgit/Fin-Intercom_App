# Intercom workspace setup

Everything the member's Intercom workspace needs before the connector can serve it.
Each item records the why, because most of these shapes were forced by measured
platform behavior (`docs/decisions.md` D1, D3, D5, D17), not chosen.

## 1. The private app (Developer Hub)

Create a private app in the member's workspace (the reference deployment calls it
`tsanet-connect`). It supplies four things:

| Piece | Where it goes |
|---|---|
| Canvas initialize URL | `https://<connector-fqdn>/canvas/initialize` |
| Canvas submit URL | `https://<connector-fqdn>/canvas/submit` |
| Webhook endpoint | `https://<connector-fqdn>/webhooks/intercom` |
| Client secret + access token | connector environment (`INTERCOM_DEV_CLIENT_SECRET`, `INTERCOM_ACCESS_TOKEN`) |

The client secret verifies BOTH canvas signatures (`X-Body-Signature`, HMAC-SHA256)
and webhook signatures (`X-Hub-Signature`, HMAC-SHA1). The access token drives ticket
creation, annotations, notes, and state moves. If the app's credentials rotate, update
the environment and redeploy — a stale secret shows up as 401s on canvas/webhooks, and
a stale token as failed annotations in the audit log.

**Placement:** add the app to the ticket Details panel (Inbox). One placement config
covers both ticket and conversation surfaces; the connector distinguishes them by the
top-level `ticket` object that rides every canvas request from a ticket surface.

**Webhook topics must be subscribed explicitly** — granting permissions subscribes
nothing by itself. The reference set:

- `ticket.created`
- `ticket.state.updated`
- `ticket.attribute.updated`
- `ticket.admin.assigned`
- `ticket.note.created`

### Permissions — the verified minimum (issue #57)

Set the app's permission toggles (Configure → Authentication) to exactly this set,
verified live 2026-08-20 against the reference workspace:

- **Read tickets** and **Write tickets** — ticket create/read/update, state moves
- **Read and write users** — contact listing/search
- **Write users and companies** — despite the name, this is the scope that CREATES
  and updates contacts; the synthetic partner contact needs it
- **Write conversations** — notes via the `/reply` endpoints

Every other editable toggle stays OFF. Intercom additionally grants four greyed,
un-uncheckable baseline scopes to every app ("Read and list users and companies" —
which bundles segments, companies, and tags reads — "Read conversations", "Read
admins", "Read data when entered into the app"); reads riding those will succeed
regardless and are not a misconfiguration.

**Permission edits are inert until the token is regenerated.** Saving toggle changes
does NOT change what the existing access token can do — the Authentication page shows
a warning banner ("outdated permissions... may grant more access than currently
configured") once they drift. The order that works: save the toggles → regenerate the
token → update `INTERCOM_ACCESS_TOKEN` in the connector environment → restart. Between
regeneration and restart the adapter's Intercom writes fail and recover via the
reconciliation poller; signature verification is unaffected (it keys on the client
secret).

The call inventory this minimum was derived from — re-verify the toggle set whenever
a new Intercom API call is added: `POST/GET/PUT /tickets` + ticket search,
`GET /ticket_types` (config-time), `POST /tickets/{id}/reply` and
`POST /conversations/{id}/reply` (notes), `GET/POST/PUT /contacts` + contact search
(synthetic partner contact), `GET /me` (health).

## 2. The Back-office ticket type

Inbound partner cases become tickets of one configured Back-office type ("Customer
cannot see" category — these are partner collaborations, not customer conversations).
Create it once per workspace, then put its id in
`INTERCOM_COLLABORATION_TICKET_TYPE_ID`.

It is deliberately un-defaulted: ticket type ids are per-workspace, and a ticket of the
wrong type is worse than no ticket, because the type carries the attributes every later
slice reads. Unset means inbound cases are refused, loudly, with an error-level
`tsanet_audit` line.

### Attributes — names are identity, spell them exactly

Ticket attributes are keyed by NAME on both write and read (measured; D5). The
connector writes these three:

| Attribute name | Type | Content |
|---|---|---|
| `TSANet Token` | text | the case token — the routing key Phase-2 inbound depends on |
| `Partner` | text | partner member name |
| `Respond By` | date/time | the respond-by deadline (written as epoch seconds — that is the wire format Intercom accepts, probed live) |

A workspace-side rename does not error; it surfaces as the connector's read-back
verification failing (the D3 rule: every Intercom mutation is read back and asserted,
because the API returns 200 while silently ignoring unknown fields). If annotations
report DEGRADED or the audit shows read-back failures, check the attribute names first.

Tickets require exactly one contact, even Back-office ones. The connector handles this
itself with a synthetic partner contact (external id `tsanet-partner`), created on
first use and reused — nothing to provision.

## 3. Ticket states and their per-workspace ids

The connector moves ticket state per the D17 mapping:

| Connect status | Ticket state |
|---|---|
| OPEN | Submitted |
| ACCEPTED | In progress |
| INFORMATION | Waiting on customer |
| REJECTED / CLOSED | Resolved |

Reading a state returns a string, but WRITING one requires the numeric
`ticket_state_id`, and those ids are per-workspace. Three of them are configuration:

- `INTERCOM_RESOLVED_STATE_ID` — where a partner-closed case lands (closure REFUSES
  when unset: a wrong id may name a different real state and move the ticket somewhere
  wrong without an error)
- `INTERCOM_IN_PROGRESS_STATE_ID` — partner accepted our outbound case
- `INTERCOM_WAITING_STATE_ID` — partner asked us for more information

Each unset id disables only the statuses that need it, each with its own error-level
audit line.

**Resolving the ids is a one-off, out-of-band step.** The only route found to enumerate
them is the ticket-type object under the `Intercom-Version: Unstable` API version
(stable versions omit `ticket_states` and `GET /ticket_states` returns nothing):

```bash
curl -s https://api.intercom.io/ticket_types/<TYPE_ID> \
  -H "Authorization: Bearer $INTERCOM_ACCESS_TOKEN" \
  -H "Intercom-Version: Unstable" | jq '.ticket_states'
```

Run that once at configuration time, record the four ids, and never put the Unstable
header in anything that runs continuously — it has no compatibility promise, and under
some stable versions (2.11, probed) state writes are silently ignored, which is exactly
the failure the connector's by-id read-back exists to catch.

One vocabulary note for the member: Intercom's "Waiting on customer" state shows the
external label "Waiting on you". On Back-office tickets that text is not
partner-visible, so the mismatch is cosmetic — if these tickets ever gain a
customer-facing surface, rename the state in the workspace rather than remapping.

## 4. What agents will see

- Outbound: any ticket's Details panel → TSANet Connect section → partner search →
  process form (submitter identity prefilled from the acting teammate, editable) →
  Submit → "Case submitted" card with the token.
- Inbound: a partner case arrives as a ticket titled from the partner's summary
  (falling back to "TSANet collaboration request"), panel header "Partner
  collaboration", offering Accept / Request information / Decline with an editable
  engineer identity.
- Canvas text renders markdown links and bold only — never HTML. Partner-authored HTML
  is converted at the presentation boundary.
