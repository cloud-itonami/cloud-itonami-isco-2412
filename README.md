# cloud-itonami-isco-2412

Open Occupation Blueprint for **ISCO-08 2412**: Financial and Investment Advisers.

This repository designs a forkable OSS business for an independent financial and investment advisory practice: a secure document-handling and archival robot manages client statements and disclosures under a governor-gated actor, so the practice keeps its own advisory records instead of renting a closed wealth-management SaaS.

**Maturity: `:implemented`.** `src/finadvisory/` implements the
`FinancialAdvisoryActor` as a `langgraph.graph/state-graph`
(`finadvisory.actor`) wired to an `Advisory Advisor` (`finadvisory.advisor`)
and an independent `FinancialAdvisoryGovernor` (`finadvisory.governor`),
following the itonami actor pattern (ADR-2607011000): `:intake -> :advise ->
:govern -> :decide -+-> :commit (:ok?) +-> :request-approval (:escalate?,
human-in-the-loop interrupt) +-> :hold (:hard?)`. 14 tests / 29 assertions
green (`kbb -M:test`). HARD invariants (always hold, never
overridable): client provenance, no-actuation (`:effect` must be
`:propose`), a registered account basis for any recommendation, the
proposed allocation percentage not exceeding the account's registered
suitability ceiling (recommending beyond the client's registered risk
tolerance is unsuitable advice, not aggressive strategy), and a risk
disclosure attached before any recommendation can be committed
(undisclosed advice is not efficient service). Always-escalate ops
(human sign-off regardless of confidence, mapping this repo's Trust
Controls in [`docs/business-model.md`](docs/business-model.md)):
`:approve-trade-execution` and `:approve-fund-transfer` (no trade
execution or fund transfer without the governor gate).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a secure document-handling and archival robot performs statement printing, disclosure packet assembly and physical archival under an actor that proposes
actions and an independent **Financial Advisory Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
trade execution or fund transfer) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
client intake + risk profile + investment mandate
        |
        v
Advisory Advisor -> Financial Advisory Governor -> advise/recommend, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `2412`). Required capabilities:

- :robotics
- :identity
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
