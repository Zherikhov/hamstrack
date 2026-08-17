import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiResponseError, apiUpdateProject } from '../api'
import { projectIssuesKeyPrefix } from '../lib/queryKeys'
import { Button } from './ui'
import type { BoardMode, Project, ProjectDeliveryUpdate } from '../types'

/**
 * Rule C, the bootstrap invariant (HD-102 §5.3) — **every capability has at least
 * one enabling affordance that is visible while the capability is off, placed
 * where a user would look for the capability. Using it turns the capability on,
 * tells the user so, and says it is reversible.**
 *
 * This module is that rule made structural rather than repeated. A capability's
 * *name*, its *off-state copy*, its *reversibility sentence* and the *partial
 * PATCH that turns it on* all live in ONE table below, and every surface renders
 * the same two components out of it. Adding a fourth capability is a row here,
 * not a fourth hand-written button on a fourth screen — which is the whole point:
 * the production dead end (a Kanban project with no route to its first sprint)
 * became possible because "how do I turn this on?" was nobody's job in
 * particular.
 *
 * Three properties the callers may not opt out of:
 *
 *  • **Curator-only.** The affordance changes a project setting, so it follows
 *    `ScopeResolver.requireProjectCurator` (project MANAGER *or* workspace
 *    OWNER/ADMIN). A plain member sees no enabling control and, for iterations,
 *    no sprint vocabulary at all — they get the read-only half or nothing.
 *  • **Reversibility is copy, never a tooltip.** `reversible` is rendered as a
 *    visible sentence in the dialog / panel that performs the switch. A hover
 *    hint is invisible to exactly the person deciding whether to click.
 *  • **The write is partial and never carries `preset`.** `patch` holds a single
 *    member of `delivery`; `preset` is derived server-side and a request
 *    containing it is a 400 — so a client can never echo back the object it read
 *    from a GET.
 *
 * Rule A still holds around all of this (§5.1): the endpoints these affordances
 * unlock were never closed. `POST /sprints` succeeds on a Kanban project and
 * `fixVersionIds` applies with releases off — enabling the capability changes
 * what the UI *offers*, never what the API *allows*.
 */

/** The three declared capabilities, named the way the UI talks about them. */
export type DeliveryCapability = 'iterations' | 'releases' | 'estimation'

/**
 * Where a capability is turned back OFF. The Board tab became **Delivery** in S5
 * (it grew the other two capabilities); this one constant is the only place the
 * rename had to land, so no notice can drift out of date.
 */
export const DELIVERY_SETTINGS_TAB = 'Delivery'

/**
 * How a capability appears on the PROJECT-CREATION picker (HD-105 §12) — or why
 * it deliberately does not.
 *
 * The union is the forcing function. A capability cannot be silently missing
 * from the picker: the row either carries its card copy or states, in `why`,
 * that it was decided against. "We forgot" and "we decided" therefore never look
 * the same, which is the same failure mode Rule C exists to prevent one screen
 * later — a capability nobody can find because nobody owned the question.
 */
export type CapabilityAtCreation =
  | {
    atCreation: true
    /** The card's name — one of the three names the user meets up front. */
    title: string
    /** What choosing it does, in one sentence. */
    blurb: string
    /**
     * Present **iff** this capability is orthogonal to the board question, i.e.
     * it renders as an independent toggle rather than one arm of the radio pair.
     * Rendered verbatim beside the title, and it is load-bearing (divergence
     * D1): three named cards are honest only while this sentence says that the
     * third one is not a third alternative.
     */
    independentNote?: string
    /**
     * A capability whose OFF state is itself a named way of working gets a
     * SECOND card, and the pair becomes a radio group. `board` is the only such
     * capability today: "Kanban" is not "no Scrum", it is the other answer to
     * "how does this board work?".
     */
    offCard?: { title: string; blurb: string }
  }
  | { atCreation: false; why: string }

/**
 * The OTHER direction (HD-106 §13) — what turning a capability OFF does, said
 * before the click.
 *
 * It lives in the same row as the enabling copy on purpose. The switch is
 * symmetrical in the API (one partial PATCH either way) and it has to be
 * symmetrical in the product too: the reason a user hesitates over an off switch
 * is that off switches usually delete things, and here none of them do. Stating
 * `keeps` and `hides` as two separate sentences is what makes that legible —
 * "nothing is lost" and "here is what you will stop seeing" are different
 * promises and a single blurb always blurs them.
 *
 * The live COUNTS ("3 unreleased versions kept") are deliberately NOT here: they
 * are read from the project's own data by the settings page. Copy is static,
 * facts are fetched.
 */
interface CapabilityTurnOff {
  /** The action's label — on the row, and on the confirm dialog's button. */
  action: string
  /** The confirm dialog's question. */
  confirmTitle: string
  /** What survives the switch. Rule B (§5.2): every value stays, and stays visible. */
  keeps: string
  /** What the UI stops OFFERING — never phrased as a loss, because it is not one. */
  hides: string
}

interface CapabilityCopy {
  /** The enabling affordance's label, where the capability belongs. */
  enable: string
  /** The capability's row title in Project settings → Delivery. */
  settingsTitle: string
  /** What it does while it is ON — the settings row's one-line subtitle. */
  settingsBlurb: string
  /** Turning it back off (§13) — the direction S1–S4 never had to describe. */
  turnOff: CapabilityTurnOff
  /** The off-state heading — states the project's current way of working. */
  offTitle: string
  /** What turning it on does, in one sentence. */
  offBlurb: string
  /**
   * The reversibility sentence. ALWAYS rendered as visible copy by
   * `ReversibleNotice` / `CapabilityOffState` — never only as a `title`.
   */
  reversible: string
  /** What a non-curator is told instead. No verb, no offer, no vocabulary. */
  memberNote: string
  /**
   * The same, for a capability that is **on**. Required rather than optional for
   * the same reason `creation` is a union: a capability whose read-only row a
   * member cannot be shown would leave the settings page with a live control as
   * its only rendering, which is exactly the gap HD-106's test gate found.
   *
   * States what the project does. No verb and no offer — a member cannot switch
   * it off, and a "Turn off …" button they can only 403 on is not information.
   */
  memberNoteOn: string
  /** The partial `delivery` body that turns it on — never carries `preset`. */
  patch: ProjectDeliveryUpdate
  /** Its card on the creation picker, or the decision not to give it one. */
  creation: CapabilityAtCreation
}

export const CAPABILITY: Record<DeliveryCapability, CapabilityCopy> = {
  iterations: {
    enable: 'Plan in sprints',
    settingsTitle: 'Sprint planning',
    settingsBlurb: 'The board scopes itself to the sprint that is running and the Backlog plans in '
      + 'sprint sections.',
    turnOff: {
      // Not "turn off sprints": the board question has two named answers, and
      // Kanban is one of them rather than the absence of the other.
      action: 'Switch to Kanban',
      confirmTitle: 'Switch to Kanban?',
      keeps: 'Every sprint is kept, with every issue in it — nothing is completed, moved or '
        + 'deleted. Sprints that are still running or planned stay on the Backlog as read-only '
        + 'sections, and a running sprint still offers Complete sprint. The ranked order is '
        + 'untouched: rank is shared with the board and was never sprint-specific.',
      hides: 'The board stops scoping to the running sprint and shows the whole project again, and '
        + 'the sprint picker leaves the create dialog and issue detail — a sprint an issue already '
        + 'carries stays visible there, read-only.',
    },
    offTitle: 'This project runs continuous flow',
    offBlurb: 'Planning in sprints adds sprint sections to this backlog and scopes the board to '
      + 'the sprint that is running.',
    reversible: 'This turns on sprint planning for this project. You can turn it off again in '
      + `Project settings → ${DELIVERY_SETTINGS_TAB} — sprints and the issues in them are kept `
      + 'either way.',
    memberNote: 'This project plans as one ranked list.',
    // Sprint vocabulary is withheld from a member only while the project does NOT
    // plan in sprints — with iterations on, the sprint sections are on their own
    // Backlog and the word is already theirs.
    memberNoteOn: 'This project plans in sprints.',
    patch: { board: 'SCRUM' },
    creation: {
      atCreation: true,
      title: 'Scrum',
      blurb: 'Fixed-length sprints you plan, run and close. Adds sprint sections to the backlog '
        + 'and story points to issues.',
      // The board question has two named answers, so it is a radio pair, not a
      // toggle: a project is never "Kanban because Scrum is off".
      offCard: {
        title: 'Kanban',
        blurb: 'Continuous flow. One board, no time-boxes.',
      },
    },
  },
  releases: {
    enable: 'Turn on releases',
    settingsTitle: 'Releases',
    settingsBlurb: 'Versions with a ship date and a Releases page of their own, plus fix / affects '
      + 'version links on issues.',
    turnOff: {
      action: 'Turn off releases',
      confirmTitle: 'Turn off releases?',
      keeps: 'Every version is kept, along with every issue linked to one — nothing is deleted, '
        + 'released or unlinked.',
      hides: 'Releases leaves the sidebar (the page itself still opens, and this tab keeps a link '
        + 'to it), and the fix / affects version pickers leave issues — versions an issue already '
        + 'carries stay visible there, read-only.',
    },
    offTitle: 'Releases are off for this project',
    offBlurb: 'Turning them on brings back the Releases item in the sidebar and the fix / affects '
      + 'version pickers on issues.',
    reversible: 'This turns on releases for this project. You can turn them off again in '
      + `Project settings → ${DELIVERY_SETTINGS_TAB} — every version, and every issue linked to `
      + 'one, is kept either way.',
    memberNote: 'Releases are off for this project. Existing versions are kept and shown '
      + 'read-only; a project admin can turn releases back on.',
    memberNoteOn: 'This project groups issues into versions and ships them from the Releases page.',
    patch: { releases: true },
    creation: {
      atCreation: true,
      title: 'Releases',
      blurb: 'Group issues into versions and ship them from a Releases page.',
      // Divergence D1, in four words. Releases are an orthogonal capability over
      // a different object (`versions`), not a third kind of board — "Scrum that
      // also ships 2.4.0" is ordinary and must stay expressible.
      independentNote: 'Works with either.',
    },
  },
  /**
   * Estimation has no page of its own to seed it from, so its affordance lives in
   * Project settings (§5.3) — S5 renders it from this same row. It is in the
   * table because the table IS the model: a capability without an entry here has
   * no enabling affordance, which is precisely what Rule C forbids.
   */
  estimation: {
    enable: 'Turn on story points',
    settingsTitle: 'Story points',
    settingsBlurb: 'A story-point estimate on every issue, and point totals on sprint sections and '
      + 'board columns.',
    turnOff: {
      action: 'Turn off story points',
      confirmTitle: 'Turn off story points?',
      // §13, verbatim — the sentence the spec pins for this switch.
      keeps: 'Existing story points are kept and still shown on issues; only the input is hidden.',
      hides: 'The points input leaves the create dialog and issue detail, and point totals leave '
        + 'sprint sections and board columns.',
    },
    offTitle: 'This project does not estimate',
    offBlurb: 'Turning estimation on adds the story-points input to issues and point totals to '
      + 'sprints and board columns.',
    reversible: 'This turns on story-point estimation for this project. You can turn it off again '
      + `in Project settings → ${DELIVERY_SETTINGS_TAB} — estimates already recorded are kept `
      + 'either way.',
    memberNote: 'Story-point estimation is off for this project.',
    memberNoteOn: 'This project estimates issues in story points.',
    patch: { estimation: true },
    creation: {
      atCreation: false,
      why: 'Open question 7 — estimation follows the board choice (Scrum turns it on) and is '
        + `adjustable in Project settings → ${DELIVERY_SETTINGS_TAB}. A third question at create `
        + 'time costs more than it informs.',
    },
  },
}

// ── The creation picker's model (HD-105 / §12) ────────────────────────────────

/**
 * What the user actually answered on the picker. Two answers, because the picker
 * asks two questions: *how does the board work?* (one of two named ways) and
 * *what else does this project do?* (independent add-ons).
 *
 * `addOns` holds capability NAMES rather than booleans so it stays in step with
 * the table: a future orthogonal capability becomes a card, a legal member of
 * this array and a member of the request body without this type changing.
 */
export interface DeliveryChoice {
  board: BoardMode
  addOns: DeliveryCapability[]
}

/**
 * The capability the board radio pair describes — DERIVED from the table (the
 * one row whose creation copy carries an `offCard`), so the picker never names a
 * capability itself. `DeliveryChoice.board` is a `BoardMode`, so there is
 * structurally one of these; the fallback only exists to keep the type honest.
 */
export const CREATION_BOARD_CAPABILITY: DeliveryCapability =
  (Object.keys(CAPABILITY) as DeliveryCapability[])
    .find(c => {
      const creation = CAPABILITY[c].creation
      return creation.atCreation && !!creation.offCard
    }) ?? 'iterations'

/** The capabilities the picker draws as independent toggles — derived, not listed. */
export const CREATION_ADD_ONS = (Object.keys(CAPABILITY) as DeliveryCapability[])
  .filter(c => {
    const creation = CAPABILITY[c].creation
    return creation.atCreation && !creation.offCard
  })

/**
 * **The lean default (divergence D4), and the only place it lives.**
 *
 * §17 calls this the highest-risk assumption in the whole design — quiet,
 * cumulative failure if it is wrong (users accept the default and never discover
 * Releases) — and promises it is "the cheapest thing in this document to
 * reverse". That promise is only kept while the default is one named constant:
 * scattered through JSX as `useState('KANBAN')` + `useState(false)` it becomes a
 * hunt. To ship releases-on-by-default, add `'releases'` to `addOns` here and
 * change nothing else in the SPA.
 */
export const LEAN_DELIVERY_CHOICE: DeliveryChoice = { board: 'KANBAN', addOns: [] }

/**
 * The reassurance sentence, rendered **above** the cards — it is read while the
 * user is deciding, not after they have. An up-front question is only cheap if
 * the "and it is not permanent" arrives before the commitment, which is also why
 * it is body copy and not a tooltip (same rule as `ReversibleNotice`).
 */
export const CREATION_REVERSIBLE_NOTICE =
  `You can change this any time in Project settings → ${DELIVERY_SETTINGS_TAB} — switching later `
  + 'keeps every sprint, version and estimate.'

/**
 * The `delivery` object a create request carries. Every member is explicit: S1
 * would apply the same lean defaults for an omitted `delivery`, but this slice
 * exists to make the choice a STATEMENT, and an explicit body is also what makes
 * the localStorage nudge auditable in the network tab.
 *
 * `estimation` is derived here rather than asked (§18 q7): it follows the board
 * answer, so choosing Scrum yields `board = SCRUM` **and** `estimation = true`
 * in one request. `preset` is never included — it is derived server-side and a
 * request carrying it is a 400.
 */
export function creationDelivery(choice: DeliveryChoice): Required<ProjectDeliveryUpdate> {
  const body: Required<ProjectDeliveryUpdate> = {
    board: choice.board,
    // The base is "every add-on the user did NOT tick is off" — a translation of
    // `choice`, not a policy. The policy (D4) is `LEAN_DELIVERY_CHOICE`; flip it
    // there, never here, or a remembered choice would be silently overruled.
    releases: false,
    estimation: choice.board === 'SCRUM',
  }
  // Each add-on contributes the SAME partial patch the single writer sends, so a
  // capability can never mean one thing at creation and another at enable-time.
  for (const capability of choice.addOns) Object.assign(body, CAPABILITY[capability].patch)
  return body
}

/** Server messages reach the SPA as ProblemDetail `detail` — prefer it over a generic string. */
function errorText(e: unknown, fallback: string): string {
  if (e instanceof ApiResponseError) return e.detail
  return e instanceof Error ? e.message : fallback
}

/**
 * The partial `delivery` body that switches ONE capability, in EITHER direction.
 *
 * Derived from the row's `patch` rather than stored twice, because the two
 * directions must be the same member of `delivery` by construction: an `offPatch`
 * field could be written to name a different member (or to restate a second one)
 * and nothing would catch it until a user turned something off and found an
 * unrelated capability had moved.
 *
 * `board` is the one member whose OFF state is a named value rather than
 * `false` — "Kanban" is not "no Scrum", it is the other answer to the same
 * question (divergence D1), which is exactly why the off value is computed from
 * the on value instead of assumed.
 */
export function capabilityPatch(
  capability: DeliveryCapability,
  on: boolean,
): ProjectDeliveryUpdate {
  const patch = CAPABILITY[capability].patch
  if (on) return { ...patch }
  const off: ProjectDeliveryUpdate = {}
  if (patch.board !== undefined) off.board = patch.board === 'SCRUM' ? 'KANBAN' : 'SCRUM'
  if (patch.releases !== undefined) off.releases = false
  if (patch.estimation !== undefined) off.estimation = false
  return off
}

/**
 * Switch one capability, on or off. The single writer for every capability
 * change in the SPA, so no caller can invent a body shape:
 *
 *  • the PATCH is **partial per member** — `{ delivery: { board: 'SCRUM' } }`
 *    changes the board and touches neither `releases` nor `estimation`;
 *  • it **never carries `preset`** (derived server-side; sending it is a 400);
 *  • the fresh `ProjectResponse` is written straight into the cache entry every
 *    surface reads through `useProjectDelivery`, so the page that performed the
 *    switch re-renders with the capability on before any refetch lands.
 *
 * Turning OFF goes through here too (HD-106 §13) — same endpoint, same partial
 * shape, no confirmation of its own. Confirmations are a UI affordance the
 * settings page owns; the API takes either direction with a 200 (Rule A, §5.1).
 *
 * 403 if the role changed under us, 409 if the project was archived meanwhile —
 * both surface as the server's own wording via `error`.
 */
export function useSetCapability(wsId: string | undefined, projectId: string | undefined) {
  const qc = useQueryClient()
  const mutation = useMutation({
    mutationFn: ({ capability, on }: { capability: DeliveryCapability; on: boolean }): Promise<Project> =>
      apiUpdateProject(wsId!, projectId!, { delivery: capabilityPatch(capability, on) }),
    onSuccess: updated => {
      qc.setQueryData(['project', wsId, projectId], updated)
      qc.invalidateQueries({ queryKey: ['project', wsId, projectId] })
      // The board re-scopes (whole project ⇄ running sprint) and the planning
      // view re-renders its sections, so both issue caches are now stale.
      qc.invalidateQueries({ queryKey: projectIssuesKeyPrefix(wsId, projectId) })
      qc.invalidateQueries({ queryKey: ['sprints', wsId, projectId] })
    },
  })
  return {
    set: mutation.mutateAsync,
    isPending: mutation.isPending,
    error: mutation.error ? errorText(mutation.error, 'Could not change this project setting') : '',
    reset: mutation.reset,
  }
}

/**
 * The enable-on-first-use half of the writer above (Rule C, §5.3), kept as its
 * own name because that is what the bootstrap affordances mean: a Backlog
 * offering "Plan in sprints" can only ever turn iterations ON, and a hook that
 * also accepts `false` would let a hurried caller wire the wrong direction into
 * a button whose label promises the other one.
 */
export function useEnableCapability(wsId: string | undefined, projectId: string | undefined) {
  const { set, isPending, error, reset } = useSetCapability(wsId, projectId)
  return {
    enable: (capability: DeliveryCapability): Promise<Project> => set({ capability, on: true }),
    isPending,
    error,
    reset,
  }
}

/**
 * The reversibility notice, inside the dialog that performs the switch (Rule C:
 * "tells the user so, and says it is reversible"). Rendered as body copy — the
 * one thing this component exists to guarantee is that the sentence cannot be
 * demoted to a `title` attribute by a caller in a hurry.
 */
export function ReversibleNotice({ capability }: { capability: DeliveryCapability }) {
  return (
    <div
      className="text-sm rounded-lg px-3 py-2.5"
      style={{
        background: 'color-mix(in srgb, var(--color-brand) 8%, white)',
        border: '1px solid color-mix(in srgb, var(--color-brand) 26%, white)',
        color: 'var(--color-text-secondary)',
      }}
    >
      {CAPABILITY[capability].reversible}
    </div>
  )
}

/**
 * The off-state a page shows **where the capability's own controls would be** —
 * the Backlog's sprint area, the Releases page's header. Dashed like the "Create
 * sprint" affordance it stands in for, so an off capability reads as an empty
 * slot to fill rather than as a missing feature.
 *
 * A non-curator gets `memberNote` and no control at all: they cannot change the
 * project, so offering them the verb would only teach them vocabulary their
 * project does not use.
 */
export function CapabilityOffState({
  capability, isCurator, pending, disabled, error, onEnable,
}: {
  capability: DeliveryCapability
  isCurator: boolean
  pending?: boolean
  /** Archived project — frozen (409 server-side), but still discoverable. */
  disabled?: boolean
  error?: string
  onEnable: () => void
}) {
  const copy = CAPABILITY[capability]
  if (!isCurator) {
    return (
      <p className="text-xs" style={{ color: 'var(--color-text-muted)' }}>{copy.memberNote}</p>
    )
  }
  return (
    <div
      className="flex flex-col gap-2 rounded-lg border border-dashed"
      style={{ padding: '14px 16px', borderColor: 'var(--color-border-2)', background: 'white' }}
    >
      <span className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>
        {copy.offTitle}
      </span>
      <span className="text-xs" style={{ color: 'var(--color-text-secondary)', maxWidth: 620 }}>
        {copy.offBlurb}
      </span>
      {/* The reversibility sentence is visible before the click, not after it. */}
      <span className="text-xs" style={{ color: 'var(--color-text-muted)', maxWidth: 620 }}>
        {copy.reversible}
      </span>
      {error && <span className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</span>}
      <div className="flex">
        <Button variant="secondary" size="sm" loading={pending} disabled={disabled} onClick={onEnable}>
          {copy.enable}
        </Button>
      </div>
    </div>
  )
}
