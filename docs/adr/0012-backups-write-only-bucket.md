# ADR-0012: Backups go to a separate bucket the machine can only write to; the retention period is set by an S3 lifecycle rule

Record date: 2026-08-26
Status: Accepted
Source: `docs/design/production-backups-proposal.md` §3, §6, §7 (HD-187);
the existing attachments policy — `docs/ops-prod-hardening.md` §1

## Context

Attachments already lie in a private S3 bucket, accessed through the `hamstrack-ec2` instance role,
which has `s3:PutObject`, `s3:GetObject` and `s3:DeleteObject` on that bucket. A
second kind of object appears — daily logical dumps of the database. The obvious solution is to put them
under a prefix in the same bucket and reuse the same policy.

What makes this a fork:

- A dump contains **everything at once**: the data of all workspaces, the addresses and password hashes of all
  users, and together with `pg_dumpall --globals-only` — also the SCRAM verifiers of the DB
  roles. One object = a full export of the product.
- Whoever got the machine also got its instance role. The role's permissions are what an attacker
  inherits automatically.
- The first move of a ransomware operator is to delete the backups with the very credentials the victim
  helpfully attached to the server.
- The retention period can be implemented in two ways: by a cleanup from the script itself, or by a
  bucket lifecycle rule. This looks like a matter of taste, but it is a question of who is able
  to erase the history.

## Decision

**A separate private bucket** (not a prefix in the attachments bucket), and a **second inline policy**
`backups-s3` is hung on the instance role (the existing `attachments-s3` is not touched):

- `s3:PutObject` and `s3:AbortMultipartUpload` are allowed on `daily/*` and `manual/*`;
- `s3:ListBucket` is allowed with a condition on the prefix — only so that the operator can verify the
  installation from the machine;
- `s3:GetObject` is **not** allowed — the machine cannot read a single byte of a backup;
- `s3:DeleteObject` and anything from `s3:Put*` on the bucket settings are **not** allowed — the machine
  can neither erase the history nor weaken the bucket.

Bucket settings: public access block, **versioning enabled**, **SSE-S3 (AES256)**
encryption, a bucket policy of `Deny` when `aws:SecureTransport=false`.

**The retention period is a property of the bucket, not of the script:** a lifecycle rule deletes objects under
`daily/` after **30 days** (noncurrent versions — after 7). The `manual/` prefix is covered by no
rule: what a human saved deliberately (for example, a dump before the Flyway chain was squashed
in HD-188) is deleted only by a human with the account's credentials.

Integrity is verified by comparing the local MD5 with the ETag from the `put-object` response. This
works only for a single PUT into a bucket with SSE-S3 — and that is the second reason to choose SSE-S3
rather than SSE-KMS: the check does not require `s3:GetObject`, that is, it does not break the
"write-only" property.

## Consequences

+ A compromise of the instance gives neither the ability to download the archive nor to destroy it.
+ A bug in the application working with the attachment storage physically cannot touch the backups.
+ The end-to-end integrity check costs not a single additional permission.
+ `daily/` and `manual/` have different lifetimes without a single line of code.
+ 30 days is also the answer about the deletion of personal data: backups expire by themselves, no
  surgery on them is required.
− A second bucket: one more name, one more policy, one more line in the runbook.
− The retention period cannot be changed from the machine — only with the account's credentials. This is deliberate.
− The "ETag = MD5" check will stop being true on a move to multipart or to SSE-KMS;
  this is written in the script right above the check.

## Alternatives

- **A `backups/` prefix in the attachments bucket** — rejected: it inherits `GetObject` and
  `DeleteObject` from the application's policy, that is, it hands the archive to whoever got the machine and
  lets it be erased.
- **The script deletes old objects itself** — rejected: to delete its own old dumps, the role
  needs `s3:DeleteObject` on the prefix, and that is exactly the permission with which all the dumps are deleted.
  A rule executed by AWS is one the machine cannot change.
- **SSE-KMS instead of SSE-S3** — rejected: a paid call per object, one more key policy
  able to quietly break the restore on exactly the day it is needed, and the loss of the
  free integrity check. Revisit if a requirement for a customer-managed key appears.
- **S3 Object Lock (immutability)** — not adopted; it is enabled only at bucket creation,
  so it is moved into the spec's open questions as an owner's decision. The recommendation is not to
  enable it: the machine cannot delete anyway, and an immutable object that survived a request for
  the deletion of personal data turns a security problem into a legal one.
