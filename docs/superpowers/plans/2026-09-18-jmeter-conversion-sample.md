# JMeter Conversion Sample Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** JMeter JMX의 Thread Group과 HTTP 요청을 시나리오 Excel로 변환하는 CLI 및 입출력 샘플을 제공한다.

**Architecture:** 보안 설정된 DOM 파서가 JMeter element/hashTree 구조를 순회해 중간 `ConversionResult`를 만든다. JMeter 전용 writer는 기존 Excel 템플릿을 채우고 공통 실행 설정과 경고 YAML을 기록하며, Picocli `convert jmeter`가 이를 연결한다.

**Tech Stack:** Java 21 XML DOM, Apache POI 5.5.1, Jackson YAML, Picocli 4.7.7, JUnit 5, AssertJ

**Spec:** `project.md` 13.2 JMeter

## Global Constraints

- Thread Group thread 수와 loop count를 `sessions`, `iterations`로 변환한다.
- HTTP Request Sampler 순서를 보존한다.
- Header Manager와 raw body를 요청에 적용한다.
- Constant Timer와 Uniform Random Timer를 대기 설정으로 변환한다.
- 미지원 Controller, Assertion, Extractor, plugin은 경고에 기록한다.
- DTD와 외부 엔티티는 허용하지 않는다.

---

### Task 1: JMX Reader

**Files:** `JMeterTestPlanReader.java`, `JMeterTestPlanReaderTest.java`, `ConversionResult.java`

**Interfaces:** `JMeterTestPlanReader.read(Path): ConversionResult`; 공통 설정 Map과 시나리오, 경고를 반환한다.

- [x] Thread Group, Timer, Header, GET/POST, 미지원 Assertion을 포함한 실패 테스트 작성
- [x] 타입 부재 실패 확인
- [x] 보안 DOM 파서와 element/hashTree 순회 구현
- [x] reader 테스트 통과 확인

### Task 2: Excel/YAML Writer

**Files:** `JMeterScenarioWriter.java`, `JMeterScenarioWriterTest.java`

**Interfaces:** `JMeterScenarioWriter.write(ConversionResult, Path): void`; common 값, host, main_scenarios와 경고 YAML을 생성한다.

- [x] 실제 XLSX/YAML 출력 실패 테스트 작성
- [x] writer 부재 실패 확인
- [x] 템플릿 기반 writer 구현
- [x] writer 테스트 통과 확인

### Task 3: CLI and Samples

**Files:** `JMeterCommand.java`, `ConvertCommand.java`, `sample-test-plan.jmx`, 생성된 sample scenario/warnings, README, project.md

**Interfaces:** `convert jmeter --input test.jmx --output scenario.xlsx`

- [x] CLI 실패 테스트 작성 및 실패 확인
- [x] Picocli 명령 구현
- [x] JMX 샘플과 문서 갱신
- [x] 전체 테스트, package, 실제 JAR 변환 스모크 테스트
