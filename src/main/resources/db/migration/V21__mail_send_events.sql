-- ---------------------------------------------------------------------------
-- HD-190 (Invitation budget — recipient-keyed mail throttle, persisted state)
-- ---------------------------------------------------------------------------
-- Append-only journal of outbound mail this instance decided to send. It is the
-- state behind the two RECIPIENT-keyed ceilings (docs/design/invite-budget-proposal.md
-- §6.2/§6.3, docs/adr/0015-recipient-keyed-mail-throttle-persisted.md):
--
--   * the per-(sender, recipient) cooldown, which works ACROSS WORKSPACES, and
--   * the global per-recipient daily cap.
--
-- WHY A TABLE AND NOT A MAP (the decision, in one paragraph). Every other limiter
-- in the app keeps its counters in a ConcurrentHashMap. That is right for a volume
-- budget and wrong for a control against harassment: a cooldown a deploy resets is a
-- cooldown an attacker waits out. And the free alternative — derive the cooldown from
-- workspace_invites — is worse than nothing, because THREE existing paths delete that
-- row and one of them is pressed by the victim: declineInvite() does a DELETE, so
-- declining an unwanted invitation would unlock the attacker's next send. The other two
-- are deleteUnacceptedByWorkspaceAndEmail (member removal, reachable by the attacker in
-- their own workspace) and the workspace_id CASCADE (no delete endpoint exists today —
-- correctness that depends on the continued ABSENCE of an endpoint breaks silently in a
-- future ticket).
--
-- NO FOREIGN KEYS AT ALL — not on sender_user_id, not on workspace_id. The whole point
-- of this table is to hold state that OUTLIVES the rows it describes: ON DELETE CASCADE
-- would re-create exactly the hole it exists to close, and RESTRICT would make deleting a
-- user or a workspace fail on throttle bookkeeping. Precedent: failed_email (V7), the same
-- shape — an install-level operational log carrying a recipient address and no FKs.
--
-- NOT WORKSPACE-SCOPED, AND NEVER READ THROUGH A TENANT-FACING SURFACE. This is the shape
-- that causes this project's top bug class, so the invariant is explicit: the repository
-- exposes ONLY aggregate counts, no entity is put in a DTO, and no endpoint reads it.
-- workspace_id is WRITTEN AND NEVER QUERIED — it is there so an operator answering "who
-- did this?" after the MailDailyVolumeHigh alert has the answer in the database, which the
-- metrics (bounded cardinality, no ids) deliberately cannot give them. If a future
-- findBy... returning rows appears on the repository, that is the bug.
--
-- TWO ADDRESS COLUMNS, AND THE CEILINGS COUNT THE KEY. recipient_email is exactly what was
-- submitted (lower-cased) — it is what a refusal echoes and what an operator needs after an
-- alert. recipient_key is MailAddresses.throttleKey(): one key per INBOX rather than one per
-- spelling. Without it every ceiling here is decorative, because victim+1@, victim+2@ and
-- v.i.c.t.i.m@googlemail.com are distinct strings that reach one human, so the attack this
-- table exists to bound is re-spelled at the cost of one keystroke. Over-folding is the
-- FAIL-SAFE direction for a ceiling (an extra match refuses sooner); the opposite is true on
-- the invite REDEMPTION path, where an extra match would let the wrong person accept — do
-- not carry either argument across. Mail is always sent to recipient_email, never to the key.
--
-- email_type FROM DAY ONE, and the table is named for MAIL rather than for invitations,
-- because HD-202 (per-address throttle on forgot-password / resend-verification) is the
-- same key and the same problem — "a caller chose a stranger's address and we send to it".
-- It becomes two require(...) calls plus config, and never has to reopen this migration.
-- Its ceilings are counted PER email_type (a separate bucket per kind of mail), so a reset
-- flood cannot consume an invitation allowance — and so one address can receive the invite
-- cap PLUS the reset cap PLUS the verification cap in a day. That is the intent, not an
-- oversight; a shared bucket would let a stranger's invitations suppress a victim's own
-- password reset.
--
-- FOR WHOEVER ADDS HD-133's UNIQUE(workspace_id, email) TO workspace_invites: it is not in
-- the schema today, and the throttle records its event BEFORE that insert in the same
-- transaction. A constraint violation there would roll back the recorded event, handing
-- callers a free way to probe the ceilings without spending them. The duplicate check has to
-- move ABOVE inviteThrottle.require, not below it.
--
-- Standing rules: VARCHAR, never CHAR(n) and never a PG ENUM (Hibernate 7 cast errors);
-- id is an app-generated UUID v7; created_at carries DEFAULT NOW() as a raw-SQL safety net
-- while JPA sets it through @CreatedDate auditing. Rows are swept on a retention window
-- (app.invites.event-retention-days), so this is not a durable who-emailed-whom store.

CREATE TABLE mail_send_events (
    id              UUID          PRIMARY KEY,           -- app-generated UUID v7
    email_type      VARCHAR(40)   NOT NULL,              -- ProductMetrics.EmailType name (validated app-side)
    recipient_email VARCHAR(320)  NOT NULL,              -- exactly as submitted, lower-cased; echoed by refusals
    recipient_key   VARCHAR(320)  NOT NULL,              -- MailAddresses.throttleKey(); the ONLY thing counted
    sender_user_id  UUID,                                -- NULL for anonymous senders (HD-202's two flows)
    workspace_id    UUID,                                -- forensic breadcrumb only; written, never queried
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- The throttle's only read: every ceiling is scoped to one recipient KEY, so one index
-- answers both of them in a single scan over at most a day of rows. Deliberately not on
-- recipient_email — that column is never a predicate.
CREATE INDEX idx_mail_send_events_recipient ON mail_send_events (recipient_key, created_at DESC);
-- Forensic, not hot: the throttle never keys on the sender (the per-sender volume budget
-- is in-memory, ADR-0015). It is here because an operator working an incident under time
-- pressure asks "what else did this account send?" after MailDailyVolumeHigh fires, and
-- this table is the only place that can answer.
CREATE INDEX idx_mail_send_events_sender    ON mail_send_events (sender_user_id, created_at DESC);
-- The retention sweep.
CREATE INDEX idx_mail_send_events_created   ON mail_send_events (created_at);
