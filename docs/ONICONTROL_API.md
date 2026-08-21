# OniControl dashboard API

All endpoints use the existing authenticated dashboard session, same-origin mutation check, role
matrix, audit log, rate limits, and tenant/proxy scoping. Request bodies use the dashboard's form
encoding; `payload`, `plan`, and `rules` values contain strict JSON documents.

| Method | Path | Minimum role | Purpose |
| --- | --- | --- | --- |
| GET | `/api/control/status` | Viewer | Runtime, bridge, rule, virtual-session, and aggregate status |
| GET | `/api/control/capabilities?xuid=...&backend=...` | Viewer | Actions negotiated for one frozen active target |
| POST | `/api/control/actions/preview` | Operator/granted tenant | Resolve target and issue one-time confirmation token |
| POST | `/api/control/actions/execute` | Same preview actor | Execute one token after revalidation |
| POST | `/api/control/plans/validate` | Operator/granted tenant | Validate a typed 1–16 step plan without storing a token |
| POST | `/api/control/plans/preview` | Operator/granted tenant | Freeze a plan revision and issue a token |
| POST | `/api/control/plans/execute` | Same preview actor | Execute sequentially and report partial completion |
| GET | `/api/control/history` | Viewer | Scoped redacted action history |
| GET/POST | `/api/control/rules` | Admin | Export or atomically replace scoped rules |
| GET/POST | `/api/tenancy/control-grants` | Owner | Read or replace one tenant's operator-action subset |

Example preview fields:

```text
xuid=2535...
backend=survival
action=SEND_TITLE
payload={"title":"Welcome","stayTicks":80}
reason=Event announcement
```

The response exposes the resolved target and single-use token, never a control key or signature.
Execution requires `confirmationToken=<token>&confirmed=true`.

Plan example:

```json
{
  "target": { "xuid": "2535...", "backend": "survival" },
  "steps": [
    { "stepId": "message", "action": "SEND_MESSAGE", "payload": { "message": "Welcome" } },
    { "stepId": "heal", "action": "HEAL", "payload": { "amount": 4 } }
  ],
  "failurePolicy": "STOP_ON_FAILURE",
  "reason": "Approved onboarding workflow",
  "expectedResult": "Message displayed and health increased",
  "confidence": 1
}
```

Plans reject unknown fields/actions and raw protocol, authentication, memory, shell, and stack-ID
material. They do not accept natural language as executable input.
