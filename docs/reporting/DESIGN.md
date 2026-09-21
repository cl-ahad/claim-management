# Reporting section — design direction & tokens (S-3 · REP-3)

**Ticket:** S-3 · **Branch:** `feature/s-3-rep-3-design-direction` (off `Demo3`) · **Owner:** Sam (Product Designer)
**Tokens file:** [`docs/reporting/reports-tokens.css`](./reports-tokens.css) — the additive CSS the frontend build ticket merges into `frontend/src/styles.css`.

> **Direction in one line:** the reporting section is **not** a new surface — it is the
> existing *Claim Management admin* system (calm, dense, one blue accent, light neutral)
> extended by exactly the components a report needs. The engineer assembles it from the
> current design system; this direction adds only a **report-type toggle**, a **chart
> frame**, a **loading skeleton** and an **export-busy button** — every one built from
> tokens that already exist in `styles.css`.

---

## 1. The system we design against (brownfield — do NOT introduce a new one)

The frontend is `frontend/` — Vite + React + TypeScript, with **one hand-authored
stylesheet** at `frontend/src/styles.css`. It is already a proper, tokenised system.
We extend it through tokens; we import nothing — no component library, no chart library.

**Reference followed:** the product's own *Claim Management admin* language — the exact
direction already committed in `frontend/src/styles.css` and used by `DashboardPage`,
`ClaimsListPage` and `Layout`. No external reference (Figma is not connected on this run).

| Token group | Values already defined (`:root` in `styles.css`) — reuse verbatim |
|---|---|
| **Typeface** | `--font` = `ui-sans-serif, -apple-system, "Segoe UI", Roboto, …` (system-ui stack — **not** the browser serif). `--mono` for claim numbers / amounts. |
| **Type scale** | h1 20 / h2 16 / h3 14 · body 14 · captions 11–12 · stat value 24. Weight 650 headings, 550 labels/buttons. |
| **Spacing** | 4px rhythm — grid gap 16, card padding 16, cell padding 9–14, `.mt-16` / `.mt-8`. |
| **Surfaces** | `--bg` #f6f7f9 · `--surface` #fff · `--surface-2` #f1f3f6 · borders `--border` / `--border-strong`. |
| **Accent (one)** | `--accent` #1f5fd6 + `--accent-hover` + `--accent-soft`. Primary action & focus only. |
| **Semantic** | `--ok` / `--warn` / `--danger` / `--neutral` / `--info`, each with a `-soft` pair. |
| **Radii (two)** | `--radius` 8 (cards) · `--radius-s` 5 (controls). |
| **Elevation** | `--shadow` (cards) · `--shadow-lg` (modals). |
| **Focus ring** | 3px `--accent-soft` ring; mirrored on the new segmented control. |

**Do NOT** add a component library, a chart library, a colour, a pixel value or a radius
outside these tokens. A literal value in a reporting component is a defect.

---

## 2. Page layout

Follows the `DashboardPage` shell exactly: `Layout` sidebar → `PageHeader` topbar →
`.content`. A new **"Reporting" → "Reports"** nav item is added to `Layout` (route
`/reports`).

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

### The metrics (from the story), placed
- **Claims filed** → stat card 1 (count).
- **Total claim amount** → stat card 2 (`Money`).
- **Avg processing time** → stat card 3 (days, 1dp; decided-claim denominator in the
  `stat-note` — the average excludes open claims per the approach).
- **Approval rate** → stat card 4 (the one headline number derived from the breakdown).
- **Status breakdown** (approved / pending / rejected) → left card of `grid-2`: a table
  with count + share, statuses shown with existing `.badge-*` colours
  (approved = ok/green, pending = warn/amber, rejected = danger/red).

### The graph (required)
One **horizontal bar chart** — approved / pending / rejected counts — in the right
`grid-2` card, rendered as **inline SVG** inside `.chart-frame` with a `.chart-legend`
below. This matches the approach's *no frontend chart library* decision and stays legible
at 390px. Bars read `var(--ok)` / `var(--warn)` / `var(--danger)`, the same figures baked
into the server-rendered chart in the exports, so on-screen and in-export never disagree.

---

## 3. Component inventory

Everything the reporting section renders, its states, and where it comes from. The build
ticket assembles the page from **this inventory** rather than inventing per-page controls.

### 3a. Reused as-is (already in `styles.css` — no change)
| Component | Class(es) | Notes |
|---|---|---|
| Page shell | `Layout` / `PageHeader` / `.content` / `.topbar` | Same as `DashboardPage`. |
| Metric stat card | `.card.stat` + `.stat-label` / `.stat-value` / `.stat-note` | The four summary metrics. |
| Grids | `.grid.grid-4`, `.grid.grid-2`, `.mt-16` | Responsive at 1100/820 breakpoints (grid-4→2→1). |
| Card wrapper | `Card` (`.card` / `.card-head` / `.card-body.tight`) | Breakdown table + chart cards. |
| Table + numerics | `table`, `.num` (tabular-nums), sticky-styled `th`, dividers | Status breakdown. |
| Status badges | `.badge-*` (`StatusBadge`) | approved/pending/rejected colours. |
| Money / dates | `Money`, `DateText` (from `components/Common`) | Amounts and period label. |
| Filter row | `.filters` / `.field` | Holds the period controls. |
| Year picker | `select` (existing styled input + focus ring) | Year dropdown. |
| Buttons | `.btn` (primary) / `.btn-secondary` / `.btn-sm` / `.btn-row` | Export buttons, all interaction states already defined. |
| Empty state | `.empty` / `.empty-title` | Whole-page empty (no claims in period). |
| Error | `.alert-error` + `.btn-secondary` Retry | Load failure. |

### 3b. Added by this ticket (`reports-tokens.css` — token-only)
| Component | Class(es) | Purpose | States |
|---|---|---|---|
| **Report-type toggle** | `.segmented` + `button[aria-pressed]` | Quarterly \| Annual, and the Q1–Q4 sub-toggle. On **Annual** the Quarter field is hidden. | default / hover / **active** (`aria-pressed="true"`) / focus-visible / disabled |
| **Chart frame** | `.chart-frame`, `.chart-frame svg` | Wrapper for the inline-SVG bar chart; SVG is `width:100%` so it scales to 390px. | — |
| **Chart legend** | `.chart-legend`, `.chart-legend .swatch` | approved/pending/rejected key under the chart. | — |
| **Chart empty** | `.chart-empty` | Chart has no data for the period; keeps card height, invites picking another period. | empty |
| **Loading skeleton** | `.skeleton` (+ `-line` / `-value` / `-chart`) | Loading state drawn **in the final layout** — a skeleton per stat, table row and chart. | loading |
| **Export-busy button** | `.spinner`, `.btn.is-busy` | Inline spinner + lock while an export is being prepared. | busy |

All 3b additions read only existing tokens, honour `prefers-reduced-motion`, and carry a
designed `[data-theme="dark"]` value set (see §6).

---

## 4. Every state is designed

The reporting section is a data screen; each state below is specified and reachable.

| State | What renders | Copy |
|---|---|---|
| **Loading** | `.skeleton` blocks in the exact final layout — four stat skeletons, a table skeleton, a `.skeleton-chart`. No spinner-in-a-void. | — |
| **Populated** | Stat cards, breakdown table, inline-SVG chart, legend. Exports enabled. | — |
| **Empty** | `.empty` / `.chart-empty`. Exports **disabled** (nothing to export). | *"No claims were filed in this period. Choose another quarter or year."* |
| **Export busy** | The pressed export button shows `.spinner` and gains `.btn.is-busy`; both export buttons lock until the download starts. | Button label → *"Preparing…"* |
| **Error** | `.alert-error` above the content with a specific message + a **Retry** (`.btn-secondary`). | *"Couldn't load the report for this period. Retry."* / export failure → *"Export failed. Try again."* |

---

## 5. Hierarchy, colour, motion, responsive, a11y

- **Hierarchy / one primary action:** **Export Excel** is the single primary (accent
  `.btn`); **Export PDF** is secondary. Stat *values* (24px/650) dominate the page; accent
  appears only on the primary button, the active toggle segment, and focus rings.
- **Colour discipline:** neutral surfaces, one blue accent, semantic hues only where they
  carry meaning (the three outcome colours). At most five hues on screen; body contrast
  ≥ 4.5:1 both themes.
- **Motion:** 180ms ease-out on the toggle; 600ms spinner; 1200ms skeleton shimmer. All
  disabled under `prefers-reduced-motion`. Nothing bounces.
- **Responsive:** rides the existing `@media` 1100/820 breakpoints — `grid-4`→2→1,
  `grid-2`→1. The SVG chart is `width:100%`. Intentional at 1440 and 390.
- **Accessibility:** the toggle is real `<button>`s in a `role="group"` with
  `aria-pressed`; focus-visible ring is the 3px `--accent-soft`; targets ≥ 40px on touch;
  the chart carries a text summary / legend so it is not colour-only.

---

## 6. Dark theme — designed, not shipped

The app ships **light-only** today (`styles.css` has no theme toggle). The tokens file
delivers a **designed** (not inverted) `[data-theme="dark"]` value set — depth from lighter
surfaces, every hue re-checked for contrast — so the whole app can adopt a theme *without
editing a single component*. **Adopting dark mode is a separate, system-wide ticket**; the
reporting section ships in the existing light theme. This block is the ready spec for it.

---

## 7. Design-review checklist (status)

| Item | Status | Note |
|---|---|---|
| First impression / one product | ✅ | Same shell, vocabulary and accent as the rest of the admin. |
| Hierarchy — one primary action | ✅ | Export Excel primary; stat values dominate. |
| Alignment & spacing on the scale | ✅ | Grids gap 16; 4px rhythm; centred `.content`. |
| Typography — one family, real scale | ✅ | System-ui stack; 20/16/14/24; no default serif. |
| Colour & contrast | ✅ | One accent + semantic outcome hues; ≥ 4.5:1. |
| Components — heights/radii/states | ✅ | Toggle, chart, skeleton, busy button all specced with every state. |
| States — loading/empty/error/success | ✅ | All designed (§4). |
| Responsive 390 / 1440 | ✅ (spec) | Rides existing breakpoints; SVG `width:100%`. |
| Dark mode | ➖ | Designed token set delivered; adoption is a separate ticket (§6). |
| Accessibility | ✅ | `aria-pressed` toggle, focus ring, target size, non-colour-only chart. |
| Copy | ✅ | Empty/error/busy copy written (§4). |
| Motion | ✅ | 180/600/1200ms; reduced-motion honoured. |

> **Rendered screenshot capture (1440/390 × light/dark) is owed against the built page**,
> not this direction doc. The sandbox host was fork-starved (`spawn EAGAIN`) on this run, so
> the direction and tokens were reconciled and landed via the repository API; the frontend
> build ticket must capture the state × width × theme screenshots against the real page and
> score this checklist with them in front of it before the section ships.
