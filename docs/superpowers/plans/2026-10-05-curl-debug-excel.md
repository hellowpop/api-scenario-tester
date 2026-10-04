# curl 실행 및 debug Excel 보고서 구현 계획

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. 프로젝트 규칙에 따라 확인 질문 없이 현재 세션에서 직접 수행한다.

**Goal:** 순차 API 호출을 curl로 수행하고 `--debug` 시 호출별 `curl/[uuid].txt` 로그를 결과 Excel에서 열 수 있게 한다.

**Architecture:** 입력 YAML은 사전 검증된 요청으로 조립한다. `CurlHttpClient`가 curl 프로세스와 임시 파일을 관리하고 `ScenarioRunner`가 순차 실행 결과를 수집한다. `ExecutionExcelWriter`가 결과 행과 실제 파일 하이퍼링크를 작성한다.

**Tech Stack:** Java 21, ProcessBuilder, curl, Apache POI, Jackson YAML, Picocli, JUnit, 로컬 JDK HttpServer.

**Spec:** `project.md` 18절.

## Global Constraints

- 병렬 API 실행은 지원하지 않는다. `sessions`는 생략 또는 `1`만 허용한다.
- 실행 JAR 이름은 `api-scenario-tester.jar`다.
- `run --scenario FILE [--config FILE] [--output FILE.xlsx] [--debug]`.
- debug 로그는 결과 Excel 부모 폴더의 `curl/[uuid].txt`; Excel에는 `curlLog` 하이퍼링크 컬럼을 둔다.
- debug 미지정 시 로그 파일·폴더를 생성하지 않고 해당 셀은 빈 값이다.
- 셸을 거치지 않고 curl 인자를 분리해 실행한다. UTF-8 본문은 stdin으로 전송한다.
- HTTP 실패와 curl 실패를 결과에 기록하며 종료 코드 1, 입력 오류는 2다.
- 현재 미지원 스크립트는 사전 거부한다. 외부 호출 전에 모든 행과 참조를 검증한다.

## Review Focus

- 공백·한글 경로, 본문의 따옴표와 셸 특수문자: 원문 유지 및 상대 링크.
- 실패·timeout·curl 실행 파일 부재: 호출 로그·실패 행 보존, 프로세스 정리.
- debug 해제: 불필요한 curl 로그 없음.
- 입력 오류·결과 파일 존재: 외부 호출 없이 실패하고 기존 파일 유지.
- 호출 순서·반복·쿠키: 한 컨텍스트에서 순차 유지.

### Task 1: CLI에서 관찰할 수 있는 실패 테스트

Files: `src/test/java/io/github/apiscenariotester/cli/RunCommandTest.java`.

- [x] 실제 로컬 HTTP 서버와 실제 curl로 debug 요청·응답 본문, 상대 링크, 여러 호출 UUID, 순차 반복을 검증한다.
- [x] debug 해제, HTTP 실패, curl 부재, timeout, 기존 결과 보존, 입력 오류 시 호출 없음, HEAD 및 쿠키 유지 테스트를 추가한다.
- [x] `mvn test -Dtest=RunCommandTest`에서 기존 placeholder 때문에 실패함을 확인한다.

### Task 2: curl 및 순차 실행 파이프라인

Files: `http/CurlRequest.java`, `http/CurlResponse.java`, `http/CurlHttpClient.java`, `execution/ScenarioRunPlan.java`, `execution/ScenarioRunPlanReader.java`, `execution/ScenarioRunner.java`, `execution/CallResult.java`, `report/ExecutionExcelWriter.java`, `cli/RunCommand.java`, `cli/ValidateCommand.java`, `cli/RootCommand.java`.

Interfaces: `ScenarioRunPlanReader.read(Path scenario, Path config)` → 검증된 실행 계획; `ScenarioRunner.run(ScenarioRunPlan plan, Path output, boolean debug)` → 호출 결과; `ExecutionExcelWriter.write(List<CallResult> calls, Path output)` → 실제 hyperlink가 있는 워크북.

- [x] curl 프로세스와 stdin/stdout/stderr·응답·trace 캡처, timeout 및 실패 로그를 구현한다.
- [x] YAML·config 조립, 단일 실행 검증, 순서·반복·subset·대기 정책을 연결한다.
- [x] Excel `summary`, `calls` 시트와 `curlLog` 파일 링크를 구현한다.
- [x] `run` 및 실제 `validate`를 연결하고 전체 테스트를 통과시킨다.

### Task 3: 문서와 최종 검증

Files: `project.md`, `README.md`, `input/excel/ScenarioTemplateWriter.java`.

- [x] 결과 기본 경로를 `results.xlsx`로 변경하고 이전 `.yml` 설정은 실행 시 `.xlsx`로 치환한다.
- [x] curl 설치·debug 사용법·로그 내용·지원 범위 및 구조 변경 이력을 기록한다.
- [x] 전체 `mvn package`, 실제 JAR 로컬 HTTP smoke 및 Excel 링크 검증.
- [x] 최종 코드 검토와 계획 체크리스트 갱신. 현재 디렉터리는 Git 저장소가 아니므로 worktree·commit 절차는 적용하지 않는다.

## 실행 기록

- Task 1 RED: 신규 12개 테스트 중 9개가 placeholder의 미지원 옵션 때문에 실패; 구현 후 전체 36개 GREEN.
- Task 3 RED: 템플릿의 결과 기본값 테스트가 `results.yml` 때문에 실패; `results.xlsx`로 변경 후 GREEN.
- Review RED: 헤더 NUL이 validate를 통과하고 빈 헤더가 누락됨. 각각 수정 후 전체 38개 GREEN.
- 최종 빌드 및 실제 JAR 로컬 HTTP smoke 통과. 사용자 Maven 설정과 시스템 Java 설정은 변경하지 않았다.
- Ruling: Git 저장소가 없으므로 worktree·commit·Git 기반 ledger 대신 이 계획의 체크리스트와 `project.md` 18.5절에 진행·검증을 기록한다.
- Ruling: 요청에 맞춰 현재 세션에서 직접 구현하고 skill이 요구하는 독립 코드 검토만 위임했다. 기존 사용자 규칙에 따라 설계·계획 확인 질문은 하지 않았다.
