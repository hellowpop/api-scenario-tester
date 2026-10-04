# Core Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실행 가능한 Spring Boot CLI와 Excel 템플릿 생성, 호출 대기 정책, 실행시간 통계 계산의 첫 번째 동작 가능한 기반을 만든다.

**Architecture:** `cli`가 사용자 명령을 받고 `input.excel`의 템플릿 작성기를 호출한다. 실행과 보고 기능에서 공통으로 사용할 대기 정책과 통계 계산은 각각 `execution`과 `report`에 순수 Java 단위로 격리하여 외부 시스템 없이 테스트한다.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Maven, Picocli 4.7.7, Apache POI 5.5.1, JUnit 5, AssertJ

**Spec:** `project.md`

## Global Constraints

- Java 버전은 21이다.
- 빌드 도구는 Maven이다.
- 모든 구현은 실패하는 테스트를 먼저 확인한 다음 최소 구현으로 통과시킨다.
- 기술 문서와 구조 변경 이력은 `project.md`에 기록한다.
- CLI 정상 완료는 종료 코드 `0`, 입력 오류는 `2`를 반환한다.

---

### Task 1: Maven과 CLI 실행 골격

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/io/github/apiscenariotester/ApiScenarioTesterApplication.java`
- Create: `src/main/java/io/github/apiscenariotester/cli/RootCommand.java`
- Test: `src/test/java/io/github/apiscenariotester/cli/RootCommandTest.java`

**Interfaces:**
- Consumes: 명령행 인자 `--help`
- Produces: `RootCommand.execute(String... args): int`, 도움말에 `template`, `validate`, `run`, `convert` 명령 표시

- [x] **Step 1: 빌드 설정 작성**

`pom.xml`에 Spring Boot parent 4.1.1, Java 21, Picocli 4.7.7, POI 5.5.1, JUnit/AssertJ 테스트 의존성과 Spring Boot Maven plugin을 선언한다.

- [x] **Step 2: 실패하는 CLI 테스트 작성**

```java
@Test
void helpListsPublicCommands() {
    StringWriter output = new StringWriter();
    int exitCode = new RootCommand().execute(output, "--help");
    assertThat(exitCode).isZero();
    assertThat(output.toString()).contains("template", "validate", "run", "convert");
}
```

- [x] **Step 3: 테스트가 올바른 이유로 실패하는지 확인**

Run: `mvn -Dtest=RootCommandTest test`
Expected: `RootCommand`가 없어 컴파일 실패

- [x] **Step 4: 최소 CLI 구현 작성**

`RootCommand`는 Picocli `CommandLine`을 생성해 실행하고, 루트 명령과 향후 공개 명령 이름을 도움말에 노출한다. 애플리케이션 main은 해당 실행 코드를 프로세스 종료 코드로 전달한다.

- [x] **Step 5: CLI 테스트 통과 확인**

Run: `mvn -Dtest=RootCommandTest test`
Expected: 1 test, 0 failures

### Task 2: Excel 템플릿 생성 명령

**Files:**
- Create: `src/main/java/io/github/apiscenariotester/input/excel/ScenarioTemplateWriter.java`
- Create: `src/main/java/io/github/apiscenariotester/cli/TemplateCommand.java`
- Test: `src/test/java/io/github/apiscenariotester/input/excel/ScenarioTemplateWriterTest.java`
- Test: `src/test/java/io/github/apiscenariotester/cli/TemplateCommandTest.java`

**Interfaces:**
- Consumes: `template --output <xlsx 경로>`
- Produces: `ScenarioTemplateWriter.write(Path output): void`, 명세의 다섯 시트와 헤더가 포함된 `.xlsx`

- [x] **Step 1: 실패하는 워크북 구조 테스트 작성**

임시 파일에 템플릿을 쓰고 Apache POI로 다시 열어 `common`, `result_format`, `scripts`, `sub_scenarios`, `main_scenarios` 시트 순서와 각 헤더의 리터럴 값을 검증한다.

- [x] **Step 2: 템플릿 테스트 실패 확인**

Run: `mvn -Dtest=ScenarioTemplateWriterTest test`
Expected: `ScenarioTemplateWriter`가 없어 컴파일 실패

- [x] **Step 3: 최소 템플릿 작성기 구현**

명세의 필수 시트, 헤더, 기본 `common` 및 `result_format` 값을 생성하고 부모 디렉터리가 있으면 그대로 사용한다. 같은 출력 파일이 이미 있으면 덮어쓰지 않고 `FileAlreadyExistsException`을 낸다.

- [x] **Step 4: 워크북 구조 테스트 통과 확인**

Run: `mvn -Dtest=ScenarioTemplateWriterTest test`
Expected: 모든 템플릿 테스트 통과

- [x] **Step 5: 실패하는 명령 통합 테스트 작성**

`template --output`을 실행해 파일 생성과 종료 코드 `0`을 확인하고, 기존 파일 대상 실행이 종료 코드 `2`와 오류 메시지를 반환하는지 확인한다.

- [x] **Step 6: 템플릿 명령 구현 및 테스트 통과 확인**

Run: `mvn -Dtest=TemplateCommandTest test`
Expected: 성공/기존 파일 오류 테스트 모두 통과

### Task 3: 대기 정책

**Files:**
- Create: `src/main/java/io/github/apiscenariotester/execution/WaitPattern.java`
- Create: `src/main/java/io/github/apiscenariotester/execution/WaitPolicy.java`
- Test: `src/test/java/io/github/apiscenariotester/execution/WaitPolicyTest.java`

**Interfaces:**
- Consumes: `WaitPattern`, `waitMinMs`, `waitMaxMs`, 선택 가능한 `RandomGenerator`
- Produces: `WaitPolicy.nextDelayMillis(): long`

- [x] **Step 1: 실패하는 고정/무작위 경계 테스트 작성**

고정 정책은 항상 최솟값을 반환하고, 무작위 정책은 닫힌 구간 `[min,max]` 안에서 최솟값과 최댓값을 생성할 수 있어야 한다. 음수, 역전 범위, FIXED의 서로 다른 min/max는 생성 시 거부한다.

- [x] **Step 2: 실패 확인**

Run: `mvn -Dtest=WaitPolicyTest test`
Expected: 대기 정책 타입이 없어 컴파일 실패

- [x] **Step 3: 최소 구현 작성 후 통과 확인**

Run: `mvn -Dtest=WaitPolicyTest test`
Expected: 모든 경계 및 검증 테스트 통과

### Task 4: 응답시간 통계

**Files:**
- Create: `src/main/java/io/github/apiscenariotester/report/TimingStatistics.java`
- Create: `src/main/java/io/github/apiscenariotester/report/StatisticsCalculator.java`
- Test: `src/test/java/io/github/apiscenariotester/report/StatisticsCalculatorTest.java`

**Interfaces:**
- Consumes: `StatisticsCalculator.calculate(List<Long> samples, double trimPercent, List<Integer> percentiles)`
- Produces: min, max, average, trimmedAverage, nearest-rank percentile Map을 가진 `TimingStatistics`

- [x] **Step 1: 실패하는 계산 테스트 작성**

표본 `[10,20,30,40,1000]`, trim 20%, percentile 50/90/99에 대해 min 10, max 1000, average 220, trimmed average 30, p50 30, p90/p99 1000을 리터럴로 검증한다. 작은 표본은 trimming 없이 전체 평균을 쓰고, 빈 표본 및 범위 밖 비율은 거부한다.

- [x] **Step 2: 실패 확인**

Run: `mvn -Dtest=StatisticsCalculatorTest test`
Expected: 계산 타입이 없어 컴파일 실패

- [x] **Step 3: 최소 구현 작성 후 통과 확인**

Run: `mvn -Dtest=StatisticsCalculatorTest test`
Expected: 정상/경계/오류 테스트 모두 통과

### Task 5: 문서 및 전체 검증

**Files:**
- Modify: `project.md`
- Create: `README.md`

**Interfaces:**
- Consumes: 구현된 CLI와 Maven 빌드
- Produces: 실행 방법, 현재 구현 범위, 구조 변경 이력

- [x] **Step 1: 문서 갱신**

README에 JDK 21 요구사항, `mvn test`, `mvn package`, `template` 실행 예제를 기록한다. `project.md` 구조 변경 이력에는 첫 기반 구현과 패키지 경계를 기록한다.

- [x] **Step 2: 전체 테스트**

Run: `mvn test`
Expected: 0 failures, 0 errors

- [x] **Step 3: 패키징과 CLI 스모크 테스트**

Run: `mvn package` 후 `java -jar target/api-scenario-tester-0.1.0-SNAPSHOT.jar --help`
Expected: 두 명령 종료 코드 0, 도움말에 공개 명령 4개 표시
