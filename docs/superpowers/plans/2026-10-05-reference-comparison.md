# Reference URL Comparison Implementation Plan

**Goal:** Compare each configured host's baseUrl response with the same request sent to referenceUrl and include comparison results in Excel.

**Architecture:** A validated ReferenceTarget maps rendered request paths from baseUrl to referenceUrl. One separate reference curl client per session owns cookies. ReferenceComparison compares final normalized header maps, status and exact UTF-8 response text. Scripts and normal success policy continue to use the base response. Reference failures and mismatches are comparison results. Reference calls run after base curl and before response scripts, sharing the rendered method/headers/body/timeouts.

**Contracts:** Relative referenceUrl is invalid; path prefixes and query strings are preserved during mapping. Runtime hosts override common host values. PRE failures produce no reference calls. UUID debug logs cover both requests. Existing session-first columns remain; reference columns are appended. Header names are case-insensitive, map order is ignored, repeated values retain order, volatile headers are compared too. Body whitespace and JSON formatting are significant.

- [x] Add tests with real base/reference HTTP servers for equality, differences, ref file body, subset and runtime hosts.
- [x] Add validated reference target, final header parser and comparison result types; integrate per-session reference client.
- [x] Extend calls and summary reports with comparison flags, errors and reference curl hyperlinks.
- [x] Verify network failure, PRE stop, missing referenceUrl, invalid targets, session isolation and cleanup.
- [x] Update README/project.md, run package and obtain code review.
