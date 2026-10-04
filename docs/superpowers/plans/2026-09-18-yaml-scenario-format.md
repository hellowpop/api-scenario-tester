# YAML Scenario Format Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** YAML을 실행 기준 시나리오 형식으로 정의하고 Excel과 양방향 변환한다.

**Architecture:** `ScenarioDocument`가 모든 시트의 의미를 Map/List 구조로 보존한다. `ScenarioExcelCodec`와 `ScenarioYamlCodec`가 동일 모델을 읽고 쓰며 Picocli `convert excel`, `convert yaml`이 파일 변환을 제공한다.

**Tech Stack:** Java 21, Apache POI 5.5.1, Jackson YAML 2.21, Picocli 4.7.7, JUnit 5, AssertJ

**Spec:** `project.md`

## Global Constraints

- YAML 최상위 `version`은 `1`이다.
- `common`, `resultFormat`은 순서 보존 Map이다.
- `scripts`, `subScenarios`, `mainScenarios`는 컬럼명을 key로 갖는 행 목록이다.
- 기존 출력 파일은 덮어쓰지 않는다.
- `validate`와 `run`의 시나리오 입력 기준은 YAML이다.

---

### Task 1: YAML Document Codec

- [x] `ScenarioDocument`와 YAML read/write 실패 테스트 작성 및 실패 확인
- [x] 불변 문서 모델과 `ScenarioYamlCodec` 구현
- [x] YAML round-trip 테스트 통과

### Task 2: Excel Document Codec

- [x] 기존 템플릿의 모든 시트를 읽고 다시 쓰는 실패 테스트 작성 및 실패 확인
- [x] `ScenarioExcelCodec` 구현
- [x] Excel round-trip 테스트 통과

### Task 3: CLI, Samples, Documentation

- [x] `convert excel`, `convert yaml` CLI 실패 테스트 작성 및 실패 확인
- [x] Picocli 명령 구현
- [x] YAML 샘플과 왕복 Excel 샘플 생성
- [x] README와 `project.md`의 실행 기준 입력 변경
- [x] 전체 테스트, package, 실제 JAR 왕복 변환 검증
