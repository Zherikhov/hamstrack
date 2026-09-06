import { AlertTriangle } from 'lucide-react'
import type { ShadowedSearchField } from '../../types'

/**
 * HD-275 — the search surface's end of the silence.
 *
 * A custom field whose key a built-in HQL name has taken is unreachable from a
 * query, and *nothing said so*: `/search/schema` simply omitted it, so the only
 * symptom was an absence, while `labels = "x"` kept answering 200 with rows
 * from the built-in label links the tenant never set. This is the one place a
 * member who writes queries — as opposed to an administrator who opens
 * Settings — can find that out.
 *
 * The list it renders arrives in its own `shadowedFields`, never inside
 * `fields`, and must stay out of the autocomplete vocabulary: suggesting one of
 * these keys would put the product's own recommendation behind a name that
 * lies. `HqlInput` reads `schema.fields` only, which is what keeps that true.
 *
 * **Absent, empty and populated are three different things and two of them draw
 * nothing.** A server that predates the field sends no list at all; that means
 * "nothing to warn about", never "warn about everything" and never "hide the
 * search box". Non-blocking by construction: no modal, no dismissal, no
 * disabled input — the queries that do work still work.
 */
export default function ShadowedFieldsNotice({ fields }: { fields?: ShadowedSearchField[] }) {
  if (!fields || fields.length === 0) return null

  return (
    <div
      className="flex items-start gap-2 px-5 py-2.5 border-b flex-shrink-0"
      style={{
        background: 'color-mix(in srgb, var(--color-warning) 10%, white)',
        borderColor: 'var(--color-border)',
      }}
    >
      <AlertTriangle
        size={14}
        aria-hidden="true"
        style={{ color: 'var(--color-warning-ink)', marginTop: 1, flexShrink: 0 }}
      />
      <div className="flex flex-col gap-1" style={{ color: 'var(--color-warning-ink)' }}>
        {/* Past three, one line each stops being a notice and starts being a
            wall. The collapsed form still names every key — the count alone
            would tell a reader something is wrong without telling them which of
            their queries is the one lying to them. */}
        {fields.length > 3 ? (
          <span className="text-xs">
            {fields.length} custom fields in this workspace have keys that are built-in search names
            ({fields.map(f => `“${f.key}”`).join(', ')}) — searching those keys returns built-in data,
            not these fields' values. A workspace admin can rename their keys in Settings → Fields.
          </span>
        ) : (
          fields.map(f => (
            <span key={f.key} className="text-xs">
              “{f.name}” is a custom field in this workspace, but “{f.key}” is a built-in search
              name — searching it returns built-in {f.shadowedBy} data, not this field's values.
              A workspace admin can rename the field's key in Settings → Fields.
            </span>
          ))
        )}
      </div>
    </div>
  )
}
