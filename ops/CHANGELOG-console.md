# Console actions log

Every change made by hand in a web console or CLI outside this repository — AWS (EC2, SSM, S3, IAM, security
groups, CloudWatch), Cloudflare (DNS, proxy, WAF, rules), Resend (domains, API keys, webhooks), GitHub settings
(secrets, environments, branch protection) — is recorded here **in the same session it is made**, newest first.
The repository cannot show these changes, so this file is the only place a reviewer can see what the running system
was told; `ops-reviewer` reads it on every ops-area diff (`docs/design/dev-team-pipeline.md` §12.2).

One entry per action, English:

```
## 2026-MM-DD — <system> — <one-line what>
- **Where:** console path or CLI command (no secrets, no account ids beyond what is already public).
- **What changed:** before → after, exact values where they are not secrets.
- **Why:** ticket key and the sentence that justifies it.
- **Read-back:** the command or screen that showed the new state in effect, quoted (a change that was not read
  back is not recorded as done — it is recorded as *applied, read-back pending*, with the date it is due).
- **Rollback:** how to undo it, or "one-way" and why.
```

Rules: the read-back is measured, never inferred from the console's own confirmation banner; a value that must stay
secret is named, never pasted; an action that touches the ops surface of the repository as well (a workflow, a compose
file, `Caddyfile`) is a normal commit and does not need an entry — this file is for what git cannot see.

<!-- The file was created on 2026-09-08 (HD-304 review, ops-reviewer): CLAUDE.md, SKILL.md and the pipeline doc had
     pointed at it since the 2026-09-07 codification while no such file existed. No console action is recorded before
     this date; earlier ones live in docs/ops-prod-hardening.md and the relevant tickets. -->
