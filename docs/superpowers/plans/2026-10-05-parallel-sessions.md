# Parallel Sessions Implementation Plan

**Goal:** Execute `common.sessions` independent scenarios concurrently using curl.

**Architecture:** Java 21 virtual threads run one sequential loop per session. Each session owns globals, executor variables, controls and a curl cookie jar. Compiled scripts and immutable plans are shared. Failure and stop affect only the current session. Results are merged in session order; Excel adds a session column without moving existing columns.

**Constraints:** Positive sessions, default 1; iterations per session; waits between calls within each session; shared UUID debug log folder. Abort and clean up workers on interruption or infrastructure errors. JMeter thread count already maps to sessions.

- [x] Add real curl concurrency, session isolation, failure policy and log/report tests; reproduce failures before implementation.
- [x] Accept sessions in plans, coordinate concurrent workers and session results, expose `scenario.session`.
- [x] Add report session column and configured sessions metric; update CLI help and validation output.
- [x] Replace obsolete single-session restrictions in tests and current documentation; record history in project.md.
- [x] Run Maven package and review cancellation, shared state and result ordering.
