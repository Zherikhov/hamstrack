import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { X } from 'lucide-react'
import { apiCreateProject } from '../api'
import { useAuthStore } from '../auth'
import {
  CAPABILITY,
  CREATION_ADD_ONS,
  CREATION_BOARD_CAPABILITY,
  CREATION_REVERSIBLE_NOTICE,
  creationDelivery,
  type DeliveryCapability,
  type DeliveryChoice,
} from './delivery'
import { readLastDeliveryChoice, rememberDeliveryChoice } from '../lib/lastDeliveryChoice'
import { Button, Input } from './ui'
import type { BoardMode } from '../types'

interface Props {
  wsId: string
  onClose: () => void
}

/**
 * One card of the delivery picker. Shared by both halves on purpose: the board
 * pair and the add-on toggle differ only in their input type, so the user meets
 * three cards that look alike and one sentence — `independentNote` — that says
 * why the third one behaves differently.
 */
function DeliveryCard({
  control, name, title, blurb, note, selected, disabled, onSelect,
}: {
  control: 'radio' | 'checkbox'
  name: string
  title: string
  blurb: string
  note?: string
  selected: boolean
  disabled?: boolean
  onSelect: () => void
}) {
  return (
    <label
      className="flex items-start gap-3 rounded-lg border cursor-pointer transition-colors"
      style={{
        padding: '12px 14px',
        background: 'white',
        // The selected-card treatment also used by Project settings → Delivery.
        borderColor: selected ? 'var(--color-brand)' : 'var(--color-border)',
        boxShadow: selected ? 'var(--shadow-card)' : undefined,
      }}
    >
      <input
        type={control}
        name={name}
        checked={selected}
        disabled={disabled}
        onChange={onSelect}
        style={{ accentColor: 'var(--color-brand)', marginTop: 3, width: 15, height: 15 }}
      />
      <span className="flex flex-col gap-0.5 min-w-0">
        <span className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>
          {title}
          {note && (
            <span
              className="ml-2 font-medium"
              style={{ fontSize: 11, color: 'var(--color-brand-ink)' }}
            >
              {note}
            </span>
          )}
        </span>
        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          {blurb}
        </span>
      </span>
    </label>
  )
}

/**
 * The delivery picker (HD-105 / §12) — the up-front question, drawn entirely
 * from the capability table in `delivery.tsx`.
 *
 * Nothing here hard-codes what capabilities exist or how they read: the board
 * pair is the row whose creation copy carries an `offCard`, the toggles are the
 * rows that do not, and `estimation` is absent because its row says
 * `atCreation: false` — not because this component forgot it. Adding a
 * capability is a row there, and it appears here with its own copy.
 */
function DeliveryPicker({
  choice, disabled, onChange,
}: {
  choice: DeliveryChoice
  disabled: boolean
  onChange: (choice: DeliveryChoice) => void
}) {
  const board = CAPABILITY[CREATION_BOARD_CAPABILITY].creation
  if (!board.atCreation || !board.offCard) return null

  // "Kanban" is the board capability OFF, "Scrum" is it ON — two named answers
  // to one question, never a feature and its absence (divergence D1).
  const boardCards: { value: BoardMode; title: string; blurb: string }[] = [
    { value: 'KANBAN', ...board.offCard },
    { value: 'SCRUM', title: board.title, blurb: board.blurb },
  ]

  function toggle(capability: DeliveryCapability, on: boolean) {
    onChange({
      ...choice,
      addOns: on
        ? [...choice.addOns, capability]
        : choice.addOns.filter(a => a !== capability),
    })
  }

  return (
    <div className="flex flex-col gap-2.5">
      <div className="flex flex-col gap-1">
        <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>
          How will this team deliver?
        </span>
        {/* Read BEFORE choosing, not after: the sentence that makes an up-front
            question cheap is worthless underneath the answer. */}
        <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          {CREATION_REVERSIBLE_NOTICE}
        </span>
      </div>

      <div className="flex flex-col gap-2.5" role="radiogroup" aria-label="Board">
        {boardCards.map(card => (
          <DeliveryCard
            key={card.value}
            control="radio"
            name="deliveryBoard"
            title={card.title}
            blurb={card.blurb}
            selected={choice.board === card.value}
            disabled={disabled}
            onSelect={() => onChange({ ...choice, board: card.value })}
          />
        ))}
      </div>

      {/* Visually separated from the pair — an add-on is not a third answer. */}
      {CREATION_ADD_ONS.map(capability => {
        const creation = CAPABILITY[capability].creation
        if (!creation.atCreation) return null
        return (
          <DeliveryCard
            key={capability}
            control="checkbox"
            name={`delivery-${capability}`}
            title={creation.title}
            blurb={creation.blurb}
            note={creation.independentNote}
            selected={choice.addOns.includes(capability)}
            disabled={disabled}
            onSelect={() => toggle(capability, !choice.addOns.includes(capability))}
          />
        )
      })}
    </div>
  )
}

export default function CreateProjectModal({ wsId, onClose }: Props) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const userId = useAuthStore(s => s.user?.id)
  const [name, setName] = useState('')
  const [key, setKey] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  /**
   * Read ONCE, in a lazy initializer: the last-preset nudge pre-selects the
   * picker and then has no further say. Re-reading it in an effect is the bug
   * this shape prevents — it would reach in and undo an explicit choice the
   * moment anything re-rendered. With nothing remembered (or anything
   * unreadable) the reader returns the lean default (D4), which lives in
   * `delivery.tsx` and nowhere else.
   */
  const [delivery, setDelivery] = useState<DeliveryChoice>(
    () => readLastDeliveryChoice(userId, wsId),
  )

  function deriveKey(n: string) {
    return n.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6)
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setSaving(true)
    try {
      const project = await apiCreateProject(wsId, {
        name: name.trim(),
        key: key.trim(),
        // Explicit, always — including when it equals the server's own default.
        delivery: creationDelivery(delivery),
      })
      // Only a project that actually exists expresses a preference.
      rememberDeliveryChoice(userId, wsId, delivery)
      await qc.invalidateQueries({ queryKey: ['projects', wsId] })
      onClose()
      navigate(`/w/${wsId}/p/${project.id}`)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to create project')
    } finally {
      setSaving(false)
    }
  }

  const overlayStyle: React.CSSProperties = {
    position: 'fixed', inset: 0, zIndex: 50,
    background: 'rgba(28,27,25,0.55)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    backdropFilter: 'blur(2px)',
  }

  const panelStyle: React.CSSProperties = {
    background: 'white',
    borderRadius: 'var(--radius-lg)',
    border: '1px solid var(--color-border)',
    width: 480,
    maxHeight: 'calc(100vh - 64px)',
    display: 'flex',
    flexDirection: 'column',
    boxShadow: '0 20px 60px rgba(0,0,0,0.18)',
  }

  return (
    <div data-modal-open="true" style={overlayStyle} onClick={onClose}>
      <div style={panelStyle} onClick={e => e.stopPropagation()}>
        <div
          className="flex items-center justify-between px-5 py-4 border-b"
          style={{ borderColor: 'var(--color-border)', flexShrink: 0 }}
        >
          <span className="font-semibold text-sm">New Project</span>
          <button onClick={onClose} className="cursor-pointer hover:opacity-60 transition-opacity">
            <X size={16} style={{ color: 'var(--color-text-muted)' }} />
          </button>
        </div>

        <form onSubmit={submit} className="p-5 flex flex-col gap-4" style={{ overflowY: 'auto' }}>
          <Input
            label="Project name"
            value={name}
            onChange={e => {
              setName(e.target.value)
              if (!key || key === deriveKey(name)) setKey(deriveKey(e.target.value))
            }}
            placeholder="e.g. Backend API"
            autoFocus
            required
          />
          <Input
            label="Key"
            value={key}
            onChange={e => setKey(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 10))}
            placeholder="e.g. BAPI"
            required
          />

          {/* Not a wizard step and never a blocker: a card is always selected and
              Create is never gated on this question. */}
          <DeliveryPicker choice={delivery} disabled={saving} onChange={setDelivery} />

          {error && (
            <p className="text-xs" style={{ color: 'var(--color-error-ink)' }}>{error}</p>
          )}
          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" type="button" onClick={onClose}>Cancel</Button>
            <Button variant="primary" type="submit" loading={saving} disabled={!name || !key}>
              Create project
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
