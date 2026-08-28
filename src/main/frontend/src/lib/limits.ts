/**
 * Request-field length bounds the SPA mirrors from the server (HD-171 §11).
 *
 * Every number here is a **copy** of a bound declared in Java; the server is the
 * guarantee and this module is only the courtesy that stops a user from meeting
 * it after typing rather than while typing. Nothing mechanical keeps the two
 * sides equal, so each constant names the annotation it mirrors — change one and
 * the javadoc tells you which other file to open.
 *
 * The bounds live in one module rather than as literals at each call site
 * because `PROSE_MAX_LENGTH` has four readers and would otherwise be four
 * numbers that only look like one, and because the password rule cannot be a
 * number at all (see below).
 */

/**
 * A block of prose this product stores: issue description, comment body,
 * project description, workflow description.
 *
 * Mirrors `@Size(max = 10000)` on `CreateIssueRequest.description`,
 * `UpdateIssueRequest.description`, `CreateCommentRequest.body`,
 * `Create/UpdateProjectRequest.description` and
 * `UpsertWorkflowRequest.description` — and `FieldValueService`'s TEXTAREA
 * bound, which is deliberately the same number so a TEXTAREA custom field and
 * an issue description refuse alike.
 */
export const PROSE_MAX_LENGTH = 10000

/**
 * The most UTF-8 **bytes** a password may cost — mirrors
 * `PasswordLimits.MAX_PASSWORD_BYTES`, which exists because
 * `BCryptPasswordEncoder.encode` throws above it.
 *
 * <strong>Bytes, not characters, and that is the whole point.</strong> An
 * `input`'s `maxLength` counts UTF-16 code units, so a 72-character bound stops
 * an ASCII password at exactly the right place and lets a Cyrillic passphrase
 * through at ~36 characters and a CJK one at ~24 — the server then answers 422
 * for a field the UI said was fine. {@link passwordByteLength} is what the two
 * password-writing pages check instead, which is strictly stronger than the
 * server's own `@Size(max = 72)`: a value of at most 72 bytes is always at most
 * 72 UTF-16 units, because no unit encodes to fewer than one byte.
 *
 * This bound belongs to the doors that **write** a password (register, reset).
 * `LoginPage` deliberately has no password bound of any kind — `LoginRequest`
 * accepts 1024 so that a reading door accepts whatever any writing door once
 * produced, and a 72 there would lock out an account rather than refuse a form.
 */
export const PASSWORD_MAX_BYTES = 72

const encoder = typeof TextEncoder === 'undefined' ? null : new TextEncoder()

/** How many UTF-8 bytes this password costs — the unit the encoder measures in. */
export function passwordByteLength(password: string): number {
  if (!password) return 0
  if (encoder) return encoder.encode(password).length
  // No TextEncoder (very old engines, some test shims): count the code points
  // by hand rather than silently reporting a character count as a byte count.
  let bytes = 0
  for (const ch of password) {
    const cp = ch.codePointAt(0) ?? 0
    bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4
  }
  return bytes
}

/**
 * The inline refusal for an over-long password, or `undefined` when it fits.
 *
 * It names the arithmetic for the same reason the server's 422 does: "72 bytes"
 * is not something a person who has typed 40 characters can evaluate, and a
 * refusal may only prescribe an action its reader can perform.
 */
export function passwordLengthError(password: string): string | undefined {
  const bytes = passwordByteLength(password)
  if (bytes <= PASSWORD_MAX_BYTES) return undefined
  return `This password is ${bytes} bytes and the limit is ${PASSWORD_MAX_BYTES}. `
    + 'Accented, Greek and Cyrillic letters cost 2 bytes each, most other scripts 3 and emoji 4 — '
    + 'so a passphrase can be well under 72 characters and still be over the limit.'
}
