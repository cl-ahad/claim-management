import type { ClaimStatus, ReportPeriod } from '../api/types';

// =====================================================================
// Report charts — hand-authored inline SVG (no chart library, per the
// reporting approach). Every colour comes from a CSS variable so the
// charts track the light/dark theme tokens; nothing here is a literal
// colour. Bars are <rect>s laid out on a simple linear scale with a
// fixed viewBox and preserveAspectRatio so the SVG scales fluidly to
// its container width.
// =====================================================================

const VB_W = 720;      // viewBox width — the SVG scales to container width
const VB_H = 260;      // viewBox height
const PAD_L = 8;       // left padding (labels sit under bars, not beside)
const PAD_R = 8;
const PAD_T = 16;
const PAD_B = 34;      // room for the period label row
const PLOT_H = VB_H - PAD_T - PAD_B;

const money = (n: number) =>
  n.toLocaleString('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const compact = (n: number) =>
  n.toLocaleString('en-US', { notation: 'compact', maximumFractionDigits: 1 });

/** A nice round upper bound for an axis, so bars never touch the ceiling. */
function niceMax(max: number): number {
  if (max <= 0) return 1;
  const pow = Math.pow(10, Math.floor(Math.log10(max)));
  const n = max / pow;
  const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
  return step * pow;
}

function GridLines({ max, format }: { max: number; format: (n: number) => string }) {
  const lines = [0, 0.25, 0.5, 0.75, 1];
  return (
    <>
      {lines.map((f) => {
        const y = PAD_T + PLOT_H * (1 - f);
        return (
          <g key={f}>
            <line className="chart-grid-line" x1={PAD_L} y1={y} x2={VB_W - PAD_R} y2={y} />
            <text className="chart-axis-label" x={PAD_L} y={y - 3}>{format(max * f)}</text>
          </g>
        );
      })}
    </>
  );
}

/** A single-series column chart — used for the claims-count-over-time chart. */
export function ColumnChart({
  labels, values, color, formatValue = (n) => n.toLocaleString(),
}: {
  labels: string[];
  values: number[];
  color: string;              // a CSS var expression, e.g. 'var(--accent)'
  formatValue?: (n: number) => string;
}) {
  const max = niceMax(Math.max(0, ...values));
  const plotW = VB_W - PAD_L - PAD_R;
  const slot = plotW / Math.max(1, values.length);
  const barW = Math.min(64, slot * 0.6);

  return (
    <svg viewBox={`0 0 ${VB_W} ${VB_H}`} role="img"
         aria-label={`Chart: ${values.map((v, i) => `${labels[i]} ${formatValue(v)}`).join(', ')}`}>
      <GridLines max={max} format={(n) => compact(n)} />
      {values.map((v, i) => {
        const h = max > 0 ? (v / max) * PLOT_H : 0;
        const x = PAD_L + slot * i + (slot - barW) / 2;
        const y = PAD_T + PLOT_H - h;
        return (
          <g key={i}>
            <rect className="chart-bar" x={x} y={y} width={barW} height={h}
                  rx={3} fill={color}>
              <title>{`${labels[i]}: ${formatValue(v)}`}</title>
            </rect>
            {v > 0 && (
              <text className="chart-value-label" x={x + barW / 2} y={y - 4}
                    textAnchor="middle">{formatValue(v)}</text>
            )}
            <text className="chart-axis-label" x={PAD_L + slot * i + slot / 2}
                  y={VB_H - 12} textAnchor="middle">{labels[i]}</text>
          </g>
        );
      })}
    </svg>
  );
}

type Series = { name: string; color: string; values: number[] };

/** A grouped column chart — used for charged-vs-paid value over time. */
export function GroupedColumnChart({
  labels, series, formatValue = money,
}: {
  labels: string[];
  series: Series[];
  formatValue?: (n: number) => string;
}) {
  const all = series.flatMap((s) => s.values);
  const max = niceMax(Math.max(0, ...all));
  const plotW = VB_W - PAD_L - PAD_R;
  const slot = plotW / Math.max(1, labels.length);
  const groupW = Math.min(120, slot * 0.7);
  const barW = groupW / series.length;

  return (
    <svg viewBox={`0 0 ${VB_W} ${VB_H}`} role="img"
         aria-label={`Chart: ${series.map((s) => s.name).join(' and ')} by period`}>
      <GridLines max={max} format={(n) => compact(n)} />
      {labels.map((label, i) => {
        const gx = PAD_L + slot * i + (slot - groupW) / 2;
        return (
          <g key={i}>
            {series.map((s, si) => {
              const v = s.values[i] ?? 0;
              const h = max > 0 ? (v / max) * PLOT_H : 0;
              const x = gx + barW * si;
              const y = PAD_T + PLOT_H - h;
              return (
                <rect key={s.name} className="chart-bar" x={x + 1} y={y}
                      width={Math.max(1, barW - 2)} height={h} rx={2} fill={s.color}>
                  <title>{`${label} · ${s.name}: ${formatValue(v)}`}</title>
                </rect>
              );
            })}
            <text className="chart-axis-label" x={PAD_L + slot * i + slot / 2}
                  y={VB_H - 12} textAnchor="middle">{label}</text>
          </g>
        );
      })}
    </svg>
  );
}

/** A stacked column chart — used for the status breakdown over time. */
export function StackedColumnChart({
  labels, series, formatValue = (n) => n.toLocaleString(),
}: {
  labels: string[];
  series: Series[];
  formatValue?: (n: number) => string;
}) {
  const totals = labels.map((_, i) => series.reduce((sum, s) => sum + (s.values[i] ?? 0), 0));
  const max = niceMax(Math.max(0, ...totals));
  const plotW = VB_W - PAD_L - PAD_R;
  const slot = plotW / Math.max(1, labels.length);
  const barW = Math.min(64, slot * 0.6);

  return (
    <svg viewBox={`0 0 ${VB_W} ${VB_H}`} role="img"
         aria-label={`Stacked chart: ${series.map((s) => s.name).join(', ')} by period`}>
      <GridLines max={max} format={(n) => compact(n)} />
      {labels.map((label, i) => {
        const x = PAD_L + slot * i + (slot - barW) / 2;
        let cursorY = PAD_T + PLOT_H;
        return (
          <g key={i}>
            {series.map((s) => {
              const v = s.values[i] ?? 0;
              const h = max > 0 ? (v / max) * PLOT_H : 0;
              cursorY -= h;
              return (
                <rect key={s.name} className="chart-bar" x={x} y={cursorY}
                      width={barW} height={h} fill={s.color}>
                  <title>{`${label} · ${s.name}: ${formatValue(v)}`}</title>
                </rect>
              );
            })}
            <text className="chart-axis-label" x={PAD_L + slot * i + slot / 2}
                  y={VB_H - 12} textAnchor="middle">{label}</text>
          </g>
        );
      })}
    </svg>
  );
}

export function ChartLegend({ items }: { items: { name: string; color: string }[] }) {
  return (
    <div className="chart-legend">
      {items.map((it) => (
        <span key={it.name}>
          <span className="swatch" style={{ background: it.color }} />
          {it.name}
        </span>
      ))}
    </div>
  );
}

// =====================================================================
// Status grouping — the REP-4 breakdown carries all nine ClaimStatus
// values; the status chart rolls them into three outcome buckets that
// match the badge palette (approved -> ok, pending -> warn, rejected ->
// danger). Kept here so the page and any future export share one map.
// =====================================================================

export type StatusGroup = 'approved' | 'pending' | 'rejected';

export const STATUS_GROUP: Record<ClaimStatus, StatusGroup> = {
  ACCEPTED: 'approved',
  PARTIALLY_PAID: 'approved',
  PAID: 'approved',
  DRAFT: 'pending',
  SUBMITTED: 'pending',
  APPEALED: 'pending',
  REJECTED: 'rejected',
  DENIED: 'rejected',
  VOID: 'rejected',
};

export const STATUS_GROUP_META: Record<StatusGroup, { label: string; color: string }> = {
  approved: { label: 'Approved', color: 'var(--ok)' },
  pending: { label: 'Pending', color: 'var(--warn)' },
  rejected: { label: 'Rejected / void', color: 'var(--danger)' },
};

/** Roll a period's per-status breakdown into the three outcome buckets. */
export function groupStatusCounts(period: ReportPeriod): Record<StatusGroup, number> {
  const out: Record<StatusGroup, number> = { approved: 0, pending: 0, rejected: 0 };
  for (const row of period.statusBreakdown) {
    out[STATUS_GROUP[row.status]] += row.count;
  }
  return out;
}
