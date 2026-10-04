# Postman Conversion Sample Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Postman Collection v2.0/v2.1 export JSON을 실행 가능한 시나리오 Excel로 변환하는 샘플 기능과 예제 입력을 제공한다.

**Architecture:** `PostmanCollectionReader`가 JSON을 중간 `ConvertedScenario` 목록과 경고로 변환한다. `PostmanScenarioWriter`가 기존 템플릿 위에 공통 host와 `main_scenarios` 행을 기록하고, `PostmanCommand`가 `convert postman` CLI와 경고 YAML 생성을 조정한다.

**Tech Stack:** Java 21, Jackson Databind/YAML, Apache POI 5.5.1, Picocli 4.7.7, JUnit 5, AssertJ

**Spec:** `project.md` 13.1 Postman

## Global Constraints

- Collection 탐색 순서를 main `order`로 보존한다.
- method, URL path/query, headers, raw body를 변환한다.
- `{{name}}` 변수는 `${global.name}`으로 보존한다.
- JavaScript와 인증 helper는 실행하지 않고 `<output-name>.warnings.yml`에 기록한다.
- 기본 예상 응답은 `200-299`로 기록한다.
- 기존 출력 파일은 덮어쓰지 않는다.

---

### Task 1: Postman JSON 중간 모델 변환

**Files:**
- Create: `src/main/java/io/github/apiscenariotester/conversion/ConvertedScenario.java`
- Create: `src/main/java/io/github/apiscenariotester/conversion/ConversionResult.java`
- Create: `src/main/java/io/github/apiscenariotester/conversion/postman/PostmanCollectionReader.java`
- Test: `src/test/java/io/github/apiscenariotester/conversion/postman/PostmanCollectionReaderTest.java`

**Interfaces:**
- Consumes: `PostmanCollectionReader.read(Path): ConversionResult`
- Produces: 순서, 이름, method, baseUrl, path, headers JSON, raw body를 가진 불변 시나리오와 경고 목록

- [x] **Step 1: 중첩 item, GET/POST, 변수, 스크립트/인증 경고를 검증하는 실패 테스트 작성**
- [x] **Step 2: `mvn -Dtest=PostmanCollectionReaderTest test`가 타입 부재로 실패하는지 확인**
- [x] **Step 3: Jackson tree model로 최소 파서와 변수 치환 구현**
- [x] **Step 4: 변환 테스트 통과 확인**

### Task 2: 시나리오 Excel과 경고 YAML 출력

**Files:**
- Create: `src/main/java/io/github/apiscenariotester/conversion/postman/PostmanScenarioWriter.java`
- Test: `src/test/java/io/github/apiscenariotester/conversion/postman/PostmanScenarioWriterTest.java`

**Interfaces:**
- Consumes: `PostmanScenarioWriter.write(ConversionResult, Path): void`
- Produces: `common`의 `host.postman.baseUrl`, `main_scenarios` 행, 경고가 있을 때만 생성되는 sibling YAML

- [x] **Step 1: 실제 XLSX 셀과 YAML 내용을 검증하는 실패 테스트 작성**
- [x] **Step 2: `mvn -Dtest=PostmanScenarioWriterTest test` 실패 확인**
- [x] **Step 3: 기존 `ScenarioTemplateWriter`를 재사용해 최소 출력 구현**
- [x] **Step 4: 출력 테스트 통과 확인**

### Task 3: CLI와 샘플 파일

**Files:**
- Create: `src/main/java/io/github/apiscenariotester/cli/ConvertCommand.java`
- Create: `src/main/java/io/github/apiscenariotester/cli/PostmanCommand.java`
- Modify: `src/main/java/io/github/apiscenariotester/cli/RootCommand.java`
- Create: `samples/postman/sample-collection.json`
- Test: `src/test/java/io/github/apiscenariotester/cli/PostmanCommandTest.java`
- Modify: `README.md`
- Modify: `project.md`

**Interfaces:**
- Consumes: `convert postman --input collection.json --output scenario.xlsx`
- Produces: 종료 코드 0과 Excel, 잘못된 입력/기존 출력은 종료 코드 2

- [x] **Step 1: 샘플 Collection을 사용하는 실패 CLI 테스트 작성**
- [x] **Step 2: `mvn -Dtest=PostmanCommandTest test` 실패 확인**
- [x] **Step 3: Picocli 하위 명령과 샘플 Collection 구현**
- [x] **Step 4: README와 구조 변경 이력 갱신**
- [x] **Step 5: `mvn clean package`와 실제 JAR 변환 스모크 테스트 실행**
