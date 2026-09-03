/**
 * Byte and percentage rendering, in one place (HD-191).
 *
 * The storage endpoints send **raw integers** and say so — "formatting is the
 * client's business" (write-budget-and-storage-quota-proposal §8.1). That is a
 * deliberate contract: a server that shipped `"9.8 GB"` would have decided the
 * base, the precision and the language for every future reader. So the sizes a
 * user reads are computed here, and nowhere else.
 *
 * **Base 1024, and the unit names that go with it.** A quota is a bound on what
 * a filesystem or a bucket reports, and both count in binary multiples; a
 * refusal that says "10 GB" over a limit the operator set as `10GB`
 * (`DataSize`, which is also binary) has to arrive at the same number or the
 * page argues with the `.env`.
 *
 * There is exactly one formatter because there was very nearly two: the issue
 * panel had a private `formatBytes` that stopped at MB, so a 3 GB workspace
 * total would have rendered as `3072.0 MB` on one surface and `3 GB` on the
 * other — the same figure, twice, disagreeing.
 */

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'] as const

/** `1024` → `1 KB`, `10_737_418_240` → `10 GB`, `13_002_342` → `12.4 MB`. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes)) return '—'
  const sign = bytes < 0 ? '-' : ''
  let value = Math.abs(bytes)
  let unit = 0
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024
    unit++
  }
  // Whole bytes are never fractional; everything above is one decimal, and a
  // trailing `.0` is dropped so a round quota reads "10 GB" rather than "10.0 GB".
  const text = unit === 0 ? String(Math.round(value)) : trimTrailingZero(value.toFixed(1))
  return `${sign}${text} ${UNITS[unit]}`
}

/**
 * `77.5` → `77.5%`, `112` → `112%`.
 *
 * **Never clamped.** A workspace can be over its quota — the operator lowered
 * it, or it was raised past an existing total — and §6.9 is explicit that the
 * page shows that rather than hiding it: the over-100% number is the only thing
 * on screen that explains why uploads are being refused.
 */
export function formatPercent(percent: number): string {
  if (!Number.isFinite(percent)) return '—'
  return `${trimTrailingZero(percent.toFixed(1))}%`
}

function trimTrailingZero(text: string): string {
  return text.endsWith('.0') ? text.slice(0, -2) : text
}
