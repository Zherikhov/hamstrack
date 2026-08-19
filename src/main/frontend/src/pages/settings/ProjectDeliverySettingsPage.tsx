import { useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '../../components/ui'
import { Modal } from '../admin/common'
import { CAPABILITY, CapabilityOffState, useSetCapability } from '../../components/delivery'
import type { DeliveryCapability } from '../../components/delivery'
import { useOpenSprints } from '../../components/sprints'
import { useProjectVersions } from '../../components/versions'
import { useProjectDelivery } from '../../hooks/useProjectDelivery'
import { useProjectPermissions } from '../../hooks/usePermissions'
import type { BoardMode, DeliveryPreset, Sprint, Version } from '../../types'

/**
 * Project settings → **Delivery** (HD-106, replacing the HD-27 Board tab).
 *
 * This is the one screen that owns the direction the earlier slices never had to
 * describe: turning a capability **off** (§13). S3 built enable-on-first-use, so
 * every capability could be reached from the page it belongs to; the way back was
 * a settings row nobody had written yet, which is why the Board tab carried a
 * stopgap list of off capabilities. That stopgap is gone — this page supersedes
 * it, and lists all three capabilities in BOTH states (§6, last row).
 *
 * Three properties this page exists to guarantee:
 *
 *  • **Nothing is destroyed, and the user is told so before the click.** The
 *    confirmations name the live data at risk of *looking* deleted — the sprint
 *    that is running, the versions that have not shipped — and state exactly what
 *    happens to it. §13's invariant is that no `sprint_id` is nulled, no version
 *    link is removed and no point value is erased by any capability change; the
 *    copy's job is to make that invariant believable.
 *  • **The "kept data" notices are LIVE.** "3 unreleased versions kept" is read
 *    from the project's versions, not written into a string — a reassurance with
 *    a made-up number is worse than none. Where no count can be had at all the
 *    sentence simply carries no number (estimation — see *No estimate count*).
 *  • **The preset is displayed and never sent.** It is derived server-side from
 *    the three capabilities (§2.3); every write here is a partial `delivery`
 *    carrying only the member that changed, and a body containing `preset` is a
 *    400 — so nothing may echo back the object a GET returned.
 *
 * Permissions: the whole settings area is curator-only (`ProjectSettingsArea`
 * mirrors `ScopeResolver.requireProjectCurator` and redirects everyone else), but
 * the real role is passed down rather than a literal `true` — the editor must
 * behave correctly wherever it is mounted, not only behind that guard. That
 * applies to EVERY control, not just the off-state cards: for a non-curator the
 * board pair and the "Turn off …" rows collapse to the same read-only statement
 * `CapabilityOffState` shows, so the page never offers a member an action whose
 * only outcome is a 403 — or, on a Kanban project, a Scrum radio whose label is
 * the vocabulary §5.3 withholds from them.
 */

/** The derived label (§2.3), shown as a label — never stored, never sent. */
const PRESET_LABEL: Record<DeliveryPreset, string> = {
  KANBAN: 'Kanban',
  SCRUM: 'Scrum',
  RELEASES: 'Releases',
  CUSTOM: 'Custom',
}

/** The add-on capabilities this page draws as their own rows — the board is a pair. */
const ADD_ONS: DeliveryCapability[] = ['releases', 'estimation']

/**
 * The one sentence every turn-off dialog ends on. §13's last row — *re-enabling
 * restores full function immediately, because nothing was destroyed* — is the
 * whole reason a switch may be offered without ceremony, so it is said out loud
 * every time rather than implied by the absence of a warning.
 */
const RESTORE_NOTICE =
  'Turning it back on here restores everything immediately — nothing is destroyed by switching.'

/** "Sprint 8", "Sprint 8 and Sprint 9", "A, B and 2 more" — never a bare count. */
function listNames(names: string[], max = 3): string {
  const shown = names.slice(0, max)
  const rest = names.length - shown.length
  const tail = rest > 0 ? `${rest} more` : shown.pop() ?? ''
  if (shown.length === 0) return tail
  return `${shown.join(', ')} and ${tail}`
}

function plural(n: number, one: string, many = `${one}s`): string {
  return n === 1 ? one : many
}

export default function ProjectDeliverySettingsPage() {
  const { wsId, projectId } = useParams<{ wsId: string; projectId: string }>()
  // ONE reader for "how does this project deliver?" (§12) — a capability is not
  // a permission, so the two questions are asked separately (HD-123 S5). Every
  // switch on this page is a change to the PROJECT, hence `project.edit`; the
  // settings area has already cached the entry both read, so neither costs a
  // request here.
  const { board, releases, estimation, preset, project } = useProjectDelivery(wsId, projectId)
  const canEditProject = useProjectPermissions(wsId, projectId).can('project.edit')

  const loaded = !!project
  const archived = !!project?.archived
  const switcher = useSetCapability(wsId, projectId)

  /** The switch awaiting confirmation (§13), or null. Off-direction only. */
  const [confirming, setConfirming] = useState<DeliveryCapability | null>(null)
  /** The last capability that saved / failed — so a message sits with its own row. */
  const [saved, setSaved] = useState<DeliveryCapability | null>(null)
  const [failed, setFailed] = useState<DeliveryCapability | null>(null)

  // ── The live facts behind every "kept data" notice ─────────────────────────
  // Both are ordinary cached lists the rest of the SPA already fetches (the
  // pickers share these exact keys), so the numbers are current and free.
  const { data: openSprints = [] } = useOpenSprints(wsId, projectId, loaded)
  const { data: versions = [] } = useProjectVersions(wsId, projectId, loaded)
  const activeSprint = openSprints.find(s => s.state === 'ACTIVE') ?? null
  const futureSprints = openSprints.filter(s => s.state === 'FUTURE')
  const unreleased = versions.filter(v => !v.released)

  /* ── **No estimate count** (HD-101 / HD-113) ─────────────────────────────────
   *
   * The third capability has no live fact behind its confirmation, deliberately.
   * The obvious implementation — `apiSearch(ws, { query: 'project = "PR" AND
   * storyPoints >= 0', size: 1 })` — **cannot succeed**: `project` is not a
   * registered HQL field. `FieldRegistry` knows status, type, priority, assignee,
   * reporter, parent, text, created, updated, due, label, component, fixVersion,
   * affectsVersion, sprint and storyPoints — so that query is a 422 *Unknown
   * field* and the dialog would fall back to the countless sentence every single
   * time. Worse: a workspace that happens to own a CUSTOM field keyed `project`
   * resolves the term to that field instead and answers a wrong number in
   * silence. Dropping the `project` term is not an option either — it would count
   * the whole workspace.
   *
   * Two ways the number comes back:
   *
   *  • **HD-101** — register `project` as an HQL field, which makes the search
   *    above correct (and useful far beyond this dialog).
   *  • **HD-113** — a `delivery-usage` endpoint answering the three counts in one
   *    cheap request. The better one: it also works on an ARCHIVED project, which
   *    search — non-archived projects only — structurally cannot.
   *
   * Until one of them lands, the estimation confirmation says the true thing
   * without a number rather than a reassuring invented one (see `impactOf`).
   */

  /**
   * §13's precondition column. A switch that cannot surprise anybody is applied
   * on the spot: turning something ON is never confirmed, and turning something
   * off with nothing behind it is a plain no-op switch ("empty / last-of-kind").
   *
   * Estimation is the exception that confirms without a precondition — whether
   * any issue carries an estimate is not knowable from cached data and no
   * endpoint can answer it today (see *No estimate count* above). So the switch
   * always asks, rather than guessing that there is nothing to keep: guessing
   * wrong here means skipping the one warning a user with 200 estimates needed.
   */
  function needsConfirmation(capability: DeliveryCapability): boolean {
    if (capability === 'iterations') return openSprints.length > 0
    if (capability === 'releases') return unreleased.length > 0
    return true
  }

  async function apply(capability: DeliveryCapability, on: boolean) {
    setSaved(null)
    setFailed(null)
    switcher.reset()
    try {
      await switcher.set({ capability, on })
      setSaved(capability)
      setConfirming(null)
    } catch {
      // 403 (role changed under us) / 409 (archived meanwhile) — the server's own
      // wording is on `switcher.error`; the dialog stays open so it is readable.
      setFailed(capability)
    }
  }

  function startSwitch(capability: DeliveryCapability, on: boolean) {
    setSaved(null)
    setFailed(null)
    switcher.reset()
    if (on || !needsConfirmation(capability)) {
      void apply(capability, on)
      return
    }
    setConfirming(capability)
  }

  const backlogHref = `/w/${wsId}/p/${projectId}/backlog`
  const releasesHref = `/w/${wsId}/p/${projectId}/releases`

  return (
    <div className="flex flex-col gap-5">
      <div>
        <div className="flex items-center gap-2 flex-wrap">
          <h2 className="font-semibold" style={{ fontSize: 15 }}>Delivery</h2>
          {/* Only for a response that actually carried one: `deliveryOf` answers
              CUSTOM for a pre-HD-102 project, and a label we invented client-side
              would be indistinguishable from one the server derived. */}
          {!!project?.delivery && <PresetPill preset={preset} />}
        </div>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)', maxWidth: 620 }}>
          How this team delivers — what the board does, whether work is grouped into versions, and
          whether issues are estimated.{' '}
          {/* A member has no switch to be told is reversible; they are told who
              does have one, and that the guarantee holds either way. */}
          {canEditProject
            ? 'Every switch here is reversible and changes nothing about the issues themselves: '
              + 'sprints, versions and estimates are kept whichever way you set it.'
            : 'A project admin sets this. Whichever way it is set, nothing about the issues '
              + 'themselves changes: sprints, versions and estimates are kept.'}
        </p>
        {/* Named so nobody reads the label as a limitation. "Scrum that also
            ships versions" is an ordinary project, and it lands here. */}
        {project?.delivery?.preset === 'CUSTOM' && (
          <p className="text-xs mt-1.5" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>
            <b>Custom</b> is not a fourth way of working — it is what the label reads whenever the
            capabilities below don’t line up with one of the three named sets.
          </p>
        )}
      </div>

      {!loaded ? (
        <p className="mono text-sm" style={{ color: 'var(--color-text-muted)' }}>loading…</p>
      ) : (
        <>
          {/* ── The board question: two named answers, one capability ────────── */}
          <section className="flex flex-col gap-2.5">
            <SectionHeading
              title="How the board works"
              // A JSX string attribute keeps its newlines and indentation verbatim
              // — long copy goes through an expression, not a wrapped literal.
              hint={canEditProject
                ? 'Kanban and Scrum are two ways to run the same board over the same issues. '
                  + 'This is one choice with two answers, not a feature and its absence.'
                : 'How this project’s board is set up. A project admin chooses it.'}
            />
            <BoardChoice
              value={board}
              canEdit={canEditProject}
              disabled={archived || switcher.isPending}
              onChange={next => startSwitch('iterations', next === 'SCRUM')}
            />

            {/* Rule B, made live: with sprint planning off, the sprints that are
                still open are the data a user would most expect to have lost.
                Curator-only, like the control above it: on a Kanban project this
                is sprint vocabulary, and §5.3 withholds that from a member. */}
            {canEditProject && board === 'KANBAN' && openSprints.length > 0 && (
              <KeptNotice to={backlogHref} linkLabel="Open Backlog">
                <b>{openSprints.length}</b> open {plural(openSprints.length, 'sprint')} kept
                {activeSprint ? <> — <b>{activeSprint.name}</b> is still running</> : null}.
                {' '}They stay on the Backlog as read-only sections, and a running sprint still
                offers <i>Complete sprint</i>. Nothing was moved out of them.
              </KeptNotice>
            )}
            {/* Addressed to whoever could do the switching — a member cannot. */}
            {canEditProject && board === 'SCRUM' && (
              <p className="text-xs" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>
                Switching to Kanban later keeps every sprint and every issue in it — open sprints
                stay on the Backlog, read-only, until you complete them.
              </p>
            )}
            {saved === 'iterations' && !failed && <SavedNote />}
            {failed === 'iterations' && !confirming && <ErrorNote text={switcher.error} />}
          </section>

          {/* ── The add-ons: each listed in BOTH states (§6, last row) ───────── */}
          <section className="flex flex-col gap-2.5">
            <SectionHeading
              title="What else this project does"
              hint={canEditProject
                ? 'Independent of the board choice — Scrum that also ships tagged versions is '
                  + 'ordinary here, and estimating without sprints is legal too.'
                : 'What this project does besides the board. A project admin chooses it.'}
            />

            {ADD_ONS.map(capability => {
              const on = capability === 'releases' ? releases : estimation
              return (
                <div key={capability} className="flex flex-col gap-2">
                  {on ? (
                    <CapabilityOnRow
                      capability={capability}
                      canEdit={canEditProject}
                      disabled={archived}
                      pending={switcher.isPending}
                      onTurnOff={() => startSwitch(capability, false)}
                    />
                  ) : (
                    // The SAME off-state component the Backlog and the Releases
                    // page render (Rule C) — one implementation, one wording, and
                    // the real permission rather than a literal `true`.
                    <CapabilityOffState
                      capability={capability}
                      canEnable={canEditProject}
                      pending={switcher.isPending}
                      disabled={archived}
                      error={failed === capability ? switcher.error : ''}
                      onEnable={() => startSwitch(capability, true)}
                    />
                  )}

                  {capability === 'releases' && !releases && versions.length > 0 && (
                    <KeptNotice to={releasesHref} linkLabel="Open Releases">
                      <b>{unreleased.length}</b> unreleased{' '}
                      {plural(unreleased.length, 'version')} kept
                      {versions.length !== unreleased.length && <> ({versions.length} in total)</>},
                      {' '}with every issue linked to one. The Releases page still opens from here.
                    </KeptNotice>
                  )}
                  {capability === 'estimation' && !estimation && (
                    <KeptNotice>
                      Story points already recorded are kept and still shown on the issues that
                      carry them — only the input and the point totals are hidden.
                    </KeptNotice>
                  )}

                  {saved === capability && !failed && <SavedNote />}
                  {failed === capability && on && !confirming && <ErrorNote text={switcher.error} />}
                </div>
              )
            })}
          </section>
        </>
      )}

      {archived && (
        <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
          This project is archived — unarchive it to change how it delivers.
        </p>
      )}

      {/* Curator-only: planning and starting a sprint is itself curator-only on
          the Backlog, so this sentence would send a member somewhere to do
          something they will not be offered. */}
      {canEditProject && board === 'SCRUM' && wsId && projectId && (
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            Plan and start sprints from the project’s Backlog.
          </span>
          {/* Absolute path: inside the /settings/* splat a relative link would
              resolve AFTER the splat segment (see AdminArea / CLAUDE.md). */}
          <Link to={backlogHref} style={{ textDecoration: 'none' }}>
            <Button variant="secondary" size="sm">Open Backlog</Button>
          </Link>
        </div>
      )}

      {confirming && (
        <TurnOffDialog
          capability={confirming}
          pending={switcher.isPending}
          error={switcher.error}
          impact={impactOf(confirming, { activeSprint, futureSprints, versions, unreleased })}
          onCancel={() => { switcher.reset(); setConfirming(null) }}
          onConfirm={() => void apply(confirming, false)}
        />
      )}
    </div>
  )
}

// ── The confirmations (§13) ───────────────────────────────────────────────────

interface ImpactFacts {
  activeSprint: Sprint | null
  futureSprints: Sprint[]
  versions: Version[]
  unreleased: Version[]
}

/**
 * The live half of a turn-off confirmation: WHICH sprint is running, HOW MANY
 * versions have not shipped — and, for estimation, no number at all (there is no
 * endpoint that can count estimates for one project; see *No estimate count*).
 *
 * Split from the static copy in `CAPABILITY[…].turnOff` on purpose — the promise
 * ("everything is kept") is the same every time and belongs in the table, while
 * the facts are read from this project at this moment and belong here. A generic
 * reassurance is exactly what makes a switch feel like a deletion.
 */
function impactOf(capability: DeliveryCapability, facts: ImpactFacts): ReactNode {
  if (capability === 'iterations') {
    const { activeSprint, futureSprints } = facts
    return (
      <>
        {activeSprint && (
          <div>
            <b>{activeSprint.name}</b> is running. It keeps its issues and stays on the Backlog as a
            read-only section — with <i>Complete sprint</i> still offered — until you complete it.
            It is never auto-completed and no issue is moved out of it.
          </div>
        )}
        {futureSprints.length > 0 && (
          <div className={activeSprint ? 'mt-1.5' : undefined}>
            Planned: <b>{listNames(futureSprints.map(s => s.name))}</b> — kept, and shown on the
            Backlog as read-only {plural(futureSprints.length, 'section')} that can’t be re-planned
            or started until sprint planning is back on.
          </div>
        )}
        <div className="mt-1.5">
          The ranked backlog is untouched — every issue keeps its exact position, because rank is
          shared with the board and was never sprint-specific.
        </div>
      </>
    )
  }
  if (capability === 'releases') {
    const { versions, unreleased } = facts
    return (
      <>
        <b>{unreleased.length}</b> unreleased {plural(unreleased.length, 'version')}
        {versions.length !== unreleased.length && <> (of {versions.length} in this project)</>}
        {' '}{plural(unreleased.length, 'is', 'are')} kept, along with every issue linked to one.
        Nothing is released, archived or unlinked.
      </>
    )
  }
  // Estimation: the one capability whose live fact cannot be counted (HD-101 /
  // HD-113 — see *No estimate count*). The sentence carries no number, and in
  // particular never claims that nothing is at stake: "no issue carries an
  // estimate yet" would be a made-up reassurance, which §13 calls worse than none.
  return <>Every story point already recorded is kept, whatever it is attached to.</>
}

/**
 * The turn-off confirmation. Three blocks in a fixed order, because they answer
 * the three questions in the order they are asked: *what happens to my data?*
 * (live facts + the `keeps` promise), *what will I stop seeing?* (`hides`), and
 * *can I undo this?* (`RESTORE_NOTICE`).
 */
function TurnOffDialog({ capability, impact, pending, error, onCancel, onConfirm }: {
  capability: DeliveryCapability
  impact: ReactNode
  pending: boolean
  error: string
  onCancel: () => void
  onConfirm: () => void
}) {
  const copy = CAPABILITY[capability].turnOff
  return (
    <Modal title={copy.confirmTitle} onClose={onCancel} width={480}>
      <div className="flex flex-col gap-3">
        {/* Warning tint, because this is the part a user is anxious about — not
            because anything is lost. The words say it is not. */}
        <div
          className="text-sm rounded-lg px-3 py-2.5"
          style={{
            background: 'color-mix(in srgb, var(--color-warning) 12%, white)',
            border: '1px solid color-mix(in srgb, var(--color-warning) 34%, white)',
            color: 'var(--color-text-secondary)',
          }}
        >
          {impact}
        </div>

        <div
          className="text-sm rounded-lg px-3 py-2.5"
          style={{
            background: 'color-mix(in srgb, var(--color-brand) 8%, white)',
            border: '1px solid color-mix(in srgb, var(--color-brand) 26%, white)',
            color: 'var(--color-text-secondary)',
          }}
        >
          {copy.keeps}
        </div>

        <div className="flex flex-col gap-1">
          <span className="text-xs font-semibold" style={{ color: 'var(--color-text-secondary)' }}>
            What changes
          </span>
          <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {copy.hides}
          </span>
          {/* Reversibility is copy, never a tooltip — the same rule
              `ReversibleNotice` enforces for the other direction. */}
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {RESTORE_NOTICE}
          </span>
        </div>

        {error && <p className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onCancel}>Cancel</Button>
          {/* Primary, not danger: a danger button would contradict every sentence
              above it. Nothing here deletes anything. */}
          <Button variant="primary" loading={pending} onClick={onConfirm}>{copy.action}</Button>
        </div>
      </div>
    </Modal>
  )
}

// ── Pieces ────────────────────────────────────────────────────────────────────

function SectionHeading({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <span className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>{title}</span>
      <span className="text-xs" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>{hint}</span>
    </div>
  )
}

/**
 * The derived preset (§2.3). A label with a tooltip that says what it is, because
 * a name that looks like a setting invites someone to try to set it — and the
 * server answers 400 to a request that carries one.
 */
function PresetPill({ preset }: { preset: DeliveryPreset }) {
  return (
    <span
      className="inline-flex items-center gap-1.5"
      title="Derived from the three capabilities below. It is a label, not a setting — nothing sends it."
      style={{
        borderRadius: 'var(--radius-full, 9999px)',
        padding: '2px 9px',
        fontSize: 11,
        background: 'var(--color-surface-2)',
        color: 'var(--color-text-secondary)',
      }}
    >
      Preset
      {/* DESIGN.md: a derived, inspectable datum reads in IBM Plex Mono. */}
      <span className="mono font-semibold" style={{ color: 'var(--color-text)' }}>
        {PRESET_LABEL[preset]}
      </span>
    </span>
  )
}

/**
 * The board pair. The two names come from the capability table's creation copy
 * (the row that carries an `offCard` IS the board question), so this page and
 * the creation picker can never call the same thing by two names. The Scrum
 * blurb, however, is the SETTINGS one: choosing Scrum here changes the board and
 * nothing else, while choosing it at creation also turns estimation on — one
 * blurb cannot honestly describe both.
 */
function BoardChoice({ value, canEdit, disabled, onChange }: {
  value: BoardMode
  canEdit: boolean
  disabled: boolean
  onChange: (next: BoardMode) => void
}) {
  // A member gets the STATEMENT, never the radio pair — the same shape
  // `CapabilityOffState` takes for a non-curator, for the same two reasons: the
  // Scrum radio is an enabling control for iterations (a click they can only
  // 403 on, Rule A), and on a Kanban project its label is exactly the sprint
  // vocabulary §5.3 withholds from a member. The settings area redirects
  // non-curators, but this editor may not depend on that guard.
  if (!canEdit) {
    const copy = CAPABILITY.iterations
    return (
      <p className="text-xs" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>
        {value === 'SCRUM' ? copy.memberNoteOn : copy.memberNote}
      </p>
    )
  }
  const creation = CAPABILITY.iterations.creation
  const scrumTitle = creation.atCreation ? creation.title : 'Scrum'
  const kanban = creation.atCreation ? creation.offCard : undefined
  const cards: { value: BoardMode; title: string; blurb: string }[] = [
    {
      value: 'KANBAN',
      title: kanban?.title ?? 'Kanban',
      blurb: kanban?.blurb ?? 'Continuous flow. One board, no time-boxes.',
    },
    { value: 'SCRUM', title: scrumTitle, blurb: CAPABILITY.iterations.settingsBlurb },
  ]
  return (
    <div className="flex flex-col gap-2.5" role="radiogroup" aria-label="Board">
      {cards.map(card => {
        const selected = value === card.value
        return (
          <label
            key={card.value}
            className="flex items-start gap-3 rounded-lg border cursor-pointer transition-colors"
            style={{
              padding: '12px 14px',
              background: 'white',
              borderColor: selected ? 'var(--color-brand)' : 'var(--color-border)',
              boxShadow: selected ? 'var(--shadow-card)' : undefined,
            }}
          >
            <input
              type="radio"
              name="deliveryBoard"
              value={card.value}
              checked={selected}
              disabled={disabled}
              onChange={() => onChange(card.value)}
              style={{ accentColor: 'var(--color-brand)', marginTop: 3, width: 15, height: 15 }}
            />
            <span className="flex flex-col gap-0.5 min-w-0">
              <span className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>
                {card.title}
              </span>
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {card.blurb}
              </span>
            </span>
          </label>
        )
      })}
    </div>
  )
}

/**
 * An add-on capability while it is ON — its name, what it does, and the way out.
 *
 * The mirror of `CapabilityOffState`, `canEdit` included: a member is told what
 * the project does and offered nothing, because "Turn off releases" is a control
 * whose only possible outcome for them is a 403 (Rule A — the endpoint was never
 * open to them). Showing a member a live off switch beside "…is off for this
 * project. A project admin can turn it back on" is the same page saying two
 * different things about the same role.
 */
function CapabilityOnRow({ capability, canEdit, disabled, pending, onTurnOff }: {
  capability: DeliveryCapability
  canEdit: boolean
  disabled: boolean
  pending: boolean
  onTurnOff: () => void
}) {
  const copy = CAPABILITY[capability]
  if (!canEdit) {
    return (
      <p className="text-xs" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>
        {copy.memberNoteOn}
      </p>
    )
  }
  return (
    <div
      className="flex items-start gap-3 rounded-lg border"
      style={{ padding: '12px 14px', background: 'white', borderColor: 'var(--color-brand)',
               boxShadow: 'var(--shadow-card)' }}
    >
      <span className="flex flex-col gap-0.5 min-w-0 flex-1">
        <span className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>
          {copy.settingsTitle}
        </span>
        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          {copy.settingsBlurb}
        </span>
      </span>
      <Button variant="secondary" size="sm" disabled={disabled} loading={pending} onClick={onTurnOff}>
        {copy.turnOff.action}
      </Button>
    </div>
  )
}

/**
 * A live "kept data" notice — what remains and stays visible, read-only, now that
 * a capability is off (Rule B). Muted/secondary tokens only: it is information,
 * not a warning, and it must not read like an error the user has to clear.
 */
function KeptNotice({ children, to, linkLabel }: {
  children: ReactNode
  to?: string
  linkLabel?: string
}) {
  return (
    <div
      className="text-xs rounded-lg px-3 py-2"
      style={{
        background: 'var(--color-surface-2)',
        border: '1px solid var(--color-border)',
        color: 'var(--color-text-secondary)',
        maxWidth: 620,
      }}
    >
      {children}
      {to && linkLabel && (
        <>
          {' '}
          {/* Absolute: relative links inside the /settings/* splat resolve after
              the splat segment. */}
          <Link to={to} style={{ color: 'var(--color-brand)' }}>{linkLabel}</Link>
        </>
      )}
    </div>
  )
}

function SavedNote() {
  return (
    <p className="text-xs" style={{ color: 'var(--color-success)' }}>
      Saved — every surface of this project follows the new setting already.
    </p>
  )
}

function ErrorNote({ text }: { text: string }) {
  if (!text) return null
  return <p className="text-xs" style={{ color: 'var(--color-error)' }}>{text}</p>
}
