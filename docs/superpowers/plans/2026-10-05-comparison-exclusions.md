# Comparison Exclusions Implementation Plan

**Goal:** Use newline-separated compareSkipHeader and compareSkipBody properties to exclude header names and JSONPath-selected nodes from reference comparisons.

**Architecture:** Compile immutable ComparisonRules during plan creation. Host keys override optional common defaults; runtime hosts override Excel/YAML host keys. Header names are case-insensitive. Jayway JSONPath 2.10.0 (Spring Boot managed version) selects concrete paths against independent JSON copies. Resolve selections first, then stream original JSON tokens while skipping selected nodes, preserving duplicate unselected fields, numeric spelling and original array addresses. Compare filtered bodies through the shared pretty formatter and existing scheme normalization. Saved responses and scripts keep full contents.

**Contracts:** Blank lines and missing paths are ignored. Invalid path syntax fails before HTTP. Non-JSON bodies retain text comparison. Root $ excludes the complete JSON body. JSONPath node selectors support fields, arrays, wildcards, recursive selection and filters. Runtime selection errors are comparison errors, not base failures. Existing properties default to no exclusions.

- [x] Add tests for newline header rules, wildcard/recursive JSONPath, defaults/host/runtime precedence and preserved results.
- [x] Add JSONPath dependency and immutable comparison rules; integrate plan/target/runner/comparator.
- [x] Verify invalid syntax, absent paths, root selection, arrays with multiple indices, non-JSON and parallel sessions.
- [x] Update README/project.md, obtain focused review and run Maven package (103 tests passed).
