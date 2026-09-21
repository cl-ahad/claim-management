# Backend CI — proposed GitHub Actions workflow

The reporting test suite (PRs #13, #16, #18) cannot be run in the Claire
sandbox (the executor returns `spawn EAGAIN`) and the repository has no CI,
so there is no automated gate. This file is the workflow that closes that
gap. It could NOT be committed to `.github/workflows/backend-ci.yml`
directly because the connected token lacks the `workflow` OAuth scope
(GitHub rejects workflow-file writes with 404/422 without it).

**To activate it:** a maintainer with `workflow` scope copies the block
below to `.github/workflows/backend-ci.yml` on `Demo3`. It then runs
`mvn -B test` on Temurin JDK 21 for every push and PR into Demo3. The
reporting tests are `@WebMvcTest` + Mockito unit slices and need no
database.

```yaml
name: Backend CI

on:
  push:
    branches: [Demo3]
  pull_request:
    branches: [Demo3]

jobs:
  test:
    name: Build & test (JDK 21 + Maven)
    runs-on: ubuntu-latest

    defaults:
      run:
        working-directory: backend

    steps:
      - name: Check out the repository
        uses: actions/checkout@v4

      - name: Set up JDK 21 (Temurin)
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Run the test suite
        run: mvn -B test
```

## Running the reporting suite by hand on any JDK 21 + Maven host

```
cd backend && mvn -B test -Dtest='ReportingServiceTest,ReportControllerTest,ReportExporterTest'
```

Covers, across PRs #13 / #16 / #18:
- `ReportingServiceTest` — quarterly/annual aggregation, half-open windows,
  every-status breakdown, money scaling, span guards, and the annual
  per-year breakdown (`annual_eachYearCarriesItsOwnPerStatusBreakdown`).
- `ReportControllerTest` — both JSON endpoints, both export endpoints
  × {xlsx, pdf}, content types, attachment filenames, ProblemJSON 400s.
- `ReportExporterTest` — real XLSX (zip signature, 3 sheets Summary /
  Quarters|Years / Status), real PDF (`%PDF` signature), empty-report path.
