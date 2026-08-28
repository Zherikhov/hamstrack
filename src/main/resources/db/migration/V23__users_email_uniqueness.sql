-- ---------------------------------------------------------------------------
-- HD-167 (Account identity is a database guarantee, not a convention)
-- ---------------------------------------------------------------------------
-- users.email IS the account: it is what a person types to log in, what a reset
-- link is mailed to, and what WorkspaceService.acceptInvite matches an
-- invitation against with equals. Its uniqueness is byte-exact in the schema
-- (users_email_key) and case-insensitive only BY CONVENTION -- three writers
-- that each remember to fold (AuthService.register, AdminUserService.create,
-- DataSeeder.run's admin seed -- there is no seedAdmin method, older prose names
-- one). HD-120 already found one way that convention breaks in
-- silence (the fold read the JVM default locale, so a Turkish container stored a
-- dotless i) and fixed the CODE without making the rule enforceable. An
-- invariant that holds only while every writer remembers it is not an invariant,
-- and the writers this project has already named as future work -- LDAP/SSO
-- provisioning, admin bulk import, a support script -- all insert accounts from
-- a foreign source.
--
-- Full design: docs/design/email-uniqueness-proposal.md. The fork itself
-- (constraint vs type) is ADR-0016.
--
-- AN INDEX, NOT citext, AND THE DECIDING REASON IS ABOUT READS RATHER THAN
-- WRITES. A constraint changes only what the database REFUSES; a TYPE changes
-- every comparison the column takes part in -- including the ones this project
-- made exact ON PURPOSE. AuthService.login resolves an account by exact match
-- because HD-120's rule is that a RESOLUTION compares exactly: an extra match
-- there lets the wrong person in. Under citext that lookup silently becomes
-- case-insensitive, and on a database where a foreign writer left Ivan@x.com and
-- ivan@x.com as two different people -- exactly what the pre-V23 schema
-- permits -- the typed address resolves to whichever row the planner returns.
-- The guarantee and the hazard would arrive in the same commit, and the hazard
-- would sit on the authentication path. (A nondeterministic ICU collation is the
-- same objection in a stronger form, plus it forbids LIKE on the column and pins
-- account identity to an ICU version.)
--
-- AND WHY lower() IS SAFE HERE, GIVEN THAT THIS PROJECT'S OWN OPERATOR MANUAL
-- WARNS AGAINST IT. docs/self-hosting.md says, of a hand-run lookup: "Do NOT
-- wrap it in lower(): that folds under the DATABASE's collation, and on a tr_TR
-- cluster it reproduces this very bug from the SQL side." That warning is
-- CORRECT and it does not sink this index. The deciding reason is
-- UNCONDITIONAL, and it goes first precisely because the second one is not:
--
--   EVERY COMPARISON THAT MATTERS APPLIES THE SAME lower() TO BOTH SIDES. The
--   write-side check asks lower(stored) = lower(:typed); this index enforces
--   uniqueness of lower(stored); the value inserted is :typed. All three go
--   through PostgreSQL's lower(), on the same cluster, in the same session, and
--   Java's Locale.ROOT fold is on NEITHER side of that comparison. So the
--   application's check and the database's guarantee CANNOT DISAGREE about
--   whether an address is free -- for any input, under any LC_CTYPE, on any
--   provider. A provider whose lower() differs from another's can therefore
--   only MANUFACTURE A COLLISION -- a 409, visible, told to the caller -- and
--   never a false "free". The warning quoted above is about a fold of an
--   ARBITRARY TYPED value compared against a RAW stored one: one side folded,
--   one side not. It is the asymmetry that is dangerous there, not lower().
--
-- SUPPORTING EVIDENCE, AND NARROWER THAN IT LOOKS: on a value the application
-- has already folded, lower() is in practice the identity function, so the
-- index keys equal the stored values. The characters whose lower-case mapping
-- varies across locales and providers are UPPERCASE ones -- I -> i/dotless-i,
-- dotted-I -> i/i+dot ("exactly two characters can differ", same document) --
-- and a value written through any of the three writers contains none.
--
--   BUT NOTE WHOSE UNICODE TABLES THAT READS. "Contains no uppercase" is a
--   property under JAVA's tables (Java 21 = Unicode 15.1), while lower() reads
--   the PROVIDER's (glibc 2.28 is ~Unicode 11, ICU 72-75 ~15-15.1). Identity
--   therefore requires the provider to know no case mapping the JDK lacks --
--   a Unicode-data-version claim, not an "uppercase only" one. It holds today
--   and on every cluster this was measured on. The direction that would break
--   it is a provider AHEAD of the JDK, and its consequence is the benign one
--   above: a manufactured collision, i.e. step 1 refusing an upgrade over a
--   legitimate internationalised address. THE CHOICE OF INDEX OVER citext DOES
--   NOT REST ON THIS PARAGRAPH -- it rests on the unconditional property, which
--   survives any provider.
--
-- citext's exposure is the one that is genuinely unbounded: it preserves the
-- stored case and re-evaluates lower() on the RAW value at every comparison,
-- including the ones this project made exact on purpose. (A collation provider
-- change still wants REINDEX -- a btree finds duplicate candidates by sort
-- order -- and docs/self-hosting.md carries that one procedure for this index
-- and workspace_invites_pending_email_uk together.)
--
-- WHAT THIS FILE MUST NOT SHIP WITHOUT. A 23505 that reaches
-- GlobalExceptionHandler is answered 500, deliberately and in writing. This
-- index adds a SECOND constraint on users that can fire on the signup INSERT,
-- so it ships together with the 23505 -> 409 translation at both writers
-- (AuthService.register, AdminUserService.create, via EmailUniqueness) and with
-- write-side existence checks that ask the question in the SAME EXPRESSION the
-- index answers it in (UserRepository.existsByFoldedEmail / findByFoldedEmail).
-- Those checks fold for a counterfactual reason worth keeping: an EXACT
-- pre-check would say "free" where the index says "taken", so an ordinary
-- signup would run a DOOMED INSERT. Stated exactly, because the translation
-- above ships in the same commit: that INSERT is answered 409 at the cost of a
-- wasted round-trip, and it becomes a 500 the day the translation stops
-- matching (a renamed constraint, a new writer that forgets it) -- which is the
-- reason the refusal must not depend on the translation being right. Folding
-- the checks is what moves a stored mixed-case row out of the INSERT and into a
-- 409 the pre-check owns.
--
-- AND WHAT THE TRANSLATION ACTUALLY COVERS IS THE RACE, NOT A SQUATTER. An
-- earlier draft of this header said the index fires on an ORDINARY FIRST-TIME
-- SIGNUP against a database holding a mixed-case squatter, because "the folded
-- pre-check finds nothing". That is MEASURED FALSE (2026-08-28: Bob@x.com
-- inserted by direct SQL, then bob@x.com registered -- 409 from the pre-check,
-- and the log shows the SELECT and nothing after it). The pre-check and this
-- index ask the same question of the same function, so THEY CAN DIFFER ONLY BY
-- THE WINDOW BETWEEN THEM -- AND A WINDOW IS A RACE, NOT A FOLD. The reachable
-- trigger is two concurrent registrations of one address, reproduced with a
-- pg_sleep BEFORE INSERT trigger: real 23505s on both constraint names, both
-- answered 409, bodies byte-identical to the pre-check's.
--
-- That is still why the translation is mandatory rather than optional: the race
-- 500s TODAY on users_email_key, and this file adds a second name it can fail
-- under. Both names are translated -- users_email_key as well as
-- users_email_lower_uk -- because they are two spellings of one answer and the
-- caller must not be able to tell which fired.
--
-- users_email_key STAYS. It is redundant as a CONSTRAINT (byte-equal values are
-- also fold-equal) and not redundant as an INDEX: it is the access path for
-- WHERE email = ?, which is the comparison login/forgotPassword/acceptInvite
-- keep exact, and an index on lower(email) cannot serve it. Two indexes, two
-- jobs.
--
-- DO NOT MIRROR THIS ON THE ENTITY. JPA cannot express a functional unique
-- constraint; @Table(uniqueConstraints = ...) would declare a rule the schema
-- does not have, and @Column(unique = true) on User.email already covers
-- users_email_key. ddl-auto=validate is unaffected -- Hibernate validates
-- columns and types, not expression indexes. The entity change is javadoc only.
--
-- MEASURED BEFORE SHIPPING, 2026-08-28. PRODUCTION over SSM, PostgreSQL 16.15,
-- server_encoding=UTF8: 5 users; 0 rows where email <> lower(email); 0 U+0131;
-- 0 U+0130; 0 non-ASCII; 0 fold collisions. Indexes on users: users_pkey,
-- users_email_key -- no lower(email) index. So step 1 below is a NO-OP on every
-- database we can see and exists for the ones we cannot: a self-hosted install
-- whose users table some other writer touched, or that ran a pre-0.16.0 build
-- under LANG=tr_TR.UTF-8. IF IT EVER ABORTS, THAT IS THE FINDING, NOT THE FIX.
--
-- Standing rules: no PG ENUM, no CHAR(n), no new column -- so no UUID-v7 or
-- @CreatedDate obligation arises here. Plain CREATE UNIQUE INDEX, not
-- CONCURRENTLY: the latter cannot run inside a transaction and Flyway runs each
-- migration in one. It takes SHARE on users for the duration of one build --
-- milliseconds on a table bounded by a headcount -- but as V22 said of its own
-- lock, THE SIZE ARGUMENT IS NOT WHAT KEEPS THIS SAFE: deploy.yml runs
-- `docker compose up -d`, which stops the old container before starting the new
-- one, so no instance serves traffic while Flyway runs. The condition to watch
-- is a ROLLING DEPLOY OR A SECOND REPLICA (docs/design/p2-scaleout-proposal.md),
-- not a row count -- and there the failure is the benign one: a signup committed
-- inside the window fails the index build outright, Flyway rolls this file back
-- whole, and startup fails loudly and re-runnably.

-- ---------------------------------------------------------------------------
-- 1. Pre-flight: refuse if any stored address is not already its own fold.
--    NOTHING IS REPAIRED, FOLDED OR DELETED HERE OR ANYWHERE BELOW.
-- ---------------------------------------------------------------------------
--    THE GENERATING RULE, and it is the whole reason this step refuses instead
--    of fixing: A MIGRATION MAY REPAIR WHAT ITS OWN APPLICATION CAN RECREATE,
--    AND MUST REFUSE WHAT IT CANNOT. V22 deleted mixed-case INVITATIONS (HD-133)
--    because a deleted offer is recoverable -- by the same administrator,
--    from the same screen, in two clicks. NOTHING RECREATES AN ACCOUNT: it owns
--    issues, comments, memberships, sessions and history.
--
--    AND THE GENTLER-LOOKING OPTION IS THE WORSE ONE, for the reason V22 gave
--    one step up in severity. `UPDATE users SET email = lower(email)` keeps the
--    account and looks strictly kinder. It is not: Bob@x.com and bob@x.com are
--    two different mailboxes on any RFC-compliant server, so folding in place
--    changes WHICH MAILBOX CAN RESET THAT ACCOUNT'S PASSWORD. V22 refused to let
--    a migration silently change who an OFFER reaches; this is the same act
--    against an ACCOUNT, and the argument gets stronger as the object gets more
--    valuable. So the fold is offered to the operator as a remedy, in a message,
--    and never taken by this file.
--
--    THIS REFUSES ON EVERY MIXED-CASE ROW, NOT ONLY ON COLLIDING ONES, and that
--    is deliberately stricter than "would the index build succeed". A lone
--    non-colliding mixed-case row is not harmless: its owner already cannot log
--    in (every lookup folds the typed address first) and already cannot receive
--    a reset mail (forgotPassword folds too); and from V23 onward it SQUATS THE
--    FOLDED KEY, so the correct spelling becomes unregisterable -- a 409 for an
--    address nobody holds, which no operator would ever connect to this row.
--    The upgrade is the only moment anyone will look. The cost is a whole
--    upgrade blocked by one harmless-looking row, and it is bought off by
--    docs/self-hosting.md carrying the same query as a PRE-upgrade check, so an
--    operator who runs it never meets this block.
--
--    AN EXPLICIT PRE-FLIGHT IS REQUIRED, NOT DECORATIVE. The tempting version of
--    this step is to skip it and let CREATE UNIQUE INDEX fail by itself: it is
--    atomic and it does abort. But V22 took away the part that made it
--    actionable -- since then the datasource runs logServerErrorDetail=false, so
--    PostgreSQL's DETAIL never reaches the application log and a failed index
--    build names THE INDEX AND NOT THE COLLIDING ROWS. A refusal may only
--    prescribe an action its reader can perform.
--
--    AND THE GATE RETIRES THAT FAILURE MODE ENTIRELY -- BY ALGEBRA, NOT BY
--    SEMANTICS. The gate is `unfolded > 0`. If a = lower(a) and b = lower(b) and
--    lower(a) = lower(b), then a = b -- which users_email_key already forbids.
--    So `unfolded = 0` IMPLIES STEP 3 CANNOT FAIL, on any database this
--    pre-flight passes, and the implication assumes nothing whatever about what
--    lower() means on any provider. The un-actionable index-build failure is
--    therefore not merely made rarer here; it is made unreachable.
--
--    THAT IS ALSO THE ANSWER TO "WHY IS collisions COMPUTED IF IT DOES NOT
--    GATE". It cannot add a refusal the unfolded count does not already make --
--    every colliding group contains at least one row that is not its own fold,
--    by the same algebra. It is reported because it SELECTS THE REMEDY: one
--    UPDATE, or a decision about two people.
--
--    THE MESSAGE CARRIES COUNTS AND QUERIES, NEVER ADDRESSES. Its reader is at a
--    database prompt by definition -- they are running the upgrade, and every
--    remedy below needs that prompt -- so a SELECT is a performable remedy,
--    while third-party email addresses written into a shipped log are not
--    something this project does (the same domain-only rule WorkspaceService
--    applies to its own invite send line, and the same reason
--    logServerErrorDetail=false shipped one release earlier). And a count scales
--    where an enumeration does not -- the population is unbounded, and an
--    operator with 400 broken rows needs a query, not a 400-address sentence.
--
--    Flyway runs this file in one transaction, so RAISE EXCEPTION aborts it
--    whole: the index does not exist and EVERY users ROW IS STILL THERE. On
--    PostgreSQL the schema-history row is written INSIDE that transaction, so it
--    rolls back with it -- NO success=false ROW IS LEFT AND THERE IS NOTHING TO
--    REPAIR (measured: history max stays at 22, with no V23 row at all). The
--    application does not start, and a re-run after the operator acts starts
--    from exactly the same state, with no `flyway repair` step in between.
DO $$
DECLARE
    unfolded       BIGINT;
    collisions     BIGINT;
    collision_help TEXT;
BEGIN
    SELECT count(*) INTO unfolded FROM users WHERE email <> lower(email);
    SELECT count(*) INTO collisions FROM (
        SELECT lower(email) FROM users GROUP BY 1 HAVING count(*) > 1
    ) c;

    IF unfolded > 0 THEN
        -- A LONE MIXED-CASE ROW IS THE LIKELIER CASE, AND IT MUST NOT BE HANDED A
        -- QUERY THAT RETURNS NOTHING. An operator who runs a query the refusal
        -- itself offered and gets an empty result reads it as "the tool is
        -- broken", not as "there is nothing here" -- so the collision half of the
        -- remedy is printed only when there ARE collisions, and the other branch
        -- says so in one line instead.
        IF collisions > 0 THEN
            collision_help := $c$FIND THE COLLISIONS:
  SELECT lower(email) AS folded, count(*) AS copies, array_agg(id ORDER BY created_at) AS ids, array_agg(email ORDER BY created_at) AS addresses FROM users GROUP BY 1 HAVING count(*) > 1;

THEN, IN THIS ORDER:
  1. RESOLVE EVERY COLLISION FIRST. Only a human can decide which of two accounts survives, and the answer is re-address or disable, never delete -- issues.reporter_id, comments.author_id and invited_by all reference users with no ON DELETE. docs/self-hosting.md, section "Duplicate accounts after an upgrade", ships the block that retires one row of a pair.
  2. THEN fold what is left: UPDATE users SET email = lower(email) WHERE email <> lower(email);

THE ORDER IS LOAD-BEARING. At this moment the index does not exist, so doing 2 before 1 does not fail safely: a blind fold over a colliding pair SUCCEEDS in producing two identical addresses and is only then refused by users_email_key, which reads like a different bug.$c$;
        ELSE
            collision_help := $c$NO TWO STORED ADDRESSES FOLD TOGETHER, so there is nothing to decide first and the whole remedy is one statement:
  UPDATE users SET email = lower(email) WHERE email <> lower(email);$c$;
        END IF;

        RAISE EXCEPTION $msg$HD-167 V23 aborted. NOTHING HAS BEEN APPLIED AND NO ACCOUNT HAS BEEN CHANGED.

This migration adds UNIQUE (lower(email)) on users, and this database holds % row(s) whose stored address is not already its own lower-case form, in % colliding fold group(s). Such a row cannot log in today and cannot receive a reset mail (both fold the typed address first), and from this migration onward it would silently occupy the folded key -- making the correct spelling unregisterable, as a 409 for an address nobody holds.

This migration repairs nothing on purpose: a migration may repair what its own application can recreate, and must refuse what it cannot -- and nothing recreates an account.

FIND THE ROWS:
  SELECT id, email, status, created_at FROM users WHERE email <> lower(email) ORDER BY created_at;

%

Folding an address changes WHICH MAILBOX CAN RESET THAT ACCOUNT'S PASSWORD, so it is your decision and not this migration's. Re-run the upgrade once the first query above returns no rows; nothing was recorded, so there is no repair step and no failed row to clean up.

THE WHOLE PROCEDURE, INCLUDING WHY A ROW THAT COLLIDES WITH NOTHING STILL BLOCKS: docs/self-hosting.md, section "Account addresses become case-insensitive in 0.18.0".$msg$,
            unfolded, collisions, collision_help;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. Advisory only: a non-ASCII address may be perfectly legitimate, or may be
--    a pre-0.16.0 locale-folded row. Reported, never rewritten, never blocking.
-- ---------------------------------------------------------------------------
--    THIS IS A DIFFERENT POPULATION FROM STEP 1, AND STATING THAT IS THE POINT
--    OF THIS BLOCK. Step 1 finds a CASE break -- a value that is not its own
--    fold. The failure HD-120 actually hit is a LOCALE break: a Turkish JVM
--    folded I to a dotless i (U+0131), which is ALREADY LOWERCASE, so lower()
--    leaves it alone, step 1's predicate is false, and the row reads clean --
--    while the application, folding the typed I to i with Locale.ROOT, never
--    matches it. The account is unreachable and the case check says nothing is
--    wrong.
--
--    IT IS NOT DETECTABLE FROM THE STORED VALUE AT ALL, and this notice does not
--    pretend otherwise. The stored value is a legal lower-case address; it is
--    wrong only relative to a Locale.ROOT fold of the TYPED address, and the
--    typed address was never stored. The predicate below is therefore a PROXY:
--    it is the fingerprint of a locale-dependent fold AND of a perfectly
--    legitimate internationalised address, and no query can tell those apart.
--    It also cannot see the dotted-capital case at all, whose old spelling is
--    pure ASCII.
--
--    SO IT MUST NOT BLOCK. The index neither fixes such a row nor is blocked by
--    one, the address is legal, and refusing an upgrade over it would be a
--    refusal whose only performable remedy is "stop using your own alphabet".
--    docs/self-hosting.md already ships the proxy query, the pair query and both
--    lone-row remedies; this notice points there rather than inventing a second
--    procedure.
DO $$
DECLARE
    non_ascii BIGINT;
BEGIN
    SELECT count(*) INTO non_ascii FROM users WHERE email ~ '[^\x00-\x7F]';

    IF non_ascii > 0 THEN
        RAISE NOTICE $msg$HD-167: % users row(s) hold a non-ASCII address. Nothing has been changed and this does not block the upgrade. It is a FLAG, NOT A VERDICT: an internationalised address is perfectly legal and Hamstrack accepts it. It is also the fingerprint of the pre-0.16.0 locale-folded row HD-120 fixed (a Turkish JVM stored IT-Admin@corp.com as a dotless-i address), and NO QUERY CAN TELL THE TWO APART -- the stored value is a legal lower-case address, wrong only relative to a fold of a typed address that was never stored. If somebody cannot log in, read docs/self-hosting.md, section "Duplicate accounts after an upgrade": it ships both queries and all three remedies. (Why this migration prints the notice at all, and why it cannot be a verdict, is in the section "Account addresses become case-insensitive in 0.18.0" of the same document.)$msg$,
            non_ascii;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 3. The guarantee.
-- ---------------------------------------------------------------------------
--    NON-PARTIAL, unlike V22's invite index: an accepted invitation stops being
--    an offer, but there is no state in which an account stops being an account.
--    A DISABLED user keeps its row and its slot -- correctly, because the
--    address is still spoken for and re-enabling is the remedy.
--
--    A future writer that forgets the boundary fold now fails CLOSED: it cannot
--    create the duplicate, it can only report it badly. What the index cannot
--    stop is such a writer taking a FREE folded key with a mixed-case row --
--    only CHECK (email = lower(email)) would, and that is deliberately deferred
--    (it would pin every application write to PostgreSQL's lower() rather than
--    to Locale.ROOT's fold, and a 23514 has no other row to name, so it could
--    only be a bare, deployment-dependent 500). ADR-0016 records that residual.
CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email));
