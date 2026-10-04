# API Scenario Tester 기술 명세

## 1. 문서 정보

- 문서 상태: 설계 승인본
- 기준일: 2026-09-18
- 구현 현황 분석일: 2026-10-05 (17절 참조)
- 산출물: Spring Boot 기반 executable JAR
- 주 입력: YAML 시나리오와 YAML 런타임 설정
- 주 출력: YAML 실행 결과

## 2. 목적

API Scenario Tester는 Excel에 정의된 REST API 호출을 지정한 순서대로 실행하는 CLI 프로그램이다. 하나의 실행 컨텍스트에서 시나리오를 순차 호출하며 병렬 실행은 지원하지 않는다. 전처리, 응답 필터링, 검증, 후처리는 Apache Commons JEXL 스크립트로 확장한다.

다음 기능을 제공한다.

- Excel 시나리오 템플릿 생성
- YAML 시나리오 파싱 및 사전 검증
- Excel과 YAML 시나리오의 양방향 변환
- 단일 실행 컨텍스트의 순차 호출과 반복 실행
- 고정 또는 범위 내 무작위 호출 대기
- JEXL 기반 전처리, 필터, 검증, 후처리
- 현재 실행의 협력적 중단
- Postman Collection 및 JMeter JMX 가져오기
- 응답시간과 성공률 통계를 포함한 YAML 결과 생성

## 3. 범위

### 3.1 포함 범위

- HTTP/HTTPS 기반 REST API
- `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`
- 문자열 기반 요청 본문과 헤더
- JSON 응답을 포함한 문자열 응답 본문
- Postman Collection v2.0/v2.1의 기본 HTTP 요청
- JMeter HTTP Request Sampler와 HTTP Header Manager
- JEXL 스크립트의 명시적 변수 접근 및 실행 제어
- 실행 결과 YAML 보고서

### 3.2 제외 범위

- GUI
- 다중 세션 및 HTTP 호출의 병렬 실행
- 분산 부하 생성
- WebSocket, gRPC, SOAP 전용 기능
- Postman의 JavaScript 런타임 호환 실행
- JMeter 플러그인 및 모든 Controller 의미의 완전한 재현
- 임의 Java 클래스 생성, 파일 시스템, 프로세스 실행을 허용하는 JEXL 기능

변환기가 지원하지 않는 Postman 또는 JMeter 요소는 누락시키지 않고 변환 경고에 기록한다.

## 4. 기술 기준

- Java 21
- Spring Boot 4.1.1
- Maven
- Apache Commons JEXL 3.7.0
- Apache POI 5.5.1
- Jackson Dataformat YAML
- Spring `RestClient`
- Picocli 기반 명령행 인터페이스
- JUnit 5, AssertJ, OkHttp MockWebServer

버전은 구현 시점의 최신 안정 버전으로 고정하며, 이후 변경 시 이 문서의 변경 이력에 기록한다.

## 5. CLI 명세

```text
java -jar api-scenario-tester.jar template --output scenario.xlsx
java -jar api-scenario-tester.jar validate --scenario scenario.yml --config runtime.yml
java -jar api-scenario-tester.jar run --scenario scenario.yml --config runtime.yml
java -jar api-scenario-tester.jar convert postman --input collection.json --output scenario.xlsx
java -jar api-scenario-tester.jar convert jmeter --input test.jmx --output scenario.xlsx
java -jar api-scenario-tester.jar convert excel --input scenario.xlsx --output scenario.yml
java -jar api-scenario-tester.jar convert yaml --input scenario.yml --output scenario.xlsx
```

- `template`: 유효한 빈 Excel 템플릿을 생성한다.
- `validate`: 외부 호출 없이 Excel과 YAML의 구조 및 참조 관계를 검증한다.
- `run`: 검증 후 시나리오를 실행하고 결과 YAML을 생성한다.
- `convert postman`: Postman Collection을 Excel 형식으로 변환한다.
- `convert jmeter`: JMeter JMX를 Excel 형식으로 변환한다.
- `convert excel`: Excel 시나리오를 실행 기준 YAML로 변환한다.
- `convert yaml`: YAML 시나리오를 Excel 편집 형식으로 변환한다.
- 정상 완료는 종료 코드 `0`, 시나리오 실패는 `1`, 입력 또는 실행 구성 오류는 `2`를 반환한다.

## 6. 입력 파일

### 6.1 런타임 YAML

환경별 값과 Excel 밖에서 관리할 전역 변수를 정의한다.

```yaml
globals:
  environment: local
  tenantId: sample
  clientSecret: "${CLIENT_SECRET}"
hosts:
  local-api:
    baseUrl: "http://localhost:8080"
    connectTimeoutMs: 3000
    readTimeoutMs: 10000
```

`${NAME}` 형식은 운영체제 환경 변수로 치환한다. 정의되지 않은 환경 변수는 실행 전 검증 오류로 처리한다. YAML에 정의한 호스트는 Excel `common` 시트의 같은 이름 항목을 덮어쓴다. 이를 통해 주소와 민감정보를 시나리오 파일에서 분리한다.

### 6.2 시나리오 YAML

실행 엔진은 Excel을 직접 읽지 않고 다음 버전 구조의 YAML만 입력으로 사용한다.

```yaml
version: 1
common:
  sessions: "1"
  iterations: "1"
resultFormat:
  output: results.yml
scripts: []
subScenarios: []
mainScenarios:
  - order: "1"
    name: health
    enabled: "true"
    host: api
    method: GET
    path: /health
    expectedStatus: 200-299
```

Map과 행의 순서를 보존하며 값은 Excel 셀과의 무손실 왕복을 위해 문자열로 저장한다. 지원하지 않는 `version`은 입력 오류다.

### 6.3 Excel 시트

Excel은 시나리오 작성·검토·외부 도구 변환을 위한 교환 형식이다. 실행 전 `convert excel`로 YAML로 변환한다.

시트 이름은 고정하며 첫 번째 행은 컬럼명이다. 빈 행은 무시한다. 목록 컬럼은 쉼표로 구분하고 JSON 객체 컬럼은 유효한 JSON 문자열이어야 한다.

#### `common`

키-값 구조로 공통 실행 설정을 정의한다.

| key | value | 필수 | 설명 |
|---|---|---:|---|
| `sessions` | 정수 | 아니오 | 기존 파일 호환용. 생략 또는 `1`만 허용하며 병렬 실행을 의미하지 않음 |
| `iterations` | 정수 | 예 | 메인 시나리오 반복 횟수, 1 이상 |
| `waitPattern` | `FIXED` 또는 `RANDOM_RANGE` | 예 | API 호출 사이 대기 방식 |
| `waitMinMs` | 0 이상 정수 | 예 | 고정 대기 또는 범위 최솟값 |
| `waitMaxMs` | 0 이상 정수 | 예 | 범위 최댓값 |
| `continueOnFailure` | boolean | 예 | 호출 실패 후 다음 행 진행 여부 |

호스트는 `host.<name>.baseUrl`, `host.<name>.connectTimeoutMs`, `host.<name>.readTimeoutMs` 키로 정의할 수 있다.

#### `result_format`

| key | value | 필수 | 설명 |
|---|---|---:|---|
| `output` | 경로 | 예 | 결과 YAML 파일 경로 |
| `includeCallDetails` | boolean | 예 | 호출별 상세 결과 포함 여부 |
| `percentiles` | 쉼표 구분 숫자 | 예 | 예: `50,90,95,99` |
| `trimPercent` | 0 이상 50 미만 숫자 | 예 | 상·하위 제외 비율, 기본값 5 |

#### `scripts`

| 컬럼 | 필수 | 설명 |
|---|---:|---|
| `id` | 예 | 워크북 내 고유 스크립트 ID |
| `phase` | 예 | `PRE`, `FILTER`, `VALIDATE`, `POST` |
| `body` | 예 | JEXL 스크립트 본문 |

#### `sub_scenarios`

| 컬럼 | 필수 | 설명 |
|---|---:|---|
| `subsetId` | 예 | 단위 시나리오 ID |
| `order` | 예 | subset 내부 실행 순서 |
| `name` | 예 | 호출 이름 |
| `host` | 예 | 대상 호스트 이름 |
| `method` | 예 | HTTP 메서드 |
| `path` | 예 | 호출 경로 또는 JEXL 템플릿 |
| `preScripts` | 아니오 | 실행할 `PRE` 스크립트 ID 목록 |
| `headers` | 아니오 | JSON 객체 |
| `body` | 아니오 | 요청 본문 |
| `expectedStatus` | 예 | 단일 코드 또는 `200-299` 범위 |
| `expectedMaxMs` | 아니오 | 최대 응답시간 |
| `filterScripts` | 아니오 | `FILTER` 스크립트 ID 목록 |
| `validationScripts` | 아니오 | `VALIDATE` 스크립트 ID 목록 |
| `postScripts` | 아니오 | `POST` 스크립트 ID 목록 |

같은 `subsetId`의 행은 `order` 오름차순으로 실행한다.

#### `main_scenarios`

| 컬럼 | 필수 | 설명 |
|---|---:|---|
| `order` | 예 | 메인 실행 순서 |
| `name` | 예 | 호출 이름 |
| `enabled` | 예 | 실행 여부 |
| `host` | 예 | 대상 호스트 이름 |
| `method` | 예 | HTTP 메서드 |
| `path` | 예 | 호출 경로 또는 JEXL 템플릿 |
| `preSubsets` | 아니오 | 사전 실행할 subset ID 목록 |
| `preScripts` | 아니오 | 전처리 스크립트 ID 목록 |
| `headers` | 아니오 | JSON 객체 |
| `body` | 아니오 | 요청 본문 |
| `expectedStatus` | 예 | 단일 코드 또는 범위 |
| `expectedMaxMs` | 아니오 | 최대 응답시간 |
| `filterScripts` | 아니오 | 응답 필터 스크립트 ID 목록 |
| `validationScripts` | 아니오 | 검증 스크립트 ID 목록 |
| `postScripts` | 아니오 | 후처리 스크립트 ID 목록 |

## 7. 아키텍처

단일 executable JAR 안에서 책임별 패키지를 분리한다.

```text
cli
 ├─ template / validate / run / convert 명령
input
 ├─ Excel 읽기·쓰기
 ├─ YAML 읽기
 └─ 입력 검증 및 실행 모델 조립
execution
 ├─ 단일 실행 컨텍스트
 ├─ 시나리오 순차 실행
 ├─ 대기 정책
 └─ 중단 상태 관리
http
 └─ REST 요청 및 응답 캡처
script
 ├─ JEXL 엔진
 ├─ 컨텍스트 생성
 └─ 스크립트 단계별 실행
conversion
 ├─ Postman 변환
 └─ JMeter 변환
report
 ├─ 통계 계산
 └─ YAML 결과 출력
domain
 └─ 불변 실행 모델과 결과 모델
```

입력 계층은 실행 기준 YAML을 도메인 실행 모델로 변환한다. Excel은 YAML과 양방향 변환되는 교환 형식이며 실행 계층에서 직접 읽지 않는다. 실행 계층은 파일 형식에 의존하지 않으며, HTTP와 스크립트 실행은 인터페이스로 주입받는다.

## 8. 실행 흐름

1. CLI 인자를 검증한다.
2. 시나리오 YAML과 런타임 YAML을 읽는다.
3. 환경 변수를 치환한다.
4. 값 범위, 중복 ID, 참조 무결성, 스크립트 phase를 검증한다.
5. JEXL 스크립트를 미리 컴파일하여 문법 오류를 검출한다.
6. 단일 executor 컨텍스트와 쿠키 저장소를 만든다.
7. 설정된 iteration 횟수만큼 메인 시나리오를 반복한다.
8. 각 메인 행에서 subset, 전처리, HTTP 호출, 필터, 검증, 후처리를 순서대로 수행한다.
9. 호출 사이에 지정한 대기 정책을 적용한다.
10. 결과를 모아 통계를 계산하고 YAML 파일을 원자적으로 교체한다.
11. 실패 유무에 따라 프로세스 종료 코드를 결정한다.

모든 호출은 순차적으로 실행한다. subset과 main 호출은 동일 executor 변수와 쿠키 저장소를 사용한다. 전역 설정은 읽기 전용이며 병렬 실행을 위한 스레드 풀은 구성하지 않는다.

## 9. JEXL 실행 환경

모든 스크립트는 다음 최상위 변수를 사용할 수 있다.

| 변수 | 내용 | 변경 가능 여부 |
|---|---|---:|
| `global` | YAML 및 공통 전역 설정 | 아니오 |
| `executor` | 현재 실행 컨텍스트의 변수 Map | 예 |
| `scenario` | 현재 호출 정의 | 아니오 |
| `request` | 현재 요청의 path, headers, body | 예 |
| `response` | status, headers, body, elapsedMs | 아니오 |
| `control` | 실행 중단 API | 제한적 |

`response`는 HTTP 호출 이후 단계에서만 제공한다. 응답 본문은 원문 문자열이며 JSON인 경우 `response.json`으로 파싱된 객체도 제공한다.

- `control.stop(reason)`: 현재 실행에 협력적 중단 신호를 보낸다.
- `VALIDATE` 스크립트는 boolean을 반환해야 하며 `false`는 호출 실패다.
- `FILTER`와 `POST` 스크립트는 반환값을 요구하지 않으며 `executor`에 값을 저장할 수 있다.
- path, header 값, body의 `${expression}`은 JEXL 표현식 결과로 치환한다.

JEXL 3.7의 `SECURE` 권한과 안전한 기능 기본값을 유지한다. 애플리케이션이 제공하는 컨텍스트 객체 외의 반사 접근, 클래스 생성, 파일 및 프로세스 접근은 허용하지 않는다. 전체 실행 중단은 스크립트가 프로세스를 강제 종료하는 방식이 아니라 공유 중단 토큰을 설정하는 방식으로 처리한다.

## 10. HTTP 실행

- 실행 컨텍스트의 쿠키 저장소를 유지하여 인증 흐름을 지원한다.
- subset과 main 호출은 같은 쿠키 저장소 및 executor 변수를 사용한다.
- 헤더와 본문은 템플릿 치환 후 전송한다.
- 응답 본문은 보고서 크기를 제한하기 위해 설정된 최대 길이까지만 상세 결과에 보존할 수 있다.
- 연결 및 읽기 제한시간은 호스트별로 적용한다.
- 기본 자동 재시도는 수행하지 않는다. 비멱등 요청의 중복 전송을 방지하기 위함이다.

## 11. 실패 및 중단 정책

### 11.1 실행 전 오류

다음은 종료 코드 `2`로 실행을 거부한다.

- 필수 시트 또는 컬럼 누락
- 잘못된 숫자, enum, JSON 값
- 중복 script ID
- 존재하지 않는 host, script, subset 참조
- script phase 불일치
- JEXL 컴파일 오류
- 순환 subset 참조
- 정의되지 않은 환경 변수
- `sessions`가 `1`이 아닌 병렬 실행 설정

Excel 관련 오류는 시트명, 행, 컬럼을 포함한다.

### 11.2 호출 실패

다음은 호출 실패로 기록한다.

- 네트워크 또는 제한시간 오류
- 예상 상태 코드 불일치
- `expectedMaxMs` 초과
- 필터, 검증, 후처리 스크립트 예외
- 검증 스크립트의 `false` 반환

`continueOnFailure=false`이면 현재 실행을 중단한다. `true`이면 후처리 가능 범위까지 기록한 후 다음 메인 행으로 진행한다.

### 11.3 명시적 중단

- `stop`: 새 호출 시작을 막고 실행 중 호출이 반환되면 현재 실행을 `STOPPED`로 종료한다.
- 중단 사유는 결과 보고서에 기록한다.

## 12. 결과 YAML

```yaml
summary:
  startedAt: "2026-09-18T10:00:00+09:00"
  finishedAt: "2026-09-18T10:01:00+09:00"
  durationMs: 60000
  iterations: 40
  totalCalls: 120
  succeeded: 118
  failed: 2
  stopped: false
timing:
  minMs: 12
  maxMs: 980
  averageMs: 105.4
  trimmedAverageMs: 92.1
  percentiles:
    p50: 80
    p90: 180
    p95: 250
    p99: 750
scenarios:
  - name: create-order
    totalCalls: 40
    succeeded: 39
    failed: 1
    averageMs: 140.2
failures:
  - iteration: 4
    scenario: create-order
    reason: "expected status 201 but received 500"
calls: []
warnings: []
```

`trimmedAverageMs`는 정렬한 응답시간 표본의 상·하위 `trimPercent`를 각각 제외한 산술 평균이다. 표본 수가 너무 적어 양쪽에서 하나 이상 제외할 수 없으면 전체 평균과 동일하다. percentile은 nearest-rank 방식으로 계산한다.

## 13. 변환 규칙

### 13.1 Postman

- Collection item의 탐색 순서를 main `order`로 사용한다.
- method, URL path, headers, raw body를 변환한다.
- Collection variable과 environment-independent variable은 템플릿 표현식으로 보존한다.
- 사전/사후 JavaScript와 인증 helper는 실행 코드로 변환하지 않고 경고로 기록한다.
- 기본 예상 응답은 `200-299`, 최대 응답시간은 비어 있는 값으로 생성한다.

### 13.2 JMeter

- Test Plan 탐색 순서의 HTTP Request Sampler를 main 행으로 변환한다.
- HTTP Header Manager를 가장 가까운 sampler에 적용한다.
- Thread Group의 loop count를 `iterations`로 변환한다. thread 수가 `1`이 아니면 병렬 실행을 지원하지 않는다는 경고를 기록하고 실행 설정은 단일 컨텍스트로 제한한다.
- Constant Timer는 `FIXED`, Uniform Random Timer는 가능한 경우 `RANDOM_RANGE`로 변환한다.
- 복잡한 Controller, assertion, extractor, plugin 요소는 경고로 기록한다.

두 변환기는 결과 Excel과 함께 `<output-name>.warnings.yml`을 생성한다. 경고가 없으면 파일을 생성하지 않는다.

## 14. 테스트 전략

- Excel 셀 변환, 템플릿 생성, 위치 기반 오류 단위 테스트
- YAML 환경 변수 치환과 host 덮어쓰기 테스트
- 참조 무결성 및 JEXL 사전 컴파일 테스트
- 고정/무작위 대기 정책 테스트
- 단일 실행 컨텍스트와 `sessions` 설정 제한 테스트
- subset과 main 실행 순서 테스트
- `stop` 협력적 중단 테스트
- MockWebServer를 이용한 쿠키, 헤더, 본문, 상태, timeout 통합 테스트
- trimmed mean과 percentile 경계값 테스트
- Postman/JMeter fixture 변환 테스트
- executable JAR을 실행하는 CLI smoke test

모든 기능은 실패하는 테스트를 먼저 확인한 다음 최소 구현으로 통과시키는 방식으로 개발한다.

## 15. 문서 및 배포 산출물

- `README.md`: 설치, Excel 작성법, CLI 예제, JEXL 예제, 결과 해석
- `project.md`: 본 기술 명세 및 구조 변경 이력
- `scenario-template.xlsx`: `template` 명령으로 재생성 가능한 예제
- `target/api-scenario-tester-<version>.jar`: 실행 가능한 최종 JAR

## 16. 구조 변경 이력

| 날짜 | 변경 내용 |
|---|---|
| 2026-10-05 | 사용자 요구에 따라 병렬 실행을 지원 범위에서 제외. 단일 실행 컨텍스트, 순차 호출·반복 실행, 중단 정책, 결과 형식과 후속 개발 계획을 수정. 기존 `sessions`는 파일 호환용으로만 유지하고 생략 또는 `1`을 허용하는 검증을 목표로 함. 코드 변경 없음 |
| 2026-10-05 | 소스·테스트·실행 JAR 분석 결과를 17절에 추가. 설계와 구현 범위, 재현된 문제, 빌드 환경 및 후속 우선순위 기록. 소스 코드와 패키지 구조 변경 없음 |
| 2026-09-18 | 실행 기준 시나리오 형식을 Excel에서 버전 1 YAML로 변경. Excel→YAML과 YAML→Excel 무손실 양방향 변환 및 CLI 추가 |
| 2026-09-18 | JMeter JMX 샘플 변환 추가. Thread Group, HTTP Request Sampler, Header Manager, Constant/Uniform Random Timer를 Excel 설정과 시나리오로 변환하고 미지원 Assertion·Extractor를 경고 YAML로 출력 |
| 2026-09-18 | Postman Collection v2.0/v2.1 샘플 변환 추가. 중첩 item의 HTTP 요청을 Excel `main_scenarios`로 변환하고 변수 표현식을 보존하며 미지원 스크립트·인증 helper를 경고 YAML로 출력 |
| 2026-09-18 | Java 21/Maven/Spring Boot 기반 프로젝트 골격 구성. `cli`, `input.excel`, `execution`, `report` 패키지와 Excel 템플릿 생성, 대기 정책, 응답시간 통계 계산의 첫 구현 추가 |
| 2026-09-18 | 초기 기술 명세 작성. Spring Boot CLI, Excel/YAML 입력, JEXL 실행, 병렬 세션, 변환기, YAML 보고서 구조 정의 |

## 17. 프로젝트 분석 — 2026-10-05

### 17.1 종합 판단

현재 프로젝트는 API 실행 도구를 목표로 하는 **시나리오 변환 및 기반 유틸리티 구현 단계**다. Excel 템플릿, 외부 형식 가져오기, Excel/YAML 변환, 대기시간 산출, 통계 계산은 구현되어 있다. 실제 API 호출과 실행 검증은 아직 구현되지 않았다. 병렬 실행은 지원 범위에서 제외한다. 앞선 2~15절은 목표 설계를 포함하므로 현재 제공 기능과 구분해서 읽어야 한다.

메인 Java 파일은 22개, 테스트 Java 파일은 14개이며 테스트는 24개다. 현재 디렉터리에는 Git 저장소가 없어 브랜치·커밋·변경 diff를 확인할 수 없다.

### 17.2 실제 구조와 데이터 흐름

```text
pom.xml                         Java 21, Spring Boot 4.1.1, executable JAR
src/main/java/io/github/apiscenariotester/
  ApiScenarioTesterApplication  Picocli 실행 및 종료 코드 전달
  cli/                          template, convert, run/validate 자리표시자
  input/excel/                  5개 시트의 Excel 템플릿 생성
  scenario/                     불변 ScenarioDocument, Excel/YAML codec
  conversion/                   공통 변환 결과 및 HTTP 요청 모델
    postman/                    Collection reader, Excel/warnings writer
    jmeter/                     JMX reader, Excel/warnings writer
  execution/                    WaitPattern, WaitPolicy
  report/                       TimingStatistics, StatisticsCalculator
src/test/java/                  CLI 및 개별 기능 테스트
samples/                        Postman, JMeter, YAML 변환 예제
docs/superpowers/plans/          기존 구현 계획
```

현재 데이터 흐름은 다음과 같다.

```text
Postman JSON / JMeter JMX → ConversionResult → Excel + 선택적 warnings.yml
Excel ↔ ScenarioDocument ↔ YAML
대기 정책 / 통계 계산: 아직 실행 파이프라인과 연결되지 않은 독립 유틸리티
```

Spring Boot는 의존성 관리와 실행 JAR 패키징에 사용된다. 진입점은 `SpringApplication.run`을 호출하지 않고 `RootCommand`를 직접 생성한다. 따라서 현재는 Spring 컨텍스트나 DI를 사용하는 실행 구조가 아니다. 7절의 `http`, `script`, `domain` 패키지도 아직 없다.

### 17.3 기능별 구현 상태

| 기능 | 현황 | 근거 및 제한 |
|---|---|---|
| CLI 및 종료 코드 | 부분 구현 | 템플릿·변환은 정상 0, 주요 입력 오류 2. 실제 시나리오 실패 1은 미구현 |
| Excel 템플릿 | 구현 | 5개 시트, 기본 설정, 헤더 서식, 기존 파일 덮어쓰기 방지 |
| Excel/YAML 변환 | 구현, 보존 범위 제한 | 버전 1, 문자열 기반 Map/List. 일부 값 유실은 17.4절 참조 |
| Postman 가져오기 | 기본 요청 구현 | 중첩 요청 순서, method, URL, header, raw body, 변수 표현식 변환 |
| JMeter 가져오기 | 제한된 구조 구현 | 첫 Thread Group·Timer, sampler 및 sampler 하위 Header Manager 중심. 현재 thread 수를 `sessions`로 보존하므로 단일 실행 명세에 맞춘 경고·설정 보정 필요 |
| 대기 정책 | 구현 | 고정/범위 무작위 시간 산출 및 범위 검증. 실제 sleep·중단 처리 없음 |
| 응답시간 통계 | 구현 | 최소·최대·평균·trimmed mean·nearest-rank percentile. 빈 표본은 예외 |
| `validate` / `run` | 자리표시자 | 입력 옵션과 실제 처리 없이 0 반환 |
| 런타임 YAML | 미구현 | 환경 변수 치환, host 덮어쓰기, 참조 검증 없음 |
| HTTP·JEXL 실행 | 미구현 | HTTP/JEXL 실행 의존성 및 실행 클래스 없음 |
| 병렬 실행 | 지원 제외 | 단일 실행 컨텍스트에서 순차 호출만 지원하도록 설계 |
| 결과 YAML | 미구현 | 변환 경고 YAML과 구별 필요. 통계 계산만 존재 |

### 17.4 확인된 문제와 우선순위

아래에서 실행 재현과 소스 검토 결과를 구분한다. 분석 과정에서는 제품 코드를 수정하지 않았다.

| 우선순위 | 항목 | 영향·근거 | 권장 조치 |
|---|---|---|---|
| 높음 | `run`·`validate`의 무처리 성공 | 실행 재현: 입력 없이 각 명령이 0 반환. 자동화가 실행·검증 완료로 오인 가능. `cli/RootCommand.java`의 placeholder | 실제 기능 전까지 미구현 상태를 명시하고 실패 코드 반환; 구현 시 필수 YAML 옵션과 검증 연결 |
| 높음 | Postman 미지원 요소의 경고 누락 | 실행 재현: `urlencoded` body 요청을 변환해도 warnings 파일이 생성되지 않음. 소스상 raw 이외 본문은 빈 문자열로 처리. Collection event와 폴더 event/auth도 경고 탐색에서 빠짐. `conversion/postman/PostmanCollectionReader.java` | 미지원 body·script·auth 및 입력 구조를 검사하고 경고에 출처 기록 |
| 높음 | JMeter 계층 의미의 단순화 | 소스 검토: 전체 sampler를 평탄화하고 첫 Thread Group/Timer만 사용. `enabled` 검사 없음, 상위 Header Manager 상속 없음. 경고 대상은 세 종류만 열거. `conversion/jmeter/JMeterTestPlanReader.java` | hashTree 범위와 활성 상태를 따라 탐색; 여러 그룹·Controller·미지원 요소를 경고 또는 거부 |
| 중간 | Excel 왕복 보존 범위 불명확 | 실행 재현: YAML 행의 `body: ""`와 `customField`가 왕복 후 사라짐. reader는 빈 셀을 생략하고 writer는 고정 헤더만 출력. `scenario/ScenarioExcelCodec.java` | 알려진 컬럼의 의미 보존과 임의 문서 무손실을 구분; 알 수 없는 컬럼은 거부/경고 또는 명시적 보존 |
| 중간 | 입력 오류 위치·구조 검사 부족 | 소스 검토: 필수 시트·헤더의 null 검사 없음, key/value 중복은 덮어씀. 행·컬럼 위치를 포함한 검증 없음 | codec에서 구조 오류를 검사하고 시트·행·컬럼을 포함한 오류 반환 |
| 중간 | 결과 파일의 원자성 부족 | 소스 검토: 템플릿 생성 뒤 Excel을 truncate하여 작성. 예외 시 빈/부분 파일 가능. Excel과 warnings가 각각 저장되어 일부만 생성될 수 있음 | 임시 파일에서 작성·검증 후 이동, 기존 파일 보호와 실패 정리 정책 수립 |
| 중간 | 실행 환경 재현성 | 실행 확인: 기본 Java 17, README 예시 JDK 경로는 현재 환경에 없음. 기본 Maven mirror는 연결 시간 초과 | Java 21 안내 정비, Maven Wrapper/CI와 저장소 설정 지침 검토 |

JMeter property reader는 `stringProp`과 `boolProp`만 검색한다. `intProp` 등 다른 표현, 다중 raw body argument, 여러 timer의 결합도 현재 코드에서는 처리되지 않으므로 추가 fixture가 필요하다. JMeter `${userId}`는 그대로 보존되지만 Postman 변수는 `${global.userId}`로 바뀐다. 실행 엔진을 구현하기 전에 외부 도구 변수의 범위와 매핑 규칙을 확정해야 한다.

### 17.5 검증 결과와 한계

- 기본 `java -version` 및 `mvn -version`: Java 17.0.10, Maven 3.9.3.
- 설치된 `C:/Develop/app/java/jdk-21.0.3`을 검증 프로세스에 적용했다.
- 기본 설정의 `mvn package`: Nexus mirror 연결 시간 초과로 의존성 해석 실패. 소스 컴파일 실패로 분류하지 않는다.
- 임시 Maven settings에서 Maven Central을 지정한 오프라인 재시도: Picocli 캐시의 저장소 ID 불일치로 실패.
- 동일 임시 설정의 온라인 `mvn package`: **BUILD SUCCESS**, 테스트 **24개, 실패 0, 오류 0, 건너뜀 0**, executable JAR 생성 성공.
- 실제 JAR `--help` 성공. `run`·`validate`의 무처리 성공 반환과 Postman 경고 누락, YAML 왕복 유실을 재현했다.
- 테스트와 재현은 외부 API를 호출하지 않았다. 실제 HTTP, JEXL 보안, 쿠키, stop은 미구현이므로 검증되지 않았다. 병렬 실행은 지원 대상이 아니다.

재현 산출물은 `target/analysis-353e9ccb7a524dec9607cce1451cb564/`에 보관했다. Maven 사용자·전역 설정과 시스템 Java 설정은 수정하지 않았으며, 임시 settings는 해당 검증 명령에만 지정했다.

현재 테스트는 정상 변환, 템플릿 덮어쓰기 거부, 대기 정책, 통계 및 JMX DOCTYPE 거부 등을 다룬다. 필수 시트·헤더 누락, 중복 키, 미지원 version의 CLI 처리, 빈 값·추가 컬럼 보존, Postman 미지원 본문/폴더 스크립트, JMeter 상속/비활성 요소/다중 그룹, 파일 작성 중 실패는 테스트 보강 대상이다. 24개 테스트 통과는 현재 테스트 범위에 대한 결과이며 전체 목표 명세 충족을 의미하지 않는다.

### 17.6 후속 개발 순서

1. `run`·`validate`의 성공 오인 방지, 변환 누락 경고와 입력 구조 검사 보강.
2. YAML 런타임 설정과 타입이 있는 실행 모델 구성. 값 범위·host/script/subset 참조 검증, `sessions`의 생략 또는 `1` 제한 및 `validate` 구현. JMeter 변환에서 다중 thread 경고와 단일 실행 설정 보정.
3. HTTP 순차 실행, 요청 치환, timeout·쿠키·실패 정책 및 MockWebServer 테스트.
4. JEXL 컨텍스트·권한·사전 컴파일·단계 실행 구현.
5. 단일 executor의 반복 실행, 변수·쿠키 유지와 협력적 중단 구현.
6. 빈 실행 결과를 포함한 통계 집계와 결과 YAML 원자적 저장, 실제 JAR 통합 검증.

각 단계는 기존 문자열 교환 모델인 `ScenarioDocument`와 검증된 실행 모델의 책임을 분리하여 진행하는 것이 적합하다. 변환 writer 세 곳의 템플릿 생성·호스트 등록·열 위치 지정·경고 저장 중복도 공통 schema와 출력 처리로 정리할 수 있다.
