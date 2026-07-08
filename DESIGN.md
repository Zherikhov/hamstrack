# Design System — easyTask

## Product Context
- **What this is:** Open-source task tracker, Jira-inspired but must not copy Jira's UI/implementation/naming. Core differentiator: safe AI-agent-driven self-service process customization (validators, transition rules, approval flows) with sandbox dry-run, human approval, and post-promotion rollback.
- **Who it's for:** Project owners and workspace admins at companies leaving Jira Data Center or evaluating new trackers — technical, skeptical buyers who have been burned by complexity and do not trust black-box AI by default.
- **Space/industry:** B2B SaaS / dev & ops process tooling.
- **Project type:** Web app (dashboard-style internal tool), dual-deployment — self-hosted DC-style and Cloud, same codebase.

## Aesthetic Direction
- **Direction:** Industrial/Utilitarian — function-first, data-dense, monospace accents.
- **Decoration level:** Intentional — subtle structural decoration (thin rules, state badges, diff blocks) that signals state and history. Never pure ornament.
- **Mood:** "Этому AI здесь можно доверять" (this AI can be trusted here). Serious technical instrument, not playful or breezy.
- **Reference sites:** linear.app (technical seriousness, register to emulate), asana.com / monday.com (breezy "agents act" register — deliberately avoided, it undercuts the trust story), plane.so (generic indigo SaaS look — deliberately avoided to not read as derivative).

## Typography
- **Display/Hero:** Cabinet Grotesk — distinctive geometric grotesk; avoids the Inter/Space Grotesk convergence trap.
- **Body:** Instrument Sans — clean, neutral, reads well at small sizes in data-dense UI.
- **UI/Labels:** Same as body.
- **Data/Tables/Diff/Audit:** IBM Plex Mono, tabular-nums — used for anything representing a literal change (diffs, version hashes, audit log values). This is a deliberate structural choice (not just code blocks) signaling "inspectable, not magic."
- **Code:** JetBrains Mono — actual script/validator source.
- **Loading:** Instrument Sans / IBM Plex Mono / JetBrains Mono via Google Fonts CDN. Cabinet Grotesk via Fontshare CDN (`api.fontshare.com`).
- **Scale:** Hero display 52px/800. Section display 34px/700. Body 17px (marketing) / 15px (UI default). Data/mono 14px (UI) / 12.5px (diff blocks). Code 13px.

## Color
- **Approach:** Restrained — one brand accent + neutrals. Color is used as a **safety-state indicator** first, branding second.
- **Primary / Production-trusted state:** `#0F6E63` (deep teal), hover `#0C5950`.
- **Pending-approval state:** `#B45309` (amber).
- **Sandbox / draft state:** `#64748B` (slate).
- **Neutrals (warm gray):** `#F7F6F3` → `#EFEDE7` → `#E4E1DA` → `#CBC7BC` → `#8B8680` → `#5C5950` → `#1C1B19`.
- **Semantic:** success `#15803D`, warning `#B45309` (shared with pending-approval), error `#B91C1C`, info `#64748B` (shared with sandbox).
- **Explicitly avoided:** indigo/violet as primary accent (overused category default; also what the prior draft and Plane both use).
- **Dark mode:** Dedicated surface/border/text overrides, not inverted lightness. State-background tints darken (not just dim) while preserving hue identity, so the safety-state meaning stays legible in both modes.

## Spacing
- **Base unit:** 8px (4px half-step for 2xs/xs).
- **Density:** Compact in-app (board, list, issue detail, AI approval panel). Comfortable on marketing/onboarding surfaces.
- **Scale:** 2xs(2) xs(4) sm(8) md(16) lg(24) xl(32) 2xl(48) 3xl(64).

## Layout
- **Approach:** Grid-disciplined for the app (predictable alignment for data-dense board/list views). Hybrid — a bit more editorial — for the marketing site.
- **Grid:** Desktop app shell is 3-column (sidebar / main / contextual AI panel); collapses to single column + drawer below 960px.
- **Max content width:** 1080px for marketing sections.
- **Border radius (hierarchical, not uniform):** sm 4px (buttons, inputs) · md 8px (cards, diff blocks) · lg 12px (panels, app shell) · full 9999px (badges, chips, avatars only — never the default).

## Motion
- **Approach:** Minimal-functional — only transitions that aid comprehension of a state change.
- **Easing:** enter `ease-out` · exit `ease-in` · move `ease-in-out`.
- **Duration:** micro 50–100ms (hover/focus) · short 150–250ms (panel open/close, badge state change) · medium 250–400ms (diff reveal). No long/expressive choreography — state changes should feel deliberate, not playful.

## Decisions Log
| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-06-24 | Initial design system created | Created by `/design-consultation`, grounded in the approved office-hours design doc and competitor visual research (Linear, Asana, monday.com, Plane) |
| 2026-06-24 | Color as a safety-state machine (slate → amber → teal) instead of pure branding | Directly reinforces the product's core trust differentiator (sandbox → pending approval → production); chosen as a deliberate risk over category-standard branding-first color use |
| 2026-06-24 | Monospace (IBM Plex Mono) for diff/audit data as a structural typographic choice, not just code blocks | Signals "inspectable, not magic" — no competitor in this category does this; ties directly to the validated trust gap from the office-hours premises |
| 2026-06-24 | Avoided indigo/violet as primary accent | The existing draft UI and competitor Plane both default to indigo; a distinct teal keeps easyTask from reading as derivative |
