Reporting section — release runbook (Demo3)
============================================

This is the release record and runbook for the reporting feature
(quarterly & annual reports, charts, Excel + PDF export). Owner: Release
Manager (Ivy). Base branch: Demo3.

--------------------------------------------------------------------------
1. WHAT IS IN THE RELEASE
--------------------------------------------------------------------------
The feature is complete across two disjoint, mergeable pull requests. They
touch non-overlapping trees (backend/ vs frontend/), so there is no merge
conflict between them.

  PR #16  Backend — aggregation + export        base: Demo3   mergeable: yes
          Superset of PR #13 (S-4). Adds:
            - dto/ReportDtos.java
            - repository/ClaimRepository.reportRowsByStatus(from,to)
            - service/ReportingService.java        (quarterly/annual aggregation)
            - service/ReportExporter.java           (Excel via POI, PDF via OpenPDF,
                                                     charts via JFreeChart)
            - web/ReportController.java             (2 JSON + 4 export routes)
            - pom.xml  poi-ooxml 5.3.0, openpdf 1.3.30, jfreechart 1.5.4
            - service/ReportingServiceTest, service/ReportExporterTest,
              web/ReportControllerTest
          Metrics: claims count/period, total charged & collected/period,
          per-status breakdown (all 9 ClaimStatus values, zero-filled).

  PR #17  Frontend — Reports UI + design fixes   base: Demo3   mergeable: yes
          Superset of PR #14 (S-5). Adds:
            - pages/ReportsPage.tsx                 (type toggle, year/span, 4 states)
            - components/ReportCharts.tsx           (inline-SVG column/grouped/stacked)
            - reports.css                           (tokens, --skeleton-hi, 40px toggle)
            - api/types.ts  ReportResponse contract
            - App.tsx /reports route, Layout.tsx nav, main.tsx dark-theme + reports.css
          Consumes the PR #16 JSON endpoints; export buttons call the 4
          export routes byte-for-byte.

  Superseded (do NOT merge — kept for history): #13 (⊂ #16), #14 (⊂ #17),
  and #8–#12 (story/approach/design docs, already reflected in the code).

--------------------------------------------------------------------------
2. READINESS — HONEST STATUS  (blocking merge)
--------------------------------------------------------------------------
Two gates are NOT satisfied yet. Per Release Manager rules, this feature is
NOT to be merged into Demo3 until both are green with evidence.

  [ ] CI pipeline green.
      There is NO pipeline on this repo — no .github/workflows on Demo3
      (confirmed: GET .github/workflows -> 404). A ready workflow is in this
      branch at docs/reporting/ci.yml.txt; it CANNOT be committed to
      .github/workflows/ because the connected token LACKS `workflow` scope
      (PUT .github/workflows/ci.yml -> 404/refused). ACTION: grant the
      GitHub token `workflow` scope, then move docs/reporting/ci.yml.txt to
      .github/workflows/ci.yml on this branch. Actions then runs backend
      `mvn -B verify` (H2 + Mockito, no Postgres) and frontend `npm ci &&
      npm run build` on GitHub's runners.

  [ ] Local build/test gate green.
      The Claire sandbox executor cannot fork child processes this session
      (`fork: Resource temporarily unavailable` on git/node/mvn), so no
      local `mvn verify` / `npm run build` / browser render was run. All
      prior verification is by code inspection, not execution. ACTION: when
      the sandbox recovers OR CI runs, execute the gate and attach the
      counts.

--------------------------------------------------------------------------
3. MERGE ORDER  (once both gates are green)
--------------------------------------------------------------------------
Backend and frontend are independent, but land backend first so the API the
UI calls exists in Demo3 before the UI ships:
  1. Merge PR #16 into Demo3 (squash).
  2. Merge PR #17 into Demo3 (squash).
  3. Close #13 and #14 as superseded.
Each merge into Demo3 is a protected-branch action and requires human
approval with this rollback plan stated in the request.

--------------------------------------------------------------------------
4. ROLLBACK
--------------------------------------------------------------------------
Trigger: any CI failure on Demo3 after merge, a 5xx from /api/reports/* in
smoke test, or the Reports page failing to render.
  - Reporting is additive and read-only: new endpoints under /api/reports,
    a new /reports page, new files only. NO migration, NO schema change, NO
    change to existing endpoints or pages.
  - Rollback = `git revert` the squash merge commit(s) on Demo3 (frontend
    first, then backend) and push. No data cleanup needed because nothing
    writes. The three new pom dependencies are removed by reverting #16.
  - Blast radius on revert: the Reports nav link and /reports route
    disappear; every other page is untouched.

--------------------------------------------------------------------------
5. SMOKE TEST  (post-merge, before sign-off)
--------------------------------------------------------------------------
  - GET /api/reports/quarterly?year=2025            -> 200, 4 periods
  - GET /api/reports/annual?year=2025&years=5       -> 200, 5 periods
  - GET /api/reports/quarterly/export.xlsx?year=2025 -> 200, xlsx, attachment
  - GET /api/reports/quarterly/export.pdf?year=2025  -> 200, %PDF
  - Open /reports: toggle Quarterly/Annual, change year/span, see 3 charts,
    click Export Excel and Export PDF (files download), check loading/empty/
    error states, light and dark.
