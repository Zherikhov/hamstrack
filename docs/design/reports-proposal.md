# Reports — proposal (epic HD-5)

> Status: **proposed**, not built. Written 2026-08-19 by `systems-analyst`.
> Companion reading: `docs/design/delivery-paths-proposal.md` (Rules A/B/C), `docs/design/agile-sprints-proposal.md` (sprint lifecycle), `docs/design/roles-permissions-proposal.md` (why reads are not permission-gated), `docs/design/advanced-search-hql-proposal.md` (the composability surface we already own).

**The epic's stated scope is "burndown + velocity, cumulative flow + created-vs-resolved, configurable dashboard of gadgets." Three of those five are refused below**, with the evidence. Read §1 before §2; the refusals are the load-bearing part of this document.

---

## 0. Executive summary

| | Decision |
|---|---|
| **Ship** | Flow (created vs resolved) · Cycle & lead time + **aging work in progress** · Sprint **burn-up** with an explicit scope line · Sprint review record · Velocity **as a forecast band** · an **Insights panel on search results** |
| **Refuse** | **Cumulative flow diagram** · **Burndown** · **Configurable gadget dashboard** · rolling-average control chart · per-person breakdowns · scheduled PDF email · time/cost reports |
| **New data** | One narrow ledger table `sprint_scope_events`, one column `issues.started_at`, four indexes. No snapshots, no materialized views, no scheduler. |
| **Permission** | **None new.** Any workspace member who can see the project can open its reports. §4.2 |
| **Cost** | Every report is bounded by (project × time window) or (one sprint), index-served, computed live. No precomputation in v1 — because the one report that would have required it is the one we refuse. |
| **Highest-risk assumption** | That the sprint scope ledger is written at **every** door that changes sprint membership. If one door misses it, the burn-up's scope line is silently wrong — the precise failure that makes competitors' burndowns distrusted. Mitigation in §5.2. |

---

## 1. The research

Organised by finding, not by product, because the same complaint recurs across products and that recurrence is the signal.

### 1.1 Praised — the things people actually open

**The sprint review record, not the sprint chart.** The report described as the one teams open every fortnight is the Sprint Report, because it is *"a factual, timestamped record of what happened"* used at retrospectives — committed vs completed vs added vs carried over — rather than a chart to interpret ([Grandia](https://grandiasolutions.com/jira-scrum-reports/), [Atlassian Community guide](https://community.atlassian.com/forums/App-Central-articles/The-Ultimate-Guide-to-the-Jira-Sprint-Report-Metrics-Analysis/ba-p/3200893)). The same guide is blunt that *"scope creep (starred/added issues) and completion percentages reveal planning failures more reliably than raw velocity numbers."*

**Conclusion:** the highest-value sprint artefact is a *list with counts*, not a curve. Ship it as a first-class report, not as a tooltip on a chart.

**Linear Insights — analytics attached to the view you are already looking at.** Insights is not a dashboard builder. It opens in the right sidebar of an issue view (`Cmd/Ctrl+Shift+I`); the dataset is *the filter you already have*, and you then pick a Measure (y), a Slice (x) and an optional Segment (colour). Measures are deliberately few — issue count, effort/estimate, cycle time, lead time, triage time, issue age, burn-up ([Linear docs](https://linear.app/docs/insights)). Reviewers report *"insights are easy to build"*; the recurring criticism is depth (*"no ad-hoc SQL or BI export"*, dashboards gated behind the top tier) ([G2 pros & cons](https://www.g2.com/products/linear/reviews?qs=pros-and-cons), [siit.io review](https://www.siit.io/tools/trending/linear-app-review)).

**Conclusion:** the best structural idea in the field, and *cheap for us* because we already own the hard half — HQL search plus saved filters. Composability belongs on the search result set, not in a widget grid. §2.6.

**Flow metrics over point metrics.** Aging work in progress and cycle time are described as diagnostic rather than decorative: *"If velocity is stable but cycle time and work-item age rise, the team may be starting more work or carrying hidden queues"* ([AgileSeekers](https://agileseekers.com/blog/mastering-scrum-metrics-velocity-burndown-flow-based-insights), [Parabol](https://www.parabol.co/blog/agile-charts/)). Azure DevOps ships Cycle Time and Lead Time as first-class widgets and tells teams to *"check these charts before or during each retrospective to find variations in efficiency and spot process problems"* ([Microsoft Learn](https://learn.microsoft.com/en-us/azure/devops/report/dashboards/analytics-widgets?view=azure-devops)).

**Conclusion:** ship cycle/lead time, and ship **aging WIP** — the "which of my open items is rotting" view — which is the actionable half and which almost nobody ships well.

**Burn-up, when scope moves.** Atlassian's own tutorial recommends considering burn-up charts when scope changes frequently, because a burn-up makes scope a visible line instead of an anomaly ([Atlassian burndown tutorial](https://www.atlassian.com/agile/tutorials/burndown-charts)). Azure DevOps ships Burndown *and* Burnup side by side and frames the burn-up's question as *"How much scope creep does your project have?"* ([Microsoft Learn](https://learn.microsoft.com/en-us/azure/devops/report/dashboards/analytics-widgets?view=azure-devops)).

### 1.2 Disliked — burndown, specifically

The brief asked whether "scope change mid-sprint" is really the accusation. It is *one of four*, and it is not the worst one. From an Atlassian Community walkthrough of *"what to do when it looks wrong"* ([source](https://community.atlassian.com/forums/App-Central-articles/How-to-actually-read-a-sprint-burndown-chart-and-what-to-do-when/ba-p/3254515)):

1. **Flat for days** — work is in progress but no status has been transitioned. The chart reports the *bookkeeping*, not the work.
2. **Cliff at the end** — everything is closed in the last two days, so the whole sprint reads as a failure until it suddenly reads as a success. Again bookkeeping.
3. **Mid-sprint jump** — scope added after start.
4. **No movement despite activity** — *"issues lack estimates"*. With unestimated issues in a points burndown the line simply cannot move.

And the accusation the brief asked about, verified with an important wrinkle: Jira's burndown counts **estimate changes as scope change**, so the Sprint Report and the Burndown Chart disagree with each other about how much scope changed — *"the Burndown Chart reflects both issues added to a sprint after it has started **and estimate changes**"* ([community thread](https://community.atlassian.com/forums/Jira-questions/Scope-change-in-Burndown-vs-Issues-added-to-sprint-in-Sprint/qaq-p/2891998)), producing the reported symptom of *"burndown shows scope change even after estimating hours before starting the sprint"* ([thread](https://community.atlassian.com/forums/Jira-questions/Issue-with-scope-changes-in-the-burndown-chart-when-sprint-is/qaq-p/1292547)).

**Conclusion — and it decides whether we ship one at all:** three of the four failure modes are properties of the *burndown form itself* (a single descending line whose slope confounds "work done", "scope changed" and "somebody updated a status"). Only #4 is fixable by configuration. **We do not ship a burndown.** We ship a burn-up whose scope line and completed line are separate series, and — decisively — **we define scope change as membership change only, never as an estimate change.** A re-estimate is never drawn as an event. That single rule deletes the disagreement documented above. (It moves the scope line as a whole, past included, because points are read current — see §2.3, where that trade is stated and why the alternative is not buildable.)

### 1.3 Disliked — cumulative flow diagrams

The strongest refusal in the document, and not a matter of taste.

- **Comprehension.** Coaches report *"people's eyes glaze over at the sight of a CFD"* and that teams *"pretend to understand CFDs while thinking it must be obvious to everyone else"* ([Medium: Agile's most misunderstood chart](https://medium.com/@brain1127/mastering-the-cumulative-flow-diagram-agiles-most-misunderstood-chart-df32ca399811)).
- **Correctness.** Mike Bowler on Jira's implementation: *"A Cumulative Flow Diagram by its very definition is cumulative; it can only go up. Yet, the Jira CFD sometimes goes down."* The cause is counting items per column at a moment and graphing the counts, which mis-renders items moving **backwards** on the board ([source](https://blog.mikebowler.ca/2026/03/27/cumulative-flow-diagram/)). Backwards movement is routine in our product — the workflow model explicitly supports it.
- **Usability at our scale.** In Jira's team-managed projects the CFD is unfilterable and therefore *"completely useless because it shows a total of the tickets across the entire project"*; a responder adds that even on new projects it degenerates into *"a bunch of horizontal lines telling you nothing"* ([thread](https://community.atlassian.com/forums/Jira-questions/Cumulative-Flow-Diagram-is-useless-in-team-managed-projects/qaq-p/2020961)). A Hamstrack project with 40k issues and three statuses would render exactly that.

**Conclusion: refuse the CFD.** It is the most expensive report to compute (the only one needing a per-day-per-status snapshot, i.e. the only reason we would need precomputation and a scheduler at all), the hardest to read, and the one our own workflow model would render incorrectly. **Everything a team actually gets from a CFD — where is the queue, what is stuck, how wide is WIP — is delivered better and cheaper by the aging-WIP report** (§2.2), which names the individual rotting item instead of shading an area. This contradicts HD-28's current scope; §10.

### 1.4 Disliked — velocity, when it escapes the team

The complaint is about the audience, not the chart. Velocity *"was never intended to be used to compare two teams"*; when it does, *"leaders misinterpret higher story point averages to mean one team is more productive"*, which *"harms their estimating process, creates inflated estimates, and demoralizes the team"* ([Platinum Edge](https://platinumedge.com/velocity-misuse-hurts-teams), [Agile Pain Relief](https://agilepainrelief.com/blog/misuse-of-velocity-in-agile-projects/)). The mechanism is specific: *"teams begin optimizing for points instead of outcomes … developers inflate estimates, avoid refactoring, and resist taking on complex items — velocity goes up but value does not"* ([Dev Interrupted](https://devinterrupted.substack.com/p/why-agile-velocity-is-the-most-dangerous)).

**Conclusion:** ship it, redesigned. Velocity in Hamstrack is **a forecast input, not a scoreboard**: a p50/p85 band over the last N completed sprints with the sample size stated, labelled team-relative, **never broken down per person**, and never surfaced above the project. §2.5.

### 1.5 Disliked — the configurable gadget dashboard

The epic's own third bullet, and the most consistent evidence in the research set.

- *"Nobody opened it again. It's a structure problem. Most Jira reporting dashboards are designed to show that data exists, not to answer the question someone actually has at 9am on a Monday."* The gadgets named as the typical, ineffective set are exactly the ones this epic proposes: sprint burndown, pie chart by status, filter results, velocity ([*14 gadgets and nobody looks at it*](https://community.atlassian.com/forums/App-Central-articles/Your-Jira-Reporting-Dashboard-Has-14-Gadgets-and-Nobody-Looks-at/ba-p/3216842)).
- The recurring support questions are *"Why is this report so slow?"*, *"These numbers don't match what I expected"*, *"Why are my gadgets blank again?"* ([*Your Jira reports are broken*](https://community.atlassian.com/forums/App-Central-articles/Your-Jira-Reports-Are-Broken-Here-s-the-Real-Reason/ba-p/3034274)). Blank gadgets have their own long-running threads ([example](https://community.atlassian.com/forums/Jira-questions/Dashboard-gadget-data-appearing-blank-for-some-of-the-users/qaq-p/1876029)).
- The diagnosis of wrong numbers is *scope*: overlapping filters double-count the same issue across widgets; averaging three issues produces *"noise"*; mixing workflows makes cycle time meaningless. The recommended mitigation is to keep *"gadget scopes under 200–300 issues"* — the product's own advice is that the feature does not survive contact with a real backlog.
- Asana's equivalent, a good implementation, still draws the same structural complaint: *"limited layout customization … no global filters to manage data across multiple widgets"* ([Coupler.io](https://blog.coupler.io/asana-reporting/), [ProjectManager](https://www.projectmanager.com/blog/asana-dashboard)). Global filters are the thing a widget grid cannot have and a view-attached panel gets for free.

**Conclusion: refuse the gadget-composition dashboard for v1** — it is a trap, and building it is how a reports feature becomes a maintenance liability that nobody opens. **Redesign, don't just drop:** the legitimate need underneath is "let me slice *my* data my way", and Linear answers that by attaching analytics to a filtered view. We already have the filtered view. So we ship an **Insights panel on the search results page** (§2.6): one dataset, one global filter by construction, no layout to maintain, and a saved filter becomes a saved report for free.

### 1.6 Export, sharing, scheduled delivery — the epic's acceptance criterion is half right

- **Native export does not exist and is heavily requested.** *"There is no native Jira sprint report export button"*; users fall back to screenshots, or to a JQL CSV export producing *"flat issue lists without burndown or velocity context"* ([sprint report guide](https://community.atlassian.com/forums/App-Central-articles/The-Ultimate-Guide-to-the-Jira-Sprint-Report-Metrics-Analysis/ba-p/3200893)). *"Jira doesn't have a built-in option for dashboard export"* and it is *"not possible with built-in features to automatically export dashboards and send them via email"*, hence a marketplace for it ([Midori](https://www.midori-global.com/blog/2022/06/20/send-jira-dashboard-via-email-in-datacenter); community requests: [1](https://community.atlassian.com/forums/Jira-questions/How-to-Generate-and-Email-Dashboard-Reports-PDF-on-a-Fixed/qaq-p/1979259), [2](https://community.atlassian.com/forums/Jira-questions/How-can-I-export-dashboard-created-for-a-team-s-sprint/qaq-p/1357076)).
- What is asked for is **a periodic PDF in an inbox**, and second, **the numbers behind the picture**. Nobody asks for a PNG; they screenshot — and the complaint about screenshots is not that they are hard to make but that Jira *"switches the elapsed-time axis scale depending on timeframe and outliers"*, so two screenshots are not comparable ([control chart analysis](https://community.atlassian.com/forums/App-Central-articles/Control-Chart-vs-Real-Workflow-Time-What-Jira-Shows-What-It/ba-p/3183409)).

**Conclusions.**
1. **CSV must export the plotted series**, not a flat issue list. "CSV" in the epic silently means the latter, which is the documented disappointment. Ship both, separately labelled.
2. **Ship a stable, shareable URL** (full report state in query params) — the cheapest sharing mechanism and the one people actually paste into Slack.
3. **PNG: ship it, cheaply** (client-side canvas) — costs almost nothing, and one axis rule makes it honest.
4. **Fixed axis rules.** Never auto-scale an axis to the data range on a report that is comparable across time. Zero-based, fixed ticks, date range printed inside the exported image. One rule, one whole class of distrust removed.
5. **Refuse scheduled email/PDF in v1** and say so out loud — a real demand but a separate epic (scheduler + renderer + per-recipient tenancy re-check at send time + unsubscribe + a DC answer for single-node scheduling).

### 1.7 What breaks at scale

- **Vendors cap their own reports.** Jira Data Center's Velocity Chart is limited to **25,000 issues and 120 sprints** *"for performance reasons"*, raised only by a REST call ([Atlassian KB](https://support.atlassian.com/jira/kb/how-to-increase-the-velocity-charts-limit-of-120-sprints-and-25000-estimated-issues-in-jira-software-server-and-data-center/)).
- **ClickUp is the cautionary tale.** Its own feedback forum carries *"the workload view takes about 60 seconds to load"*, *"performance is HORRIBLE, especially in the morning"*, *"2 hours of work can take 4 hours"* ([performance](https://feedback.clickup.com/feature-requests/p/performance), [make it faster](https://feedback.clickup.com/feature-requests/p/make-it-faster)).
- **Azure DevOps solved it by not solving it in the transactional store** — Analytics is a separate read model with its own *View analytics* permission, enabled as a service ([Microsoft Learn](https://learn.microsoft.com/en-us/azure/devops/report/dashboards/analytics-widgets?view=azure-devops)). Right at their scale, wrong at ours: a second read model needs a refresh job, which needs a DC story — exactly the cloud-only assumption `CLAUDE.md` forbids.
- **Wrong numbers come from scope, not arithmetic.** *"Garbage in = garbage out"* — average cycle time over 1,500 heterogeneous issues spanning years, or lead time including unresolved issues, is precise and meaningless ([source](https://community.atlassian.com/forums/App-Central-articles/Your-Jira-Reports-Are-Broken-Here-s-the-Real-Reason/ba-p/3034274)).

**Conclusions.**
1. **Every report is bounded, and the bound is disclosed.** A cap that silently truncates is how you get "these numbers don't match what I expected". Every response carries `meta.basedOnIssues`, `meta.truncated`, `meta.computedAt`, and the UI prints it.
2. **Refuse the one unbounded report** (CFD) rather than build the infrastructure it needs. That single refusal is what lets v1 ship with zero precomputation, zero scheduler, zero new operational surface.
3. **Percentiles, never rolling averages.** Jira's control-chart rolling average is issue-count-based (*"20% of the total items displayed, centred on each item … not a last-7-days average"*), so *"if throughput changes, your rolling average behaviour changes even if the process doesn't"* ([source](https://community.atlassian.com/forums/App-Central-articles/Control-Chart-vs-Real-Workflow-Time-What-Jira-Shows-What-It/ba-p/3183409)). p50/p85 over a stated window is comprehensible, stable, and states its own sample size.

### 1.8 Open-source peers — what the field does not have

OpenProject's reporting strength is **time and cost** (cost reports, budgets, planned vs realised), with burndown via the backlogs module ([docs](https://www.openproject.org/docs/user-guide/time-and-costs/reporting/)). Redmine's is the spent-time report, and its documented weaknesses are of the same family — no subtask time roll-up on the issue list, missing custom fields in the report builder ([Redmine #11253](https://www.redmine.org/issues/11253), [Time Doctor analysis](https://www.timedoctor.com/blog/redmine-time-tracking/)). Plane positions Analytics as real-time issue visualisation ([Plane](https://plane.so/blog/introducing-plane-simple-extensible-open-source-project-management-tool)). Shortcut ships Burndown, CFD and Cycle Time and users report *"using the reporting features on a daily basis"* ([Shortcut reports](https://www.shortcut.com/product/reports), [Capterra](https://www.capterra.com/p/160498/Shortcut/reviews/)). YouTrack's burndown is period-field-driven and tied to time tracking ([docs](https://www.jetbrains.com/help/youtrack/cloud/burndown.html)).

**Conclusion:** the open-source field's reporting centre of gravity is **time and cost**, which Hamstrack does not model natively (no time tracking; `estimate_hours` / `time_spent_hours` are optional custom fields bound to nothing by default). We are not competing there and should not pretend to. Our differentiator is that the *flow* reports are free of the four failure modes in §1.2.

### 1.9 Sources

Atlassian Community — [14 gadgets and nobody looks at it](https://community.atlassian.com/forums/App-Central-articles/Your-Jira-Reporting-Dashboard-Has-14-Gadgets-and-Nobody-Looks-at/ba-p/3216842) · [Your Jira reports are broken](https://community.atlassian.com/forums/App-Central-articles/Your-Jira-Reports-Are-Broken-Here-s-the-Real-Reason/ba-p/3034274) · [Reading a sprint burndown when it looks wrong](https://community.atlassian.com/forums/App-Central-articles/How-to-actually-read-a-sprint-burndown-chart-and-what-to-do-when/ba-p/3254515) · [Sprint report guide + export](https://community.atlassian.com/forums/App-Central-articles/The-Ultimate-Guide-to-the-Jira-Sprint-Report-Metrics-Analysis/ba-p/3200893) · [Control chart vs real workflow time](https://community.atlassian.com/forums/App-Central-articles/Control-Chart-vs-Real-Workflow-Time-What-Jira-Shows-What-It/ba-p/3183409) · [Scope change: burndown vs sprint report](https://community.atlassian.com/forums/Jira-questions/Scope-change-in-Burndown-vs-Issues-added-to-sprint-in-Sprint/qaq-p/2891998) · [Scope change when a sprint starts early](https://community.atlassian.com/forums/Jira-questions/Issue-with-scope-changes-in-the-burndown-chart-when-sprint-is/qaq-p/1292547) · [CFD useless in team-managed projects](https://community.atlassian.com/forums/Jira-questions/Cumulative-Flow-Diagram-is-useless-in-team-managed-projects/qaq-p/2020961) · [Blank gadgets](https://community.atlassian.com/forums/Jira-questions/Dashboard-gadget-data-appearing-blank-for-some-of-the-users/qaq-p/1876029) · [Emailing a dashboard PDF on a schedule](https://community.atlassian.com/forums/Jira-questions/How-to-Generate-and-Email-Dashboard-Reports-PDF-on-a-Fixed/qaq-p/1979259) · [Automating sprint dashboard email](https://community.atlassian.com/forums/Jira-questions/How-can-I-export-dashboard-created-for-a-team-s-sprint/qaq-p/1357076).
Atlassian — [burndown tutorial](https://www.atlassian.com/agile/tutorials/burndown-charts) · [velocity chart limits KB](https://support.atlassian.com/jira/kb/how-to-increase-the-velocity-charts-limit-of-120-sprints-and-25000-estimated-issues-in-jira-software-server-and-data-center/).
Microsoft — [Analytics widgets overview](https://learn.microsoft.com/en-us/azure/devops/report/dashboards/analytics-widgets?view=azure-devops) · [CFD/cycle/lead guidance](https://learn.microsoft.com/en-us/azure/devops/report/dashboards/cumulative-flow-cycle-lead-time-guidance?view=azure-devops).
Linear — [Insights docs](https://linear.app/docs/insights) · [Insights changelog](https://linear.app/changelog/2023-03-23-linear-insights) · [G2 pros & cons](https://www.g2.com/products/linear/reviews?qs=pros-and-cons) · [siit review](https://www.siit.io/tools/trending/linear-app-review).
Mike Bowler — [CFDs](https://blog.mikebowler.ca/2026/03/27/cumulative-flow-diagram/). Medium — [Agile's most misunderstood chart](https://medium.com/@brain1127/mastering-the-cumulative-flow-diagram-agiles-most-misunderstood-chart-df32ca399811).
Velocity misuse — [Platinum Edge](https://platinumedge.com/velocity-misuse-hurts-teams) · [Agile Pain Relief](https://agilepainrelief.com/blog/misuse-of-velocity-in-agile-projects/) · [Dev Interrupted](https://devinterrupted.substack.com/p/why-agile-velocity-is-the-most-dangerous).
ClickUp feedback — [performance](https://feedback.clickup.com/feature-requests/p/performance) · [make it faster](https://feedback.clickup.com/feature-requests/p/make-it-faster).
Asana — [Coupler.io](https://blog.coupler.io/asana-reporting/) · [ProjectManager](https://www.projectmanager.com/blog/asana-dashboard). Shortcut — [reports](https://www.shortcut.com/product/reports) · [Capterra](https://www.capterra.com/p/160498/Shortcut/reviews/). YouTrack — [burndown](https://www.jetbrains.com/help/youtrack/cloud/burndown.html) · [reporting features](https://www.jetbrains.com/youtrack/features/reporting_analysis.html). OpenProject — [time & cost reporting](https://www.openproject.org/docs/user-guide/time-and-costs/reporting/). Redmine — [#11253](https://www.redmine.org/issues/11253) · [Time Doctor](https://www.timedoctor.com/blog/redmine-time-tracking/). Plane — [introduction](https://plane.so/blog/introducing-plane-simple-extensible-open-source-project-management-tool). Flow metrics — [AgileSeekers](https://agileseekers.com/blog/mastering-scrum-metrics-velocity-burndown-flow-based-insights) · [Parabol](https://www.parabol.co/blog/agile-charts/).

---

## 2. What we ship

### 2.0 Problem & goal

A Hamstrack team can see *what* is in a project — board, backlog, search — and can see nothing about *how it is going*. Today a lead answering "are we on track", "what is stuck", "how much can we take next sprint" has to eyeball the board or run three HQL queries and count rows. The goal is that each question is answered by opening one page with no configuration, that the numbers are trustworthy enough to be quoted to a stakeholder, and that the page stays fast on a project with 100k issues. Success is the reports being opened weekly without anyone being told to — which, per §1.5, is exactly what configurable dashboards fail to achieve.

**Scope — in:** six read-only surfaces (§2.1–2.6), one ledger table, one issue column, CSV/PNG/URL export. **Out:** CFD, burndown, gadget dashboard, per-person metrics, scheduled delivery, time/cost, workspace rollups, alerting, goals/OKRs, forecasting beyond a stated percentile band.

### 2.1 Flow — created vs resolved

**Question:** are we keeping up? Is the backlog growing or shrinking, and since when?

**Shape:** one chart, two lines, weekly or daily buckets over a selected window (default: last 90 days, weekly). Line A = created in the bucket. Line B = resolved in the bucket. A secondary line = open count at bucket end (cumulative created − cumulative resolved). Below: three numbers — created, resolved, net — for the whole window. Optional filters: type, component, label.

**Data:** `issues.created_at` and `issues.closed_at`. **Nothing else. No history table.**

**Cost:** two grouped queries (`date_trunc` + `count`) over `issues` filtered by `project_id` and the window, plus one count for the opening balance. Needs the indexes in §5.1. On a 100k-issue project with a 90-day window this is an index range scan over thousands of rows, not 100k.

**Capability:** none — works in every project.

**Known imprecision, disclosed in the UI:** `closed_at` is *cleared* when an issue leaves a DONE status (`docs/project-state.md` → System fields). So the resolved series is "currently-closed issues, dated by their most recent closure" — reopening removes an issue from a past bucket. This is a real "the number changed under me" hazard and the footnote says so: *"Resolved counts issues that are closed now, dated by their latest closure. Reopened issues move."* No resolution-event ledger in v1; §9 OQ 5.

**Empty state:** a project with under two weeks of data renders the short window plus *"Only N days of history — trends need a few weeks."* Never a blank panel.

### 2.2 Cycle time, lead time & aging work in progress

**Question:** how long does work take, and *which open item is rotting right now*?

**Shape — one page, two halves.**

*Finished work* — a scatter: x = completion date, y = days, each dot one issue, clickable. Two horizontal reference lines: **p50 and p85** of the window, with sample size printed (`based on 214 issues`). Toggle between **cycle time** (`started_at → closed_at`) and **lead time** (`created_at → closed_at`). No rolling average, ever (§1.7).

*Unfinished work — aging WIP* — a column per non-DONE status of the project's effective workflow; each column a stack of dots by **age since `started_at`** (or since `created_at` for items never started), oldest at top, each labelled with issue key and assignee. Reference lines are drawn across it too, so an item above p85 is visibly older than 85% of everything the team has ever finished. That is the point: it names the item.

**Those reference lines are cycle time over the project's whole completed history — not the window, and not the selected measure.** Deliberately: aging has no window of its own, so a windowed percentile here would be an arbitrary comparison. They therefore differ from the lines on the finished half, which means both halves must say which they are showing. The suppression threshold is shared, though: a reader told "not enough completed work" on the scatter must not find a confident p85 painted across the columns beside it.

**Data:** `created_at`, `closed_at`, and a **new column `issues.started_at`** (§5.1) stamped on first entry into an `IN_PROGRESS`-category status. Aging WIP additionally reads the project's effective statuses through `ProjectConfigService` (already cached).

**Cost:** the finished half is two statements (the bounded row query and one combined aggregate). The aging half is **two on the steady-state path and three on a cold one**, and the split is deliberate — see below. Percentiles via `percentile_cont(...) WITHIN GROUP (ORDER BY ...)` in PostgreSQL, not in Java. Row cap (`app.reports.max-rows`, default 20 000) with `meta.truncated`, never silent truncation.

**Why aging's third statement exists, and why it is cached** (HD-138 R3 round 2 — do not "simplify" it back into one query): the reference lines above are computed over the project's *entire* completed history, so that statement has **no window, no row cap, and nothing the caller can narrow**, and it grows for the life of the project. It is the only unbounded read in the feature; every other one is bounded by a validated window or by the row cap, while the per-principal throttle charges one unit for it exactly as for a cheap request. It is therefore split out of the open-work aggregate and fronted by a **60-second per-project cache** — the same freshness the response already advertises to the client as `Cache-Control: private, max-age=60`, so nothing is staler than the contract already permitted. The claim in bold above is preserved exactly: the pass still covers the whole history when it runs, it just runs at most once per project per minute per node. Only that half is cacheable; the open counts stay live, because a minute-old open count printed above a live item list could disagree with the rows underneath it. Measured on a 100k-issue project: ~120 ms of database time per request before, ~65 ms warm, and the old ~120 ms on a miss.

**Capability:** none.

**`started_at` and the honesty rule:** cycle time is defined only for issues that have a `started_at`. The migration backfills best-effort from `issue_history` (§5.1) and the response reports `missingStartCount`; the UI prints *"cycle time available for 812 of 940 completed issues"*. Lead time is defined for everything from day one, so the page is never empty. **We never silently substitute `created_at` for a missing `started_at`** — that is how a cycle-time report becomes a lead-time report wearing a false name.

**Empty state:** under 5 completed issues in the window → percentile lines suppressed with *"Not enough completed work to compute percentiles (need 5, have 3)"*. Printing noise is worse than printing nothing (§1.7).

### 2.3 Sprint burn-up (with a real scope line)

**Question:** will this sprint land, and what happened to the plan?

**Shape:** for one sprint (default: the ACTIVE one). X = day, start to end. Two lines:
- **Scope** — total work in the sprint that day. Steps up on adds, down on removes. Every step hoverable, naming the issue and who moved it.
- **Completed** — cumulative work closed by that day.

Plus a faint **ideal** guide from (start, 0) to (end, scope-at-start) — a guide, not a verdict, drawn to the *committed* scope, not the current scope. Below: the **scope-change log** — every add/remove with timestamp, issue, actor, delta.

Measure toggle: **issue count** (default) or **story points**.

**The two rules that make it different from a burndown:**
1. **Scope change is membership change only.** A re-estimate is never a scope event and is not drawn as a step. This deletes the documented Jira disagreement (§1.2).

   An earlier draft of this rule said a re-estimate "changes the scope line's height going forward". **It does not, and it cannot** — the ledger (§5.2) records a row only when membership changes, so a re-estimate that moves no issue leaves no trace and a per-day estimate history is not reconstructible from anything we store. The rule below is the one that governs.
2. **The line ends where it ends.** No projection, no "at this rate you'll finish Thursday". Forecasting lives in §2.5 with a stated sample size.

**Data:** `sprint_scope_events` (§5.2) for the scope line, `issues.closed_at` for the completed line, `issues.story_points` for points.

**Points are the issue's CURRENT points** (decision, 2026-08-19). The consequence is worth stating plainly rather than burying in a footnote: **a re-estimate moves the whole scope line, including its past**, so a chart read yesterday can look different today. That is the honest trade. The alternative — freezing each issue's points at the moment it entered the sprint, which the ledger's `story_points` column does snapshot — buys an immovable history at the price of a re-estimate never being visible anywhere, and it would make a sprint's scope disagree with the same issues' current estimates on every other screen in the product.

The UI footnotes it: *"Points reflect current estimates."*

The ledger's snapshot is **not** made redundant by this. It answers a different question, and the one the sprint review (§2.4) actually asks: *what did this issue weigh when it entered this sprint* — which is exactly what a retrospective needs and what current points destroy.

**Cost:** **7 statements — 4 resolution + 3** (the sprint, the ledger, and one statement carrying both `meta` scalars — `firstIssueAt` and the ledger's distinct-issue count), and the same 7 for the sprint review (§2.4): one joined ledger query serves both, so the two reports cannot disagree about what was in the sprint. The ledger for one sprint is O(sprint size + changes) — hundreds of rows — and the join is O(ledger rows), not O(project issues): measured at **2.7 ms** on a 25-issue project and **~4 ms** on a 102 000-issue one, against a seeded 249 000-row ledger. Cheapest report in the set.

`meta.firstIssueAt` **cannot** be spliced into the ledger query the way §2.1 and §2.2 splice it, because that query legitimately returns zero rows for an empty sprint and a scalar in a no-row result never arrives. Hence the third statement — which also carries `meta.basedOnIssues`, counted **above** `app.reports.max-rows` rather than from the grouped rows that survived it: the field's family contract (§4.3) is that it states how many issues the report was *about*, so when a cap bites it is deliberately larger than what came back, and a field whose job is to disclose truncation must not shrink with the data it discloses.

**Capability — `board`:** the API answers for any sprint that exists, in any project, regardless of `board` (Rule A). The **UI** shows this report only when `board = SCRUM`; when KANBAN it is listed but disabled with the Rule C affordance *"This project doesn't run sprints — turn on Scrum in project settings"*, linking there. **Never decided by whether sprints exist in the data** (the documented shipped bug).

**Capability — `estimation`:** with estimation OFF the UI omits the points toggle and shows count; the API still returns both series (Rule A). Existing points still render read-only in the scope-change log (Rule B) — which is why every `ScopeChange` carries the ledger's `storyPoints` snapshot **beside** its measure-dependent `delta`: under `COUNT` the delta is ±1, so without it no point value reaches the log at all and the Rule B affordance has nothing to show. Rule C: *"Turn on estimation to chart story points"* next to the disabled toggle.

**The series has a day bound, and it is not `meta.truncated`.** A sprint longer than `app.reports.max-window-days` is drawn from its **first** day (the commitment is what later numbers are read against) and the response says where it stopped: `seriesTruncatedAt` names that day, null when the whole sprint is drawn. `meta.truncated` keeps its one meaning — the `max-rows` ledger cap bit, the number printed beside it in `meta.cap` — because folding the two together made a twelve-issue sprint answer `truncated: true, cap: 20000` and put "20 000" in a banner about a report that dropped no rows. The scope-change log and `unestimatedCount` are clipped to the same day; the sprint review is a list report and is not clipped, so on a clipped sprint the two legitimately describe different spans, which is exactly what the field announces.

**Empty state:** no sprint has ever existed → the whole report is replaced by the Rule C card, not by an empty chart.

### 2.4 Sprint review record

**Question (the one teams actually ask at retro):** what did we commit to, what arrived late, what did we finish, what carried over?

**Shape:** not a chart. Five labelled lists with counts and point sums: **Committed** (in the sprint when it started) · **Added after start** · **Removed before end** · **Completed** · **Carried over**. Each list is issue rows (key, title, type, assignee, points, status), clickable. One header line: *"Sprint 12 · 14 Aug – 28 Aug · completed 18 of 25 issues (41 of 60 points) · 5 added after start."*

**The denominator is what the sprint held at its end** — completed plus carried over — not what it committed to. An earlier draft compared completions against the *commitment*, which counts two different populations: work added after the start can be completed, so the numerator was not a subset of its own denominator and the ratio could exceed one. The commitment is not lost by this: it is a labelled list of its own, and the *"5 added after start"* clause is precisely the disclosure of how far the sprint drifted from it.

**Points per list are nullable, and null is not zero.** A list in which nothing was estimated reports `points: null` (with `unestimatedCount` saying how many), not `0` — "we didn't estimate this" and "this is worth nothing" are different statements that a bare zero renders identically, and the alternative made every client re-derive emptiness from `count > unestimatedCount` in five places. Empty lists are null for the same reason.

**Data:** `sprint_scope_events` + current issue state + `closed_at`. For a COMPLETED sprint this is a permanent, exact record because the ledger is append-only and id-keyed.

**Cost:** none of its own — it shares §2.3's ledger query and its 7 statements. Bounded by sprint size.

**Capability:** same as §2.3.

**Why this and not "the burndown's tooltip":** §1.1 — this is the artefact people open, and it is the cheapest thing in this document to build.

### 2.5 Velocity — as a forecast band, never a scoreboard

**Question:** how much should we plan for next sprint?

**Shape:** a small bar chart of the last N completed sprints (N default 6, max 12), each bar showing **completed** with the **committed** level marked. Beside it, the actual output: a band — *"Recent sprints delivered between 14 and 23 issues; plan for ~18 (p50) and treat 23 (p85) as a stretch. Based on 6 sprints."* Measure toggle count/points as in §2.3.

**Hard design rules, from §1.4:**
- **No per-person breakdown.** Not as a filter, not as a tooltip, not in the CSV.
- **No cross-project or workspace-level velocity.** Project-scoped endpoint, no aggregate above it. Comparing two teams must be done by hand — that is the intended amount of friction.
- Permanent caption: *"Story points are team-relative. Velocity is not comparable between teams."*
- Fewer than 3 completed sprints → band suppressed, sample size stated.

**Data:** `sprint_scope_events` + `closed_at` + `story_points`, over the last N COMPLETED sprints.

**Cost:** one grouped query over the ledger joined to issues, bounded by N. Cap N at 12 — Jira caps at 120 sprints / 25 000 issues for the same reason (§1.7); we cap lower and say so.

**Capability:** same as §2.3.

### 2.6 Insights panel on search results — the dashboard replacement

**Question:** anything the five fixed reports do not answer.

**Shape:** a collapsible panel on `SearchResultsPage` (and reachable from a saved filter), modelled on Linear's structure and on our own existing surface:

- **Dataset** = the HQL query currently in the box. Already tenancy-scoped by `SearchScope`, already validated, already saveable. **This is the global filter a widget grid can never have.**
- **Measure** (y): issue count · story points · none.
- **Slice** (x): status · type · priority · assignee · component · label · sprint · project.
- **Segment** (colour, optional): same list.
- Render: grouped/stacked bar plus the numbers as a table underneath. Clicking a bar narrows the HQL query — the panel is a *navigation* device as much as a chart.

**Why this instead of gadgets:** one dataset means no double-counting across widgets (§1.5); no layout to save, break or migrate; a saved filter *becomes* a saved report at zero cost; and the whole feature is one endpoint over machinery that already exists.

**Cost:** one `GROUP BY` over the existing search Criteria query with page/sort dropped. Same cap and `meta` as everything else.

**The assignee slice is allowed here and refused in §2.5, and the combination that looks like a loophole is reachable.** Slicing by sprint, colouring by assignee and measuring in points produces, in substance, the cross-project per-person velocity chart §2.5 exists to refuse. That is accepted, not overlooked, and the reason is the distinction the whole section rests on: this is a query a person typed, against the filter they were already looking at, with no stable address. §2.5 is a *published metric* — a named report, at a URL, that people quote at each other and that acquires authority by being on a page called Velocity. The harm §1.4 documents comes from the second thing, not the first.

Two consequences worth stating rather than leaving implied. **No structural guard can see this combination** — §2.5's refusals are enforced by tests keyed on its own records and paths, and an ad-hoc slice matches neither, so the line is held by this paragraph and the javadoc rather than by a mechanism. And it follows that the line must not be defended by narrowing the panel: removing the assignee slice would break the ad-hoc case the panel exists for while leaving anyone determined to build a scoreboard one export away. **What must never happen is the reverse — this combination acquiring a name, a saved home or a navigation entry.** The moment it does, it is §2.5 with the refusals stripped off, and it should be refused as such.

**Capability:** the slice list omits `sprint` when `board = KANBAN` and `story points` when estimation is off — mirroring `/search/schema`'s existing "suggestions narrow, resolution never does" rule. A slice the UI hides still resolves if requested (Rule A).

---

## 3. What we deliberately refuse

| Refused | Why (evidence) | What replaces it |
|---|---|---|
| **Cumulative flow diagram** | Misunderstood by design (*"eyes glaze over"*); Jira's implementation **goes down**, which a cumulative chart cannot do, because items move backwards on the board — normal in our workflow model; unfilterable CFDs degenerate to *"horizontal lines telling you nothing"*. Also the **only** report needing a per-day-per-status snapshot, i.e. the only reason we'd need precomputation, a scheduler and a DC story for it. §1.3 | **Aging WIP** (§2.2) — same questions, names the stuck item instead of shading an area. |
| **Burndown** | Three of four documented failure modes are properties of the chart form, not of configuration: flat lines from late transitions, end-of-sprint cliffs from batch closing, mid-sprint jumps from scope. Plus Jira's own two reports disagree about scope because estimate changes count as scope change. §1.2 | **Burn-up with an explicit scope line** (§2.3), scope change = membership change only. |
| **Configurable dashboard of gadgets** | Most consistent complaint in the research: abandoned within weeks; blank, slow, double-counting across overlapping filters; vendor's own advice is to keep gadget scope under 200–300 issues. The gadget list named as typical-and-ineffective is *this epic's own list*. §1.5 | **Fixed, opinionated reports** + the **Insights panel on search** (§2.6), which has a global filter by construction. |
| **Rolling-average control chart** | Average computed over 20% of displayed *items*, not over time, so it moves when throughput moves even if the process doesn't; axis auto-scaling makes two screenshots incomparable. §1.7 | **p50/p85 percentiles** with sample size printed, and fixed axes (§1.6 #4). |
| **Per-person metrics** (velocity by assignee, "top closer") | Velocity misuse is the best-documented harm in the set: cross-comparison inflates estimates, discourages complex work, demoralises teams. §1.4 | Assignee slicing exists **only** in the ad-hoc Insights panel, never as a published report — which is also what lets us ship with no new permission (§4.2). |
| **Scheduled email / PDF delivery** | Genuinely demanded (§1.6) but a separate epic: scheduler (ours is single-node — see rate limiting), HTML/PDF renderer, per-recipient tenancy re-check at send time, unsubscribe. Shipping it badly is worse than not shipping it. | **Shareable URLs** + **CSV of the plotted series** + **PNG** (§4.4). Named as a follow-up epic, not silently dropped. |
| **Time / cost reports** | No native time tracking; `estimate_hours` / `time_spent_hours` are optional custom fields bound to nothing by default. Reports on data most installs don't collect is how you get blank gadgets. §1.8 | Nothing in v1. Revisit if native time tracking lands. |
| **Workspace-wide cross-project rollup** | Cross-project is where native gadgets are documented to fail (*"three separate dashboards or one extremely confused merged filter"*), and doing it correctly at 100k+ issues is the case that would force precomputation. | The Insights panel already spans projects, because HQL search does (`project` is a slice). A dedicated rollup is §9 OQ 4. |

---

## 4. Actors, permissions, API and behaviour

### 4.1 Actors & scope

| Actor | May |
|---|---|
| Any **workspace member** who can reach the project | Open every report, export CSV/PNG, share the URL |
| Non-member / unknown workspace or project | **404** — never 403 |
| Anonymous | 401 |

All report endpoints resolve through `WorkspaceAccessService.resolveProject(actor, wsId, projectId)` — the same 4-statement resolution as every other project-scoped read. Sprint-scoped reports additionally resolve the sprint with `findByIdAndProject`, so a sprint id from another project is **404**, not a cross-tenant read. No report endpoint accepts a list of project ids; the Insights panel goes through the existing `SearchScope`, which ANDs `workspace = :ws AND project.id IN :visibleProjectIds` as the outermost conjunction.

### 4.2 Permission model — no new permission, and why

**Decision: reports require project membership and nothing else. No `report.view` permission in v1.**

1. **Reads are deliberately not permission-gated in this product.** There is no `issue.view` or `project.view` (`Permission` javadoc: *"shipping a permission every role must have is dead code that lies"*). A `report.view` would be the first read gate and inconsistent with everything around it.
2. **It would protect nothing.** Every number in every v1 report is derivable by any member from the search API they already have. A gate walked around by one HQL query is theatre.
3. **The thing that would justify a gate, we are not shipping.** The sensitive report is the per-person one, and §3 refuses it outright.
4. **The precedent supports us.** Azure DevOps *does* have a distinct *View analytics* permission — and defaults it to **all project members** ([Microsoft Learn](https://learn.microsoft.com/en-us/azure/devops/report/dashboards/analytics-widgets?view=azure-devops)). Even the vendor that modelled it does not restrict it by default.

**Recorded trigger for revisiting:** the day a per-assignee breakdown, a workspace rollup, or an export of another user's activity is proposed, add `report.view.people` (project-scoped, own-optional) *in the same change*. Per the roles model that is an enum constant, a `require(...)` line and one seed row per built-in — never a migration.

**Archived projects:** reports are readable on an archived project (they are history; the write freeze does not apply to reads). The UI shows the standard archived banner.

### 4.3 API surface

All under `/api/workspaces/{wsId}/projects/{pId}/reports`. All `GET`, read-only, returning `200` with a `meta` block. `Cache-Control: private, max-age=60`.

```
GET  /flow?from=&to=&interval=DAY|WEEK&typeId=&componentId=&labelId=
GET  /cycle-time?from=&to=&typeId=&componentId=&labelId=
GET  /aging
GET  /sprint-burnup?sprintId=&measure=COUNT|POINTS
GET  /sprint-review?sprintId=
GET  /velocity?sprints=6&measure=COUNT|POINTS

GET  /{report}.csv?<same params>              # text/csv of the plotted series
POST /api/workspaces/{wsId}/search/insights   # {query, measure, slice, segment}
```

Shared `meta` on every response:

```jsonc
"meta": {
  "computedAt": "2026-08-19T09:14:22Z",
  "basedOnIssues": 1842,     // rows the numbers were computed from
  "truncated": false,        // true when the row cap bit
  "cap": 20000               // the cap that would bite
}
```

Representative shapes (records; Jackson 3 rules — boxed types for optional fields, coalesced in a compact constructor):

```jsonc
// GET /flow
{ "from":"2026-05-21","to":"2026-08-19","interval":"WEEK",
  "buckets":[{"date":"2026-05-21","created":14,"resolved":9,"openAtEnd":121}, …],
  "totals":{"created":142,"resolved":137,"net":5}, "meta":{…} }

// GET /cycle-time
{ "from":…, "to":…,
  "items":[{"issueId":"…","key":"DEMO-14","title":"…","typeId":"…",
            "startedAt":"…","closedAt":"…","cycleDays":4.2,"leadDays":11.7}, …],
  "percentiles":{"cycle":{"p50":4.1,"p85":12.6},"lead":{"p50":9.0,"p85":28.4}},
  "sampleSize":214, "missingStartCount":128, "meta":{…} }

// GET /aging
{ "columns":[{"statusId":"…","name":"In Progress","category":"IN_PROGRESS",
              "items":[{"issueId":"…","key":"DEMO-31","title":"…","ageDays":19.4,
                        "assigneeId":"…","startedAt":"…"}, …]}, …],
  "percentiles":{"p50":4.1,"p85":12.6}, "meta":{…} }

// GET /sprint-burnup
{ "sprint":{"id":"…","name":"Sprint 12","state":"ACTIVE"},
  "startAt":"…","endAt":"…","measure":"COUNT","committedAtStart":23,
  "unestimatedCount":2,
  "series":[{"date":"2026-08-14","scope":23,"completed":0}, …],
  "scopeChanges":[{"at":"…","issueId":"…","key":"DEMO-77","event":"ADDED",
                   "delta":1,"actorId":"…","storyPoints":5}, …],
  "seriesTruncatedAt":null, "meta":{…} }

// GET /sprint-review — five lists, each {count, points, unestimatedCount, issues[]}
{ "sprint":{…},"startAt":"…","endAt":"…","completedAt":"…",
  "committed":{…},"addedAfterStart":{…},"removedBeforeEnd":{…},
  "completed":{…},"carriedOver":{…},
  "totals":{"committedCount":23,"committedPoints":55,
            "atEndCount":25,"atEndPoints":60,
            "completedCount":18,"completedPoints":41,"addedAfterStartCount":5},
  "meta":{…} }

// GET /velocity
{ "measure":"COUNT",
  "sprints":[{"sprintId":"…","name":"Sprint 7","committed":21,"completed":18,
              "addedAfterStart":4,"carriedOver":3}, …],
  "forecast":{"p50":18.0,"p85":23.0,"sampleSize":6}, "meta":{…} }
```

**Status codes.** `200` · `400` on a malformed or over-long window (`from > to`, or `to - from > app.reports.max-window-days`) with the cap named in the detail — **never a silent clamp**, that is the "numbers don't match" failure · `400` on `sprints > 12` · `404` workspace/project not visible, or `sprintId` not in this project · `401` anonymous. **No status code anywhere depends on a delivery capability** (Rule A).

`openapi.yaml` + `docs/api-cloud.md` + `docs/api-dc.md` must follow (`api-docs-sync`).

### 4.4 Export

- **`GET /{report}.csv`** — the **plotted series**, one row per data point, with a comment header carrying project, window, measure, `computedAt`, `basedOnIssues`. This is the one the research says is missing everywhere (§1.6).
- **"Download matching issues"** — a separate, clearly-labelled link handing off to the existing search export path, so the two are never confused.
- **PNG** — client-side only (`canvas`), copy-to-clipboard and download. Every image embeds project, window and `computedAt` in the footer; every axis is **zero-based with fixed ticks** so two exports are comparable (§1.6 #4).
- **Shareable URL** — full report state in query params. No new backend.

---

## 5. Data model impact

One table, one column, four indexes, one config property pair.

### 5.1 `issues.started_at` (new column) + indexes

```sql
-- V17__reports_foundations.sql  (illustrative; builder owns the final file)
ALTER TABLE issues ADD COLUMN started_at TIMESTAMPTZ;

CREATE INDEX idx_issues_project_created ON issues (project_id, created_at);
CREATE INDEX idx_issues_project_closed  ON issues (project_id, closed_at)
    WHERE closed_at IS NOT NULL;
CREATE INDEX idx_issues_project_started ON issues (project_id, started_at)
    WHERE started_at IS NOT NULL;
```

**Semantics** — symmetric with the existing `closed_at`, with one deliberate asymmetry:

- Set to `now()` the **first** time an issue enters a status whose category is `IN_PROGRESS` **or** `DONE` (an issue dragged straight to Done was started, then finished).
- **Never cleared.** `closed_at` is cleared on leaving DONE because "is it closed" is a current-state question; "when did work start" is not. Clearing would make cycle time shrink retroactively.
- Entity: `@Column(name = "started_at")`, `OffsetDateTime`, plain writable field written only in `IssueService` alongside the existing `closedAt` logic (and inside the same pre-mutation ordering, to avoid the `@Version` double-write flush trap). No `updatable=false` — there is no native writer.

**Backfill — best effort, and labelled as such.** In the same migration:

```sql
UPDATE issues i SET started_at = sub.first_move FROM (
  SELECT h.issue_id, MIN(h.created_at) AS first_move
    FROM issue_history h
    JOIN statuses s ON s.name = h.new_value
   WHERE h.field = 'status' AND s.category IN ('IN_PROGRESS','DONE')
   GROUP BY h.issue_id
) sub WHERE i.id = sub.issue_id AND i.started_at IS NULL;
```

This joins history to statuses **by display name**, because that is all `issue_history` stores (`field VARCHAR(50)`, `old_value`/`new_value` TEXT — no ids, no project column, index only `(issue_id, created_at DESC)`). It is therefore approximate: a renamed status is invisible to it, and two same-named statuses in different workflows collide harmlessly (the category is what matters). Every issue it cannot resolve keeps `started_at = NULL` and is reported as `missingStartCount`, never guessed. **Do not "improve" this by falling back to `created_at`** — §2.2.

> This name-keyed weakness of `issue_history` is also the reason the sprint scope line is **not** derived from it. See §5.2.

### 5.2 `sprint_scope_events` (new table) — the ledger

```sql
CREATE TABLE sprint_scope_events (
    id           UUID        PRIMARY KEY,                 -- UUID v7, app-generated
    workspace_id UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    sprint_id    UUID        NOT NULL REFERENCES sprints(id)    ON DELETE CASCADE,
    issue_id     UUID            NULL,   -- nullable; composite FK below, ON DELETE SET NULL
    issue_key    VARCHAR(40) NOT NULL,   -- snapshot: survives the issue
    event        VARCHAR(10) NOT NULL,        -- ADDED | REMOVED  (Java enum; never a PG ENUM)
    story_points NUMERIC(5,2),                -- points at the moment of the event, nullable
    actor_id     UUID            REFERENCES users(id),
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT sprint_scope_events_event_ck CHECK (event IN ('ADDED','REMOVED'))
);
CREATE INDEX idx_sprint_scope_events_sprint ON sprint_scope_events (sprint_id, occurred_at);
CREATE INDEX idx_sprint_scope_events_ws     ON sprint_scope_events (workspace_id);
```

Entity extends `CreatedOnlyEntity` (append-only; `@CreatedDate`, no `updated_at`, no `@Version`). `workspace_id` is denormalised for the same reason `Issue`, `Sprint`, `Component` and `Version` carry it — a tenant-scoped query never joins through `projects`.

**Why a ledger and not a JSONB snapshot on `sprints`:** the burn-up needs *when* scope moved, not only the endpoints; the sprint review needs the add/remove lists; velocity needs "committed" for each of the last N sprints. One append-only table answers all three, is id-keyed (immune to sprint and status renames, unlike anything derived from `issue_history`), is trivially indexable, and follows the project's stated preference for real columns over JSONB outside custom-field values.

**Commitment is an event, not a snapshot.** `SprintService.start` writes one `ADDED` row per current member issue with `occurred_at = startAt`, in one `saveAll` batch, in the same transaction as the conditional `markActive` UPDATE (after its affected-row arbitration succeeds, so a losing double-click writes nothing). "Scope at time T" is then `count(ADDED ≤ T) − count(REMOVED ≤ T)`, and "committed" is scope at `startAt`. One mechanism, no second concept.

**This draft said `ON DELETE CASCADE` on `issue_id`, and V18 shipped the opposite.** Corrected here because anyone reading the old text would build an inner join and reintroduce the bug it causes.

Cascading loses the arrival **and** the departure *as a pair*, which is worse than losing either: a completed sprint's review would quietly shed issues, and its scope arithmetic would still balance, so nothing would look wrong. What shipped is a **composite** FK `(issue_id, workspace_id) → issues (id, workspace_id) ON DELETE SET NULL (issue_id)` — the column list matters, since a bare `SET NULL` would try to null the `NOT NULL` `workspace_id` and the delete would fail — plus a snapshotted `issue_key` and `story_points` on the row itself, so a departed issue still means something.

Two consequences the read side must honour, both load-bearing:

1. **Never inner-join `issues`.** A completed sprint whose issues were later deleted would silently lose rows from its own record — exactly what the nulling FK exists to prevent.
2. **Group by `issue_id`, or by `issue_key` when it is null.** Otherwise every departed issue in a sprint collapses into one phantom whose adds and removes interleave.

**The enumerated-doors problem — the highest-risk part of the feature.** Sprint membership changes today in at least five places, all of which already write an `IssueHistory` row with `field = 'sprint'`:

| Door | Code |
|---|---|
| `PATCH /issues/{n}` carrying `sprintId` | `IssueService.update` (≈ line 652) |
| `POST /sprints/{id}/issues` | `SprintService` (≈ line 605) |
| `DELETE /sprints/{id}/issues/{issueId}` | `SprintService` (≈ line 653) |
| Sprint completion carry-over (bulk) | `SprintService.writeSprintHistory` |
| Force-delete detach (bulk) | `SprintService.writeSprintHistory` |

If one misses the ledger, the burn-up's scope line is **silently wrong** — exactly the failure that makes competitors' burndowns distrusted (§1.2). Mitigations, mirroring the `ProjectAdminGuard` "nine enumerated doors" pattern from HD-136:

1. **One writer.** A `SprintScopeLedger` component with `recordAdded`/`recordRemoved`, called from the same statements that build the `sprint` history rows — never from a controller.
2. **A test that enumerates the doors** and asserts every path producing a `sprint` `IssueHistory` row also produces a ledger event, by count and by issue id. An unlisted door is the bug.
3. **Bulk paths use the same proxy trick** already documented in `writeSprintHistory` — build rows against `getReferenceById` proxies after the bulk UPDATE, never re-materialise the issues (the documented "bulk JPQL UPDATE desyncs already-loaded entities" trap).

**Retention:** none in v1. A few rows per issue per sprint; a 100k-issue workspace running sprints for three years is low single-digit millions of narrow rows, which the `(sprint_id, occurred_at)` index serves fine. Revisit only with data.

### 5.3 Configuration

```properties
app.reports.max-window-days=${REPORTS_MAX_WINDOW_DAYS:365}
app.reports.max-rows=${REPORTS_MAX_ROWS:20000}
```

`@ConfigurationProperties` in `common.config`, `@Min`-validated, **identical in `dc` and `cloud` with no profile override** — reporting depth is a product property, not a plan property; if it were ever limited it would be by lowering a number, never by a second code path. Both go through the full wiring checklist below. Note it names **four** files and no README table — there is no config table in the README, and the two most easily missed are the declaration site itself and the operator reference.

---

## 6. Behaviour, edge cases & failure modes

| Case | Behaviour |
|---|---|
| Window exceeds `max-window-days` | **400**, detail names the cap. Never a silent clamp. |
| `from > to` | 400. |
| Row cap bites | 200 with `meta.truncated = true`; the UI prints *"showing the most recent 20 000 issues"* above the chart. |
| Project has 0 issues | 200, empty series, a specific empty-state sentence per report (§2). Never a blank panel. |
| Fewer than 5 completed issues (cycle time) | Percentiles suppressed, sample size stated. **Cycle and lead are gated independently** — they are computed over different sets (`sampleSize - missingStartCount` vs `sampleSize`), which on an upgraded install differ widely, so one may be suppressed while the other is not. |
| Fewer than 3 completed sprints (velocity) | Forecast band suppressed, sample size stated. |
| Sprint from another project | 404 (`findByIdAndProject`). |
| Sprint deleted while a report URL is open | 404 on refetch; the UI offers the sprint picker. |
| Issue deleted | Its ledger rows **survive**, with `issue_id` nulled and `issue_key`/`story_points` snapshotted, so a past sprint's record does not change. It appears as a real, unlinked row marked deleted (§5.2). A deleted issue out of a **completed** sprint cannot be proven completed — the completion lives on the issue, and removing it from a frozen sprint writes no ledger row — so it lands in *carried over* with a null completion rather than being dropped (which would shrink what the sprint committed to) or claimed (which a report may not do for something it cannot show). **Its `actorId` is nulled in the response** (the DB row keeps it): `issue_history.issue_id` cascades on delete, so preserving attribution here would leave the scope log as the only surviving place in the product naming who touched that issue and exactly when — wider survival than the ledger was designed for. The step, its instant, its direction, its key and its estimate are the record; the person is not. |
| Issue reopened | It leaves its old "resolved" bucket and joins a new one. Footnoted (§2.1). |
| Archived project | Reports readable; archived banner shown. |
| Archived / removed status | Aging WIP renders columns from the project's *effective* workflow via `ProjectConfigService`; an issue stranded in a status outside the workflow appears in a trailing **"Not on this board"** column rather than vanishing. |
| Unestimated issues in a points measure | Counted as 0 in the series **and** reported as `unestimatedCount`, exactly as `SectionStats` already does. Never silently treated as 0 alone — that is documented failure mode #4 (§1.2). |
| `estimation` off but points exist | Values still render (Rule B); the measure toggle is hidden, not the data. |
| Two tabs, concurrent edits | Reports are reads; no optimistic locking involved. `meta.computedAt` is the reader's anchor. |
| Idempotency / races | Read-only endpoints. The one **write** this feature adds is the ledger insert inside `SprintService.start`, inheriting that method's conditional-UPDATE arbitration — a losing double-click writes no ledger rows. |
| Timezone | All bucketing on **UTC day boundaries**, stated in the UI footer. Per-workspace reporting timezone is §9 OQ 6. |

---

## 7. Frontend impact

**New route** `/w/:wsId/p/:projectId/reports/*` — `pages/reports/ReportsArea.tsx`, lazy-loaded (it carries the chart chunk). A left list of reports; a shared header with the window picker / sprint picker / measure toggle; one page per report: `FlowReportPage`, `CycleTimeReportPage` (both halves), `SprintBurnupPage`, `SprintReviewPage`, `VelocityPage`. `ParamKeyed`-wrapped like the other project pages so state never leaks across projects. Report state lives in query params (§4.4).

**`NavRail.tsx`** — replace the "Reports — SOON" stub (currently ~lines 245–254) with a real `RailLink`. Per Rule C the item is **always visible**; sprint-dependent reports inside it are listed-but-disabled with their enabling affordance, so a Kanban project can still discover that sprint reports exist and what turns them on.

**`SearchResultsPage`** — the Insights panel (§2.6), collapsible, alongside `SavedFiltersPanel`.

**Stores/hooks** — a `reports` API group in the existing api module; TanStack Query keys `['reports', kind, projectId, params]` with `staleTime` 60s to match `Cache-Control`. Capability gating goes through the existing project `delivery` object; permission gating goes through `hooks/usePermissions.ts` **only** for the settings links in the Rule C affordances (`project.edit`) — never for the reports themselves.

**Charts.** The SPA has no chart library today. **Recommendation: Recharts (MIT), imported only inside the lazy `/reports` chunk and the Insights panel chunk**, so the main bundle is unchanged (precedent: Swagger UI's 1.4MB lazy chunk). Hand-rolled SVG is viable for bar and line but not for the scatter-with-tooltips-and-reference-lines, which is the report carrying the most value. §9 OQ 1.

**`DESIGN.md` compliance.**
- Read `DESIGN.md` first. Use the tokens: `--color-brand` for the primary series, `--color-ink` for the rail; **no hardcoded hex**.
- **`DESIGN.md` has no data-visualisation palette today.** One must be added before charts are built — a categorical ramp, colour-blind-safe, not colliding with the existing semantic colours (status categories, priority colours). A prerequisite deliverable, not an afterthought; part of the first UI slice.
- The `max-w-*` trap: never use Tailwind `max-w-2xs…max-w-3xl` (our `@theme --spacing-*` scale shadows them); inline `maxWidth`.
- Absolute paths in `<Link>`/`<NavLink>` inside the `/reports/*` splat route.
- Every chart needs a table equivalent underneath (accessibility — and it is also the CSV).

---

## 8. DC / Cloud

**Nothing about this feature differs between deployment models, by construction.**

- No scheduler, no background job, no queue, no second read model, no external warehouse — a direct consequence of refusing the CFD and scheduled delivery (§3).
- No object storage, no email, no third-party service.
- The two new properties (§5.3) are identical in `dc` and `cloud` with no profile override, and go through the full wiring checklist: `application.properties` (**the declaration site — its comment sits one line above the value and is the one that goes stale**) → `docker-compose.prod.yml` (via `env_file`, so nothing to add) → `.env.prod.example` → `docs/self-hosting.md` (**the operator reference; README has no config table and delegates here**) → `docs/api-dc.md` operator settings.
- PNG export is client-side, so there is no headless-browser dependency on the server (which *would* have been a DC problem — and is one of the reasons scheduled PDF is refused).
- If scheduled delivery is ever built, its DC answer is the existing single-node `@Scheduled` infrastructure (the same one the rate limiter uses) plus `MailService`, with the single-node caveat documented exactly as rate limiting documents it. Recorded here so the follow-up epic does not rediscover it.

---

## 9. Open questions for the owner

1. **Chart library.** Recommend **Recharts, lazy-loaded** (MIT, ~100KB gz in a chunk only the reports route pulls). Alternative: hand-rolled SVG, zero dependencies, but the scatter/percentile chart is real work. *Recommended default: Recharts.*
2. **`started_at` backfill.** Recommend the **best-effort name-join backfill** (§5.1) with `missingStartCount` disclosed — it makes cycle time useful on day one for most existing issues. Alternative: backfill nothing and let the column fill forward over weeks (perfectly accurate, useless for two months). *Recommended default: best-effort + disclosure.*
3. **Insights panel — same slice or later?** It is the intellectual replacement for the refused dashboard but lives on a different page and touches the search subsystem. *Recommended default: ship it late (R6), after the fixed reports prove the chart stack.*
4. **Workspace-level rollup** ("all projects, created vs resolved"). *Recommended default: defer.* The Insights panel covers the ad-hoc case; a published rollup is the case that would force precomputation and should be its own decision with data behind it.
5. **Reopen churn.** `closed_at` being cleared on reopen makes past "resolved" buckets mutable. *Recommended default: disclose in v1*, measure how often it happens (countable from `issue_history`), then decide whether a resolution-event ledger is worth it.
6. **Reporting timezone.** *Recommended default: UTC, stated in the footer.* A per-workspace reporting timezone is a real request in distributed teams but it is a column plus a correctness question on every bucket boundary.
7. **Should the sprint review record be immutable after completion?** Today it is derived from the ledger, so editing an old issue's points changes an old report's point sums. *Recommended default: leave it derived* (counts are exact and immutable; only point sums drift), footnoted *"points reflect current estimates"* consistently with §2.3.

---

## 10. Contradictions with the epic's current description

1. **"Cumulative flow"** — refused (§1.3, §3). This removes half of **HD-28**.
2. **"Burndown"** — refused and replaced by burn-up (§1.2, §2.3). This changes half of **HD-29**.
3. **"Configurable dashboard of gadgets"** — refused for v1, replaced by fixed reports plus the search Insights panel (§1.5, §2.6).
4. **"Export as image/CSV"** — half right. The CSV people ask for is the **series**, not a flat issue list; the image nobody asks for (they screenshot); what *is* demanded and is **not** in the epic is **scheduled delivery**, which we refuse with reasons rather than omit (§1.6, §3).
5. **Two reports the epic does not mention are the two with the best evidence behind them:** the **sprint review record** (§2.4 — the artefact teams actually open) and **aging WIP** (§2.2 — the actionable half of everything a CFD promises).

---

## 11. Proposed slicing

HD-28 and HD-29 are **not** the right first slices as written: HD-28's first half is refused, HD-29's first half is replaced, and both depend on a ledger and a chart stack that do not exist. Recommendation: keep the ticket numbers, rewrite their scope, insert a foundation slice.

| Slice | Ticket | Scope | Depends on |
|---|---|---|---|
| **R1** | **HD-28 (re-scoped)** | **Flow report (created vs resolved), end to end.** Two indexes, one endpoint, one page, the chart stack, the `DESIGN.md` data palette, the `meta`/empty-state/caps conventions. **Ship first** — needs no new data, establishes every convention the rest reuse. | — |
| **R2** | new | **Foundations: `issues.started_at` + backfill + `sprint_scope_events` ledger + `SprintScopeLedger` single writer + the enumerated-doors test.** Backend only, lands dark. | — |
| **R3** | new | **Cycle & lead time + aging WIP.** Highest-value report in the set. | R1, R2 |
| **R4** | **HD-29 (re-scoped, first half)** | **Sprint burn-up with scope line + scope-change log**, and the **sprint review record** (shared ledger query and sprint picker). | R2, R1 |
| **R5** | **HD-29 (second half)** or new | **Velocity as a forecast band**, with the no-per-person and no-cross-team rules baked in. | R4 |
| **R6** | new | **Insights panel on search results** — the dashboard replacement. | R1 |
| **R7** | new | **Export**: CSV of the series for every report, PNG with fixed axes and embedded provenance, shareable URL state audit. | R1, R3, R4, R5 |
| **R8** | new | **Docs**: `openapi.yaml`, `docs/api-cloud.md`, `docs/api-dc.md` (incl. the two operator settings), `docs/project-state.md` section. | all |

Why R1 before R2 even though R2 is "foundations": R1 has zero data dependencies and delivers a usable report in one slice, de-risking the chart stack and the conventions before anything schema-shaped lands. R2 is a migration + a ledger + a parity test and wants its own review gates (`migration-reviewer`, `tenancy-reviewer`) without a UI in the diff.

---

## 12. Acceptance criteria

**Tenancy (every slice)**
- [ ] Every report endpoint resolves through `WorkspaceAccessService.resolveProject`; a non-member and an unknown workspace/project both return **404**, never 403.
- [ ] A `sprintId` belonging to another project returns **404**.
- [ ] The Insights endpoint runs through `SearchScope`; a query naming a project the caller cannot see returns no rows from it (never "all").
- [ ] No report query reaches `issues`, `sprints` or the ledger without a `workspace_id` or `project_id` predicate.

**Capabilities (Rules A/B/C)**
- [ ] With `board = KANBAN`, `GET /sprint-burnup?sprintId=…` for an existing sprint returns **200** with the same body as a SCRUM project (no status code depends on a capability).
- [ ] With `estimation = false`, `measure=POINTS` returns **200** with a points series.
- [ ] The UI hides the points toggle when estimation is off and shows the Rule C affordance linking to project settings; the reports list shows sprint reports disabled-with-reason in a Kanban project.
- [ ] No code path decides what to show by counting existing sprints, versions or points.

**Correctness & honesty**
- [ ] A window longer than `app.reports.max-window-days` returns **400** naming the cap; nothing is silently clamped.
- [ ] When the row cap bites, `meta.truncated = true` and the UI says so.
- [ ] Cycle time is computed only from issues having both `started_at` and `closed_at`; `missingStartCount` is returned and displayed; `created_at` is never substituted.
- [ ] Percentiles suppressed below 5 samples; the velocity band below 3 sprints.
- [ ] Adding an issue to a running sprint produces exactly one `ADDED` ledger row and a corresponding step in the burn-up's scope line within the same request.
- [ ] Changing an issue's story points mid-sprint produces **no** scope-change event.
- [ ] Starting a sprint with N issues writes exactly N `ADDED` rows dated `startAt`; a concurrent double-start writes N total, not 2N.
- [ ] Every path that writes a `sprint` `IssueHistory` row also writes a ledger event (enumerated-doors test, by count and by issue id).
- [ ] Removing then re-adding an issue produces `REMOVED` then `ADDED`, and a scope line that returns to its previous level.
- [ ] No endpoint, response field or CSV column in `/velocity`, `/sprint-review`, `/sprint-burnup` or `/flow` breaks results down by assignee.

**Performance**
- [ ] Each report is a bounded, fixed number of queries (query-count test in the style of `PermissionResolutionQueryCountTest`) — no N+1 over issues, sprints or the ledger.
- [ ] `EXPLAIN` shows index usage, per slice and named — an index no shipped query uses is not a passing checkbox, it is a write cost on the hottest table in the product:
  - `(project_id, created_at)` and `(project_id, closed_at)` — R1 (flow), R3 (cycle time). **Confirmed.**
  - `(sprint_id, occurred_at)` — R4/R5, once the ledger has a reader.
  - `(project_id, started_at)` — **used by nothing so far.** R3 was expected to exercise it and does not: neither half filters or sorts on `started_at` alone (cycle time ranges on `closed_at`, aging orders by `COALESCE(started_at, created_at)`). If R4 and R5 do not use it either, drop it rather than carry it.
- [ ] Index-only scans are expected of the **aggregate** halves only. A report that returns per-issue rows carries a heap fetch per shipped row by construction — the range scan is index-only, the payload is not. Do not write an acceptance criterion a row-returning report cannot meet.
- [ ] On a seeded 100k-issue project, every report responds in under 1 s warm.

**Migration**
- [ ] No `CHAR(n)`, no PG `ENUM`; `event` is `VARCHAR(10)` + Java enum + CHECK.
- [ ] Ledger ids are UUID v7 via `@UuidGenerator(style = TIME)`; `@CreatedDate` for `occurred_at`; entity extends `CreatedOnlyEntity`.
- [ ] Entity ⇄ schema parity passes Hibernate `validate` on a fresh DB.
- [ ] The `started_at` backfill is idempotent (`WHERE started_at IS NULL`).

**Docs & config**
- [ ] `openapi.yaml` validates (`swagger-cli`); both `docs/api-*.md` updated.
- [ ] `REPORTS_MAX_WINDOW_DAYS` / `REPORTS_MAX_ROWS` documented — and **agreeing on the valid range** — in all four places: `application.properties` (the comment above the declaration), `.env.prod.example`, `docs/self-hosting.md` and `docs/api-dc.md` operator settings; identical defaults in `dc` and `cloud`. A bound changed in the validator but not in these is a documented value that aborts the boot — R3 shipped exactly that miss, in two of the four, because this list previously named a README table that does not exist and omitted the other two.
- [ ] `DESIGN.md` gains a data-visualisation palette section before any chart ships.
