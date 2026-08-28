import { describe, it, expect } from 'vitest'
import {
  PASSWORD_MAX_BYTES, PROSE_MAX_LENGTH, passwordByteLength, passwordLengthError,
} from './limits'

// HD-171 §11. The password rule is a BYTE count and the trap is that it looks
// like a character count: `maxLength={72}` stops an ASCII password at exactly
// the right place and lets a Cyrillic one through at 36 characters, where the
// server answers 422. These assertions are the client half of the same
// arithmetic the server does in PasswordLimits.
describe('password length is measured in UTF-8 bytes', () => {
  it('counts Latin at 1 byte, Cyrillic at 2, CJK at 3 and emoji at 4', () => {
    expect(passwordByteLength('abcd')).toBe(4)
    expect(passwordByteLength('пароль')).toBe(12)
    expect(passwordByteLength('密码')).toBe(6)
    expect(passwordByteLength('🐹')).toBe(4)
    expect(passwordByteLength('')).toBe(0)
  })

  it('accepts exactly 72 bytes and refuses 73 — the bound is exact, not approximate', () => {
    expect(passwordLengthError('a'.repeat(PASSWORD_MAX_BYTES))).toBeUndefined()
    expect(passwordLengthError('a'.repeat(PASSWORD_MAX_BYTES + 1))).toBeDefined()
  })

  it('refuses a 37-character Cyrillic passphrase that a 72-character bound would pass', () => {
    // 37 × 2 = 74 bytes, 37 UTF-16 units. This is the case that proves the
    // check cannot be a `maxLength`, and the one the server answers 422 to.
    const cyrillic = 'п'.repeat(37)
    expect(cyrillic.length).toBeLessThan(PASSWORD_MAX_BYTES)
    expect(passwordLengthError(cyrillic)).toBeDefined()
    // …and 36 of them are exactly 72 bytes, so they are accepted.
    expect(passwordLengthError('п'.repeat(36))).toBeUndefined()
  })

  it('is never weaker than the server @Size(max = 72), which counts UTF-16 units', () => {
    // A value of at most 72 bytes is always at most 72 units, so anything this
    // check lets through also satisfies the annotation — the client can never
    // send a password the edge refuses with a 400 it did not predict.
    for (const p of ['a'.repeat(72), 'п'.repeat(36), '密'.repeat(24), '🐹'.repeat(18)]) {
      expect(passwordLengthError(p)).toBeUndefined()
      expect(p.length).toBeLessThanOrEqual(PASSWORD_MAX_BYTES)
    }
  })

  it('names the actual size and the arithmetic, because "72 bytes" is not actionable alone', () => {
    const message = passwordLengthError('п'.repeat(40))
    expect(message).toContain('80 bytes')
    expect(message).toContain(String(PASSWORD_MAX_BYTES))
    expect(message).toMatch(/Cyrillic/)
  })
})

describe('prose bound', () => {
  // Mirrors @Size(max = 10000) on the five prose DTO fields AND
  // FieldValueService's TEXTAREA bound — one number for "a block of prose this
  // product stores". If the server raises one, this is the client copy to raise.
  it('is 10000', () => {
    expect(PROSE_MAX_LENGTH).toBe(10000)
  })
})
