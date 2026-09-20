# DESIGN — Denials worklist (S-3)

Design direction for `GET /api/denials` (story **S-14**). This document sets how the
Denials worklist looks and behaves **before any production screen is built**. It reuses
the committed design tokens in `frontend/src/styles.css` verbatim — **no new colors,
type sizes, radii, or spacing values are introduced.** A rendered wireframe accompanies
it under [`docs/wireframes/`](./wireframes/denials-worklist.html).

- **Wireframe (desktop, 1440):** `docs/wireframes/denials-worklist-1440.png`
- **Wireframe (phone, 390):** `docs/wireframes/denials-worklist-390.png`
- **Source:** `docs/wireframes/denials-worklist.html` (links the real `styles.css`)

> **Direction:** the existing product is a **Linear/Vercel-calm** enterprise system —
> neutral surfaces, one blue accent (`--accent #1f5fd6`) used sparingly, 1px hairline
> borders, tabular numerics. The worklist extends that system; it does not add a new one.

---

## 1. The token set we build on

The worklist uses only these committed tokens. The ones that carry the denial-specific
meaning are the **semantic** colors:

| Token | Value | Used for on this screen |
|---|---|---|
| `--bg` / `--surface` / `--surface-2` | `#f6f7f9` / `#fff` / `#f1f3f6` | page, card, sticky table header |
| `--border` / `--border-strong` | `#dfe3e9` / `#c6ccd6` | table dividers, control borders |
| `--text` / `--text-muted` / `--text-faint` | `#131a24` / `#5c6879` / `#8b95a5` | primary / secondary / tertiary text |
| `--accent` / `--accent-hover` / `--accent-soft` | `#1f5fd6` / … | primary action, focus ring, links |
| `--ok` / `--ok-soft` | `#13795b` / `#e2f4ee` | **healthy** appeal window |
| `--warn` / `--warn-soft` | `#8a5a06` / `#fdf1dc` | **deadline approaching** |
| `--danger` / `--danger-soft` | `#b32020` / `#fdeaea` | **≤ 3 days and expired** |
| `--radius` / `--radius-s` | `8px` / `5px` | card / control corners |
| `--font` / `--mono` | system-ui / mono stack | body / claim numbers, codes |

Existing component classes reused as-is: `.card`, `.filters` + `.field`, `table`/`th`/`td`
with `.num` and `.mono`, `.badge badge-DENIED|APPEALED|PAID|…`, `.pill`, `.btn`
(`.btn-secondary`, `.btn-sm`), `.empty` / `.empty-title`, `.pagination`. The screen is
assembled from these — it invents no new control.

---

## 2. Layout — table sorted by deadline

Content sits in the shared `.content` max-width (**1280px**) with the app's page padding,
inside one `.card`. The list is a single `table`, one row per denied claim, **sorted by
appeal deadline ascending** so the most urgent work is at the top (the API sorts; the UI
does not re-sort).

Columns, left to right:

| Column | Class | Notes |
|---|---|---|
| Claim number | `.mono` | monospace, primary link into the claim |
| Patient | — | name + faint MRN underneath (proximity grouping) |
| Payer | — | |
| Denial reason | — | `group-CARC` in a `.pill`, optional `+RARC` in a second `.pill`, plain-language label in `.muted`. Multiple reasons stack as multiple pills. |
| Denied | `.nowrap` | denial date |
| Appeal deadline | `.nowrap` | the **frozen** deadline stamped at filing — display of a stored value, never recomputed |
| **Days remaining** | `.num` | computed **for display only**; see §3 |
| Charged | `.num` | right-aligned, tabular numerics |
| Status | — | `.badge` (DENIED / APPEALED / …) |

The `th` row uses `--surface-2` and is **sticky** on vertical scroll (the class already
supports it), so the header stays while a biller works down a long worklist. Rows are
`.clickable`; hover uses the existing row-hover token. Numerics right-align and share one
set of divider lines — no ragged edges.

---

## 3. Days-remaining and expired treatments (semantic tokens)

"Days remaining" is a **display-only** computation from the frozen deadline and today; it
is never persisted and never drives status. The value is rendered with a small status dot
+ text, escalating through the existing semantic tokens — **no new colors**:

| Condition | Token | Rendering |
|---|---|---|
| > 14 days | `--ok` | dot `--ok`, text `--text` (weight 400) — "30 days" |
| 8–14 days | `--warn` | dot `--warn`, text `--warn` **weight 600** — "10 days" |
| 1–7 days | `--danger` | dot `--danger`, text `--danger` **weight 600** — "3 days" |
| **Expired** (≤ 0) | `--danger` | dot `--danger`, text `--danger` **weight 600**, label **"Expired"** |

**Expired claims stay visible** and are marked, not hidden (per S-14). Urgency reads by
**color + weight**, not size alone, so the treatment survives a colorblind read (the dot
is redundant with position, and "Expired" is a word, not just red). Contrast: `--warn`
(#8a5a06) and `--danger` (#b32020) on `--surface` both clear 4.5:1 for the ≥600-weight text.

> The deadline column shows the stored deadline; the days-remaining column is the only
> place a live computation appears, and it is decorative/for-triage only.

---

## 4. Filter controls

Filters sit in the existing `.filters` bar (which wraps via flex) above the table, each in
a `.field` (label + control). Per S-14: **payer**, **reason code**, **date range**.

| Filter | Control | Notes |
|---|---|---|
| Payer | `select` | "All payers" default |
| Reason group | `select` | CO / PR / OA / PI — the structured group code |
| Reason code (CARC) | `text input` | e.g. `197`; pairs with group |
| Denied from / Denied to | two `date` inputs | the denied-date range |
| Clear | `.btn.btn-secondary` | resets to defaults |

All controls are the app's own styled `select`/`input`/`button` — **never browser
defaults**. Focus uses the committed 2px `--accent` ring. Applying a filter re-fetches;
the sort (deadline asc) is preserved.

---

## 5. Every state is designed

- **Loading** — a **skeleton in the final table layout** (shimmer tinted between
  `--surface-2` and `--border`), not a spinner in a void. Column widths match the real
  table so nothing jumps on load. Shimmer honours `prefers-reduced-motion`.
- **Empty** — the existing `.empty` block: title **"No denied claims in this window"**,
  a line that says what belongs here and the next action ("Widen the denied-date range or
  clear the reason-code filter"), and a **Clear filters** button. An empty worklist is
  good news, so the copy is neutral, not alarming.
- **Error** — inline within the card (reuse the app's error style), specific and
  recoverable, with a **Retry**; never a blank screen.
- **Success** — filtering/refresh confirmation follows the app's existing toast pattern.

Interface copy is written to say what each thing does; codes (CARC/RARC) always carry a
plain-language label so the screen is readable without a code sheet.

---

## 6. Behaviour at 1440 and 390

**1440 (desktop) — verified, healthy & styled.** Sidebar + main shell, filters on one
wrapping row, full nine-column table with sticky header and right-aligned numerics.
Screenshot: `docs/wireframes/denials-worklist-1440.png`.

**390 (phone) — verified; documented adaptation required.** The filter bar already stacks
correctly (`.filters` wraps to one column). **The nine-column table is wider than the
viewport.** The intended behaviour, built from existing tokens only:

1. The table scrolls **horizontally inside its `.card`** (wrap in an `overflow-x:auto`
   container) with **Claim number** and **Days remaining** as the anchored, most-scannable
   columns — a biller triages on "which claim, how long left."
2. `.filters` collapse to a single stacked column (already the case).
3. No token, color, or size changes are needed — this is a layout container + a narrow
   breakpoint, not a restyle.

> **Finding for the implementing engineer (S-14):** `styles.css` has **no responsive
> breakpoint** for the wide table or the fixed sidebar today. The worklist screen must add
> a horizontal-scroll wrapper (and/or a stacked card view under ~640px) using existing
> tokens. This is called out here so it is designed, not discovered at build time.

---

## 7. Dark mode — open decision (no values invented)

**The committed token set is light-only.** `frontend/src/styles.css` defines a single
`:root` palette; there is **no `prefers-color-scheme`, no `.dark`/`[data-theme]` scope, and
no dark values anywhere in the frontend.** The directive asks for "both themes," but the
craft rule is firm: **dark mode is designed, not inverted, and I will not invent color
values the token file does not define.**

Recommended path (surfaced for commander decision — **not** implemented here):

- Add a **second token scope** (`[data-theme="dark"]` or `@media (prefers-color-scheme)`)
  in `styles.css` that redefines the *same token names* with dark values — depth from
  lighter surfaces, not heavier shadows; `--danger`/`--warn` re-tuned for contrast on a
  dark surface. Because every component reads tokens, the worklist inherits dark for free.
- Until that token layer lands, the worklist ships **light only**, and this document does
  not show a dark render because doing so would require inventing values.

**Decision needed:** approve adding a dark token scope to `styles.css` (a separate story),
or confirm the worklist ships light-only for now. Either way, no worklist component will
carry a literal color — dark comes entirely through tokens when the scope exists.

---

## 8. Design review checklist — worklist (self-review against the rendered mock)

| Item | Verdict | Note |
|---|---|---|
| One typeface, type scale, 4px spacing | **Pass** | inherits app tokens |
| One primary action most visible | **Pass** | row → claim is the primary path; Clear is secondary |
| Hierarchy by size **and** weight **and** color | **Pass** | title/sub/caption differ on all three; urgency via weight+color |
| Whitespace from the scale, centred max-width | **Pass** | `.content` 1280px, page padding |
| No browser-default controls | **Pass** | app-styled select/input/button; 2px accent focus |
| Color discipline (accent sparing, ≤5 hues, ≥4.5:1) | **Pass** | neutrals + accent + 3 semantics; contrast checked |
| Consistent components & states | **Pass** | reuses `.badge`/`.pill`/`.btn`/`.filters` |
| Every state designed (loading/empty/error/success) | **Pass** | skeleton, empty, inline error, toast |
| Motion quiet, reduced-motion honoured | **Pass** | shimmer only; disabled under reduced-motion |
| **Phone (390) layout** | **Fail → fix specified** | table overflows; §6 requires an overflow-x wrapper / stacked view |
| **Dark mode** | **Fail → open decision** | tokens are light-only; §7 requires a dark token scope before any dark render |

The two fails are **not** defects to fix in this ticket (no production screen ships here) —
they are the design constraints the S-14 implementer must build to, recorded so they are
handled deliberately.
