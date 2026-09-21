import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, ApiError, tokenStore } from '../api/client';
import type { ReportResponse } from '../api/types';
import { PageHeader } from '../components/Layout';
import { Card, Money } from '../components/Common';
import {
  ChartLegend, ColumnChart, GroupedColumnChart, StackedColumnChart,
  STATUS_GROUP_META, groupStatusCounts,
} from '../components/ReportCharts';

// The reporting section (S-5). A report-type toggle (Quarterly | Annual)
// drives the two REP-4 endpoints; the response renders as three charts —
// claims count over time, charged-vs-paid value over time, and the
// status breakdown — with a stat summary. All four states are designed:
// loading skeleton, empty, populated and error.

const CURRENT_YEAR = new Date().getUTCFullYear();
const YEAR_CHOICES = Array.from({ length: 5 }, (_, i) => CURRENT_YEAR - i);
const SPAN_CHOICES = [3, 5, 10];

function parsePeriod(raw: string | null): 'quarterly' | 'annual' {
  return raw === 'annual' ? 'annual' : 'quarterly';
}
function parseYear(raw: string | null): number {
  const n = Number(raw);
  return Number.isInteger(n) && n >= 2000 && n <= CURRENT_YEAR ? n : CURRENT_YEAR;
}
function parseSpan(raw: string | null): number {
  const n = Number(raw);
  return SPAN_CHOICES.includes(n) ? n : 5;
}

export default function ReportsPage() {
  const [params, setParams] = useSearchParams();
  const period = parsePeriod(params.get('period'));
  const year = parseYear(params.get('year'));
  const span = parseSpan(params.get('years'));

  const [report, setReport] = useState<ReportResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [exporting, setExporting] = useState<null | 'xlsx' | 'pdf'>(null);
  const [exportError, setExportError] = useState<string | null>(null);

  const setParam = useCallback((patch: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v); else next.delete(k);
    }
    setParams(next);
  }, [params, setParams]);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    const path = period === 'annual' ? '/reports/annual' : '/reports/quarterly';
    const query = period === 'annual' ? { year, years: span } : { year };
    api<ReportResponse>(path, { query })
      .then((r) => { if (alive) setReport(r); })
      .catch((err) => {
        if (alive) {
          setReport(null);
          setError(err instanceof ApiError ? err.detail : 'Could not load the report.');
        }
      })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [period, year, span, reloadKey]);

  const chartData = useMemo(() => {
    if (!report) return null;
    const labels = report.periods.map((p) => p.label);
    const counts = report.periods.map((p) => p.claimsCount);
    const charged = report.periods.map((p) => p.totalCharged);
    const paid = report.periods.map((p) => p.totalPaid);
    const grouped = report.periods.map(groupStatusCounts);
    return {
      labels, counts, charged, paid,
      approved: grouped.map((g) => g.approved),
      pending: grouped.map((g) => g.pending),
      rejected: grouped.map((g) => g.rejected),
    };
  }, [report]);

  const isEmpty = !!report && report.totalClaims === 0;

  // Export is delivered server-side (S-6). The button streams the file
  // for the current view; if the export endpoint is not yet available the
  // error surfaces inline rather than crashing the page.
  const runExport = useCallback(async (format: 'xlsx' | 'pdf') => {
    setExporting(format);
    setExportError(null);
    try {
      const q = new URLSearchParams(
        period === 'annual'
          ? { year: String(year), years: String(span) }
          : { year: String(year) },
      );
      const token = tokenStore.get();
      const res = await fetch(`/api/reports/${period}/export.${format}?${q}`, {
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
      });
      if (!res.ok) throw new Error(`Export failed (${res.status})`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `claims-${period}-${year}.${format === 'xlsx' ? 'xlsx' : 'pdf'}`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch {
      setExportError('Export is not available yet. Charts are up to date; try the export again shortly.');
    } finally {
      setExporting(null);
    }
  }, [period, year, span]);

  const exportActions = (
    <>
      <button className={`btn export-actions${exporting === 'xlsx' ? ' is-busy' : ''}`}
              disabled={loading || isEmpty || exporting !== null}
              onClick={() => runExport('xlsx')}>
        {exporting === 'xlsx' ? <span className="btn-spinner" aria-hidden /> : <DownloadIcon />}
        Export Excel
      </button>
      <button className={`btn btn-secondary export-actions${exporting === 'pdf' ? ' is-busy' : ''}`}
              disabled={loading || isEmpty || exporting !== null}
              onClick={() => runExport('pdf')}>
        {exporting === 'pdf' ? <span className="btn-spinner" aria-hidden /> : <DownloadIcon />}
        Export PDF
      </button>
    </>
  );

  return (
    <>
      <PageHeader
        title="Reports"
        subtitle="Quarterly and annual claim reporting"
        actions={exportActions}
      />

      <div className="content">
        {exportError && <div className="alert alert-warn">{exportError}</div>}

        <div className="card">
          {/* Controls: report type, year, and (for annual) the span. */}
          <div className="report-controls">
            <div className="control-group">
              <span className="control-label">Report</span>
              <div className="segmented" role="group" aria-label="Report type">
                <button type="button" aria-pressed={period === 'quarterly'}
                        onClick={() => setParam({ period: 'quarterly', years: '' })}>
                  Quarterly
                </button>
                <button type="button" aria-pressed={period === 'annual'}
                        onClick={() => setParam({ period: 'annual' })}>
                  Annual
                </button>
              </div>
            </div>

            <div className="control-group">
              <span className="control-label">
                {period === 'annual' ? 'Ending year' : 'Year'}
              </span>
              <div className="segmented" role="group" aria-label="Year">
                {YEAR_CHOICES.map((y) => (
                  <button key={y} type="button" aria-pressed={y === year}
                          onClick={() => setParam({ year: String(y) })}>
                    {y}
                  </button>
                ))}
              </div>
            </div>

            {period === 'annual' && (
              <div className="control-group">
                <span className="control-label">Years</span>
                <div className="segmented" role="group" aria-label="Number of years">
                  {SPAN_CHOICES.map((s) => (
                    <button key={s} type="button" aria-pressed={s === span}
                            onClick={() => setParam({ years: String(s) })}>
                      {s}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>

          {loading && <ReportSkeleton />}

          {!loading && error && (
            <div className="card-body">
              <div className="alert alert-error">{error}</div>
              <button className="btn btn-secondary" onClick={() => setReloadKey((k) => k + 1)}>
                Retry
              </button>
            </div>
          )}

          {!loading && !error && report && isEmpty && (
            <div className="chart-empty">
              <div>
                <div className="empty-title">No claims in this period</div>
                <div>
                  {period === 'annual'
                    ? `No claims were created in the ${span} years ending ${year}.`
                    : `No claims were created in ${year}.`}
                  {' '}Try another year, or switch report type.
                </div>
              </div>
            </div>
          )}

          {!loading && !error && report && !isEmpty && chartData && (
            <div className="card-body">
              <div className="grid grid-4">
                <Stat label="Total claims" value={report.totalClaims.toLocaleString()}
                      note={period === 'annual' ? `${span} years to ${year}` : `${year}`} />
                <Stat label="Charged" value={<Money value={report.totalCharged} />}
                      note="gross billed" />
                <Stat label="Collected" value={<Money value={report.totalPaid} />}
                      note={collectionNote(report)} />
                <Stat label="Periods" value={String(report.periods.length)}
                      note={period === 'annual' ? 'years' : 'quarters'} />
              </div>

              <div className="grid grid-2 mt-16">
                <Card title="Claims count over time" tight>
                  <div className="chart-frame">
                    <ColumnChart labels={chartData.labels} values={chartData.counts}
                                 color="var(--accent)" />
                  </div>
                </Card>

                <Card title="Claim value over time" tight>
                  <div className="chart-frame">
                    <GroupedColumnChart
                      labels={chartData.labels}
                      series={[
                        { name: 'Charged', color: 'var(--info)', values: chartData.charged },
                        { name: 'Collected', color: 'var(--ok)', values: chartData.paid },
                      ]}
                    />
                  </div>
                  <ChartLegend items={[
                    { name: 'Charged', color: 'var(--info)' },
                    { name: 'Collected', color: 'var(--ok)' },
                  ]} />
                </Card>
              </div>

              <div className="mt-16">
                <Card title="Status breakdown over time" tight>
                  <div className="chart-frame">
                    <StackedColumnChart
                      labels={chartData.labels}
                      series={[
                        { name: STATUS_GROUP_META.approved.label, color: STATUS_GROUP_META.approved.color, values: chartData.approved },
                        { name: STATUS_GROUP_META.pending.label, color: STATUS_GROUP_META.pending.color, values: chartData.pending },
                        { name: STATUS_GROUP_META.rejected.label, color: STATUS_GROUP_META.rejected.color, values: chartData.rejected },
                      ]}
                    />
                  </div>
                  <ChartLegend items={[
                    { name: STATUS_GROUP_META.approved.label, color: STATUS_GROUP_META.approved.color },
                    { name: STATUS_GROUP_META.pending.label, color: STATUS_GROUP_META.pending.color },
                    { name: STATUS_GROUP_META.rejected.label, color: STATUS_GROUP_META.rejected.color },
                  ]} />
                </Card>
              </div>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

function collectionNote(report: ReportResponse): string {
  if (report.totalCharged <= 0) return 'no charges';
  const rate = (report.totalPaid / report.totalCharged) * 100;
  return `${rate.toFixed(1)}% of charges`;
}

function Stat({ label, value, note }: { label: string; value: ReactNode; note?: string }) {
  return (
    <div className="card stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  );
}

// Loading state: the final layout drawn as skeletons — four stat cards,
// two half-width charts and a full-width chart — never a spinner in a void.
function ReportSkeleton() {
  return (
    <div className="card-body">
      <div className="grid grid-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="card stat">
            <div className="skeleton skeleton-line" style={{ width: '50%' }} />
            <div className="skeleton skeleton-value" />
          </div>
        ))}
      </div>

      <div className="grid grid-2 mt-16">
        {Array.from({ length: 2 }).map((_, i) => (
          <div key={i} className="card">
            <div className="card-body">
              <div className="skeleton skeleton-line" style={{ width: '40%' }} />
              <div className="skeleton skeleton-chart" />
            </div>
          </div>
        ))}
      </div>

      <div className="mt-16">
        <div className="card">
          <div className="card-body">
            <div className="skeleton skeleton-line" style={{ width: '30%' }} />
            <div className="skeleton skeleton-chart" />
          </div>
        </div>
      </div>
    </div>
  );
}

// lucide "download" glyph, inline so the page adds no icon dependency and
// the stroke follows currentColor like the rest of the button label.
function DownloadIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="2" strokeLinecap="round"
         strokeLinejoin="round" aria-hidden focusable="false">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <polyline points="7 10 12 15 17 10" />
      <line x1="12" y1="15" x2="12" y2="3" />
    </svg>
  );
}