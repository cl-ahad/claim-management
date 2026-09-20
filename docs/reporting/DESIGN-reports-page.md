# Reports page — visual direction & tokens (S-4 · REP-3-design)

**Ticket:** S-4 · **Branch:** `feature/s-4-rep-3-design` · **Owner:** Sam (Product Designer)
**Depends on:** the reporting story (S-2) and the technical approach (S-3, `docs/reporting/DESIGN.md`).
**Feeds:** the frontend build ticket **S-7 (REP-6-reports-page)**.

> **Direction in one line:** the Reports page is *not* a new surface — it is the existing
> Claim Management admin system (calm, dense, one blue accent, light neutral) extended by
> exactly the components a report needs. The engineer assembles it from the current design
> system; this note only adds a **toggle**, a **chart frame**, a **skeleton** and a **busy
> button** — every one built from tokens that already exist.

---

## 1. The system we design against (brownfield — do NOT introduce a new one)

The frontend is `frontend/` — Vite 7 + React 19 + TypeScript, **one hand-authored stylesheet**
at `frontend/src/styles.css`. It is already a proper, tokenised system. We extend it; we import
nothing.

**Reference followed:** the product's own "Claim Management admin" language — the exact
direction already committed in `frontend/src/styles.css` and used by `DashboardPage`,
`ClaimsListPage` and `Layout`. No external reference (Figma is not connected on this run).

| Token group | Values already defined (`:root` in `styles.css`) |
|---|---|
| **Typeface** | `--font` = `ui-sans-serif, -apple-system, "Segoe UI", Roboto, …` (system-ui stack — **not** the browser serif). `--mono` for claim numbers. |
| **Type scale** | h1 20 / h2 16 / h3 14 · body 14 · captions 11–12 · stat value 24. Weight 650 for headings, 550 for labels/buttons. |
| **Spacing** | 4px rhythm — grids gap 16, card padding 16, cell padding 9–14, `.mt-16`. |
| **Surfaces** | `--bg` #f6f7f9 · `--surface` #fff · `--surface-2` #f1f3f6 · borders `--border`/`--border-strong`. |
| **Accent (one)** | `--accent` #1f5fd6 + `--accent-hover` + `--accent-soft`. Used for primary action & focus only. |
| **Semantic** | `--ok` / `--warn` / `--danger` / `--neutral` / `--info`, each with a `-soft` pair. |
| **Radii (two)** | `--radius` 8 (cards) · `--radius-s` 5 (controls). |
| **Elevation** | `--shadow` (cards) · `--shadow-lg` (modals). |
| **Focus ring** | 3px `--accent-soft` ring on inputs; we mirror it on the new segmented control. |

**Do NOT** add a component library, a chart library, a colour, a pixel value or a radius
outside these tokens. A literal value in a Reports component is a defect.

### Tokens: what exists vs. what S-4 adds
- **Exists, reuse as-is:** `card`, `card stat` / `stat-label` / `stat-value` / `stat-note`,
  `grid grid-4` / `grid-2`, `Card` (`card-head` / `card-body tight`), `table` + `.num`
  (tabular numerics, sticky-styled header, dividers), `.badge-*`, `.btn` / `.btn-secondary`
  / `.btn-sm` (hover/disabled states), `.filters` / `.field`, `.empty` / `.empty-title`
  (empty state), `.loading`, `.alert-error` / `.alert-warn`, `.pagination`.
- **Added by S-4** (`docs/reporting/reports-tokens.css`, all token-only, merged into
  `styles.css` by S-7):
  1. `.segmented` — the quarterly/annual (and Q1–Q4) **toggle**. Nothing like it exists today.
  2. `.chart-frame` + `.chart-legend` — wrapper for the on-screen **inline-SVG graph** (the
     approach in S-3 is *no chart library on the frontend*; one 3-bar chart is hand-drawn SVG).
  3. `.skeleton` (+ `-line` / `-value` / `-chart`) — a **loading** state in the final layout.
     Today only a text `.loading` "Loading…" exists; a report needs skeletons.
  4. `.spinner` + `.btn.is-busy` — the **export-in-progress** button state.
  5. `[data-theme="dark"]` — a *designed* dark token set (see §6).

---

## 2. Page layout

Follows the `DashboardPage` shell exactly: `Layout` sidebar → `PageHeader` topbar → `.content`.
A new **"Reporting" → "Reports"** nav item is added to `Layout.tsx` (route `/reports`).

```
┌ topbar ──────────────────────────────────────────────────────────────┐
│ Reports                                          [Export PDF][Export Excel]
│ Quarterly and annual claims performance          ← the two export buttons
├ .content ─────────────────────────────────────────────────────────────┤
│ ┌ card · period controls (.filters) ─────────────────────────────────┐ │
│ │ View [Quarterly|Annual]   Year ▾   Quarter [Q1|Q2|Q3|Q4]   Jul–Sep │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ ┌ grid grid-4 · metric summary ──────────────────────────────────────┐ │
│ │ Claims filed │ Total claim amount │ Avg processing time │ Approval% │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│ ┌ grid grid-2 ───────────────────────────────────────────────────────┐ │
│ │ Status breakdown (table: approved/pending/rejected) │ Claims by     │ │
│ │  approved · pending · rejected + counts & share      │ outcome (SVG  │ │
│ │                                                       │ bar graph)   │ │
│ └────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### The five metrics (from the story), placed
- **Claims filed** → stat card 1 (count). **Total claim amount** → stat card 2 (`Money`).
- **Avg processing time** → stat card 3 (days, 1dp, with the decided-claim denominator as the
  `stat-note` — the average excludes open claims per S-3).
- **Status breakdown** (approved / rejected / pending) → the left card in the `grid-2`: a
  table with count + share, statuses shown with existing `.badge-*` colours
  (approved = ok/green, pending = warn/amber, rejected = danger/red). A fourth stat card
  (**Approval rate**) turns the breakdown into the one headline number.

### The graph (required)
One **horizontal bar chart** — approved / pending / rejected counts — in the right `grid-2`
card, rendered as **inline SVG** in `.chart-frame` using `var(--ok)` / `var(--warn)` /
`var(--danger)` fills and a `.chart-legend`. Horizontal bars keep long labels legible and
collapse cleanly to 390px. The same figures are the server-side chart baked into the PDF/Excel
(S-6), so on-screen and in-export never disagree.

### The toggle
`.segmented` control, `aria-pressed` per option, in the period-controls card:
- **View:** `Quarterly | Annual`. Selecting **Annual** hides the Quarter segment.
- **Quarter:** `Q1 | Q2 | Q3 | Q4` (quarterly view only). Year is a `<select>`.
- State lives in `useSearchParams` (`?period=quarterly&year=2026&quarter=3`) — matches how
  `ClaimsListPage` holds filters, so a report URL is shareable.

### The two export buttons
Top-right of the `PageHeader`, in the existing `.btn-row`:
- **Export PDF** → `.btn.btn-secondary` (secondary weight). **Export Excel** → `.btn` (accent).
  Excel is the primary because analysts pull data more than they print. Both carry a lucide
  `download` icon at 15px. They hit the S-6 backend endpoints and download bytes.

**Hierarchy:** on the ready page the one primary (accent) action is **Export Excel**; every
other control is secondary or neutral. Stat values (24/650) dominate; labels are muted 11px
uppercase; the accent appears only on the primary button, the active toggle segment and focus.

---

## 3. Interface copy

| Element | Copy |
|---|---|
| Page title / subtitle | **Reports** · "Quarterly and annual claims performance" |
| Stat labels | "Claims filed", "Total claim amount", "Avg processing time", "Approval rate" |
| Stat notes | "in this period", "gross charged", "N decided claims", "approved of filed" |
| Toggle labels | "Quarterly" / "Annual" · "Q1"–"Q4" |
| Period caption | "Jul 1 – Sep 30, 2026 · anchored on filing date" (states the S-3 `created_at` anchor) |
| Export buttons | "Export PDF", "Export Excel"; busy → "Preparing PDF…" / "Preparing Excel…" |
| Empty title / hint | "No claims were filed in {period}" · "Pick another period, or file a claim to start populating this report." |
| Error | "We couldn't load this report. Check your connection and try again." + **Retry** |

---

## 4. Every state is designed (per S-3, the page fetches `/api/reports/...`)

| State | What the user sees |
|---|---|
| **Ready** | The layout above with real figures. Primary action = Export Excel. |
| **Loading** | `.skeleton` blocks in the **final layout** — four stat-card skeletons, a table-line skeleton, a `.skeleton-chart` where the graph will be. Never a spinner in a void. Period controls stay live so the user can change selection while data loads. |
| **Empty** (period has 0 claims) | `.empty` block: "No claims were filed in {period}" + a next action (change period / file a claim). Export buttons **disabled** — nothing to export. |
| **Export in progress** | The triggered button gets `.is-busy` + `.spinner`, label → "Preparing Excel…", and is non-interactive until the download starts. The rest of the page stays fully usable. |
| **Error** | Inline `.alert-error` above the content + an `.empty` "Report unavailable" with a **Retry** button. Specific and recoverable. |

---

## 5. Responsive — 1440 and 390

- **1440 (desktop):** content in the app's `.content` padding (22/24px) inside the sidebar
  shell; `grid-4` = 4 stat cards across, `grid-2` = breakdown table beside the graph. Export
  buttons inline in the topbar.
- **390 (phone):** the system's existing breakpoints do the work — `@media (max-width:1100px)`
  drops `grid-4` to 2-up, `@media (max-width:820px)` stacks every grid to a single column, so
  stat cards stack, then the breakdown table, then the graph (SVG is `width:100%`, scales down).
  The `.segmented` toggle stays a pill row; on Annual it is just two segments. Export buttons
  wrap under the title via the existing `.btn-row` `flex-wrap`. No new phone-specific CSS needed
  beyond the graph being fluid — the direction deliberately rides the system's own responsive
  rules rather than inventing per-page breakpoints.

---

## 6. Themes (light today; dark specified, not shipped)

The application ships **light-only** — there is no dark theme in `styles.css` and no theme
toggle anywhere in the app. The Reports page is designed and reviewed **in the shipping light
theme**.

The commander's brief asks for "both themes", so S-4 delivers a **designed** dark token set
(`[data-theme="dark"]` in `reports-tokens.css`) — surfaces get lighter for depth rather than
relying on shadows, the accent lightens to `#5b9bff` for contrast on dark, and every body pair
is re-checked for ≥4.5:1. Because the whole system reads tokens, flipping `data-theme="dark"`
on `<html>` themes the entire app, Reports included, **without editing one component**.

> **Decision for the commander:** adopting dark mode is a **system-wide** change (it themes
> every page, not just Reports) and needs a theme toggle + persistence. That is out of scope
> for the Reports feature and should be its own ticket. The dark tokens here are the ready
> specification so that ticket is a small, safe change when the team wants it. **Reports itself
> ships in the existing light theme.**

---

## 7. Design review checklist — status

Reviewed against the studio checklist. States/widths/copy are specified above; the two items
I could not tick from a rendered screenshot are called out honestly.

| Item | Status |
|---|---|
| One typeface, defined scale, 4px spacing | ✅ inherited from `styles.css` |
| One primary action, most visible (Export Excel) | ✅ specified |
| Headings/body/captions differ in size **and** weight **and** colour | ✅ inherited |
| Grouping by proximity; gaps from the scale | ✅ grid gap 16, card 16 |
| No browser-default controls; designed focus ring | ✅ segmented + inputs use the token ring |
| Colour discipline — neutral surfaces, one accent, semantic for status | ✅ ≤5 hues on screen |
| Every state designed (loading skeleton / empty / error / busy / success-download) | ✅ specified in §4 |
| Motion quiet, ≤250ms ease-out, honours reduced-motion | ✅ 180ms toggle, reduced-motion guards |
| Dark theme designed not inverted | ✅ tokens provided; ships light-only (§6) |
| **Rendered at 1440 & 390, light & dark** | ⚠️ **NOT verified in-browser this run** — sandbox was fork/resource-starved (`spawn EAGAIN`), so `sandbox_browse` could not launch. The mockup (`docs/reporting/reports-design-mock.html`) loads the verbatim `styles.css` + these tokens and is self-contained; QA/S-7 should open it and capture the five states × 2 widths × 2 themes and attach them. Layout, spacing and states are specified precisely enough to build; the screenshots are the confirmation step. |

---

## 8. Handoff to S-7 (build)

1. Merge the blocks in `reports-tokens.css` into `frontend/src/styles.css` (they are additive).
2. Build `ReportsPage.tsx` from `DashboardPage.tsx` — same `PageHeader`, `Card`, `Money`,
   `StatusBadge`, `Loading`/`EmptyState`/`ErrorAlert`, `grid grid-4`/`grid-2`, `.stat` cards.
3. Segmented toggle + year select drive `useSearchParams`; fetch `/api/reports/...` (S-5).
4. Inline-SVG bar chart in `.chart-frame`; export buttons call S-6 endpoints and set `.is-busy`
   while the download is prepared.
5. Register the `/reports` route and the "Reporting → Reports" nav item in `Layout.tsx`.
6. Verify at 1440 & 390 (light) and attach screenshots for review.
