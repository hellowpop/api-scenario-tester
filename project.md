# API Scenario Tester 기술 명세

## 1. 문서 정보

- 문서 상태: 설계 승인본
- 기준일: 2026-09-18
- 구현 현황 분석일: 2026-10-05 (17절 참조)
- 산출물: Spring Boot 기반 executable JAR
- 주 입력: YAML 시나리오와 YAML 런타임 설정
- 주 출력: Excel 실행 결과 및 선택적 curl debug 로그 (YAML 보고서는 후속 기능)

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
- 응답시간과 성공률 통계를 포함한 Excel 결과 및 curl debug 로그 생성

## 3. 범위

### 3.1 포함 범위

- HTTP/HTTPS 기반 REST API
- `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`
- 문자열 기반 요청 본문과 헤더
- JSON 응답을 포함한 문자열 응답 본문
- Postman Collection v2.0/v2.1의 기본 HTTP 요청
- JMeter HTTP Request Sampler와 HTTP Header Manager
- JEXL 스크립트의 명시적 변수 접근 및 실행 제어
- 실행 결과 Excel 보고서와 선택적 curl debug 로그 (YAML 보고서는 후속 기능)

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
- HTTP 호출은 외부 `curl` 명령으로 수행
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
- `run`: 검증 후 curl로 시나리오를 순차 실행하고 결과 Excel을 생성한다. `--debug` 시 curl 입력·출력 로그와 파일 하이퍼링크를 추가한다.
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
  output: results.xlsx
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
| `output` | 경로 | 예 | 결과 Excel 파일 경로, 기본 `results.xlsx`. 이전 YAML 확장자는 `.xlsx`로 치환 |
| `includeCallDetails` | boolean | 예 | 후속 YAML 보고서용 설정. 현재 Excel은 모든 시도된 호출을 포함 |
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
 ├─ Excel 결과 출력 및 curl 로그 링크
 └─ YAML 결과 출력 (후속 기능)
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
10. 결과를 모아 통계를 계산하고 Excel 파일을 원자적으로 저장한다. debug 시 호출별 curl 로그 링크를 포함한다.
11. 실패 유무에 따라 프로세스 종료 코드를 결정한다.

모든 호출은 순차적으로 실행한다. subset과 main 호출은 동일 실행 컨텍스트와 쿠키 저장소를 사용한다. 런타임 설정 원본과 구분되는 실행용 `global` Map에 토큰·사용자 ID를 저장하거나 삭제할 수 있도록 설계한다. 병렬 실행을 위한 스레드 풀은 구성하지 않는다.

## 9. JEXL 실행 환경

모든 스크립트는 다음 최상위 변수를 사용할 수 있다.

| 변수 | 내용 | 변경 가능 여부 |
|---|---|---:|
| `global` | YAML 초기값 및 실행 중 추출한 전역변수 | 예 (실행용 Map에 저장·삭제; 원본 설정은 보존) |
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

JEXL 3.7의 `SECURE` 권한을 사용하고 `ExecutionControl`의 중단 메서드만 명시적으로 추가한다. Map 변경을 위해 global side effect를 허용하지만 최상위 컨텍스트 바인딩 교체는 금지한다. 클래스 생성, pragma, annotation, lambda, 반복문은 금지한다. 템플릿 엔진은 대입도 금지한다. 반사·파일·프로세스 접근은 권한으로 제한한다. 전체 실행 중단은 공유 중단 토큰으로 처리한다.

## 10. HTTP 실행

- API 호출은 curl 프로세스로 수행한다. 상세 debug 및 결과 Excel 규칙은 18절을 따른다.

- 실행 컨텍스트의 쿠키 저장소를 유지하여 인증 흐름을 지원한다.
- subset과 main 호출은 같은 쿠키 저장소 및 executor 변수를 사용한다.
- 헤더와 본문은 템플릿 치환 후 전송한다.
- 응답 본문은 보고서 크기를 제한하기 위해 설정된 최대 길이까지만 상세 결과에 보존할 수 있다.
- 연결 제한시간과 전체 호출 제한시간은 호스트별로 적용한다. curl의 전체 호출 제한은 연결·읽기 설정값의 합이며 idle-read timeout과는 구별한다.
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

## 12. 결과 YAML (후속 기능 설계)

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
- `target/api-scenario-tester.jar`: 버전명을 포함하지 않는 실행 가능한 최종 JAR. Maven `build.finalName`을 `api-scenario-tester`로 고정한다.

## 16. 구조 변경 이력

| 날짜 | 변경 내용 |
|---|---|
| 2026-10-05 | 기존 결과 보관 접미사를 UUID에서 timestamp(`yyyyMMdd_HHmmss_SSS`)로 변경. 같은 timestamp 충돌에는 일련번호를 추가하고 변환 결과·경고 파일에는 같은 timestamp 적용 |
| 2026-10-05 | 사용자 요청으로 기존 결과 보관 UUID를 접두사에서 접미사로 변경. 파일명 뒤·마지막 확장자 앞에 UUID를 추가하며 변환·run·경고 YAML에 동일 적용 |
| 2026-10-05 | subset 내부 curl 호출에 main과 동일한 debug 로그·Excel 링크 계약을 명시. SUBSET/preSubsets, 여러 행·반복·HTTP 및 POST 실패·비debug 동작의 실제 curl 회귀 테스트 추가 |
| 2026-10-05 | 변환 오류에 작업 단계·절대 입출력 경로·예외 종류를 포함하는 `cli/ConversionDiagnostics` 추가. 출력 준비 시 없는 상위 폴더를 생성하여 변환 대상 폴더 누락 오류 보완 |
| 2026-10-05 | `cli/OutputFiles` 추가. 변환 네 명령과 run에서 기존 결과를 같은 폴더의 UUID 접두사 파일로 보관한 뒤 새 결과 저장. Postman/JMeter의 경고 YAML도 보관하며 입력 오류 시 기존 파일 유지 |
| 2026-10-05 | `extract-token`의 누락·빈 응답 헤더 접근 오류를 재현. 샘플에 토큰 존재 검증과 명시적 중단 추가, JEXL 오류 소스명을 스크립트 ID로 지정하고 상태 코드·수신 헤더 이름 진단 추가 |
| 2026-10-05 | Apache Commons JEXL 3.7.0과 `script` 패키지 추가. 단계별 실행, 전역변수 저장·삭제, 동적 요청 치환, 응답 Map, `control.stop` 및 독립 SUBSET 호출을 curl 실행기에 연결. 샘플 실행 안내 갱신 |
| 2026-10-05 | 로그인·사용자 목록·상세·로그아웃 Excel 샘플을 `samples/auth/sample-scenario.xlsx`에 추가. 요청에 따라 실행용 global 변수의 저장·삭제와 독립 SUBSET 호출 행을 샘플 설계에 명시. 현재 스크립트 실행 미지원 제한을 기록 |
| 2026-10-05 | `http` curl 프로세스 계층과 순차 실행 계획·runner, 실제 `run`/`validate`, Excel `summary`/`calls` 보고서 추가. `--debug` 시 호출별 `curl/<uuid>.txt` 입력·출력 로그와 `curlLog` 파일 링크 생성. 결과 기본값 `results.xlsx`로 변경 |
| 2026-10-05 | Maven `build.finalName`을 `api-scenario-tester`로 지정하여 실행 JAR 이름을 `api-scenario-tester.jar`로 고정. README와 빌드 검증 명령의 산출물 경로 수정 |
| 2026-10-05 | 사용자 요구에 따라 병렬 실행을 지원 범위에서 제외. 단일 실행 컨텍스트, 순차 호출·반복 실행, 중단 정책, 결과 형식과 후속 개발 계획을 수정. 기존 `sessions`는 파일 호환용으로만 유지하고 생략 또는 `1`을 허용하는 검증을 목표로 함. 코드 변경 없음 |
| 2026-10-05 | 소스·테스트·실행 JAR 분석 결과를 17절에 추가. 설계와 구현 범위, 재현된 문제, 빌드 환경 및 후속 우선순위 기록. 소스 코드와 패키지 구조 변경 없음 |
| 2026-09-18 | 실행 기준 시나리오 형식을 Excel에서 버전 1 YAML로 변경. Excel→YAML과 YAML→Excel 무손실 양방향 변환 및 CLI 추가 |
| 2026-09-18 | JMeter JMX 샘플 변환 추가. Thread Group, HTTP Request Sampler, Header Manager, Constant/Uniform Random Timer를 Excel 설정과 시나리오로 변환하고 미지원 Assertion·Extractor를 경고 YAML로 출력 |
| 2026-09-18 | Postman Collection v2.0/v2.1 샘플 변환 추가. 중첩 item의 HTTP 요청을 Excel `main_scenarios`로 변환하고 변수 표현식을 보존하며 미지원 스크립트·인증 helper를 경고 YAML로 출력 |
| 2026-09-18 | Java 21/Maven/Spring Boot 기반 프로젝트 골격 구성. `cli`, `input.excel`, `execution`, `report` 패키지와 Excel 템플릿 생성, 대기 정책, 응답시간 통계 계산의 첫 구현 추가 |
| 2026-09-18 | 초기 기술 명세 작성. Spring Boot CLI, Excel/YAML 입력, JEXL 실행, 병렬 세션, 변환기, YAML 보고서 구조 정의 |

## 17. 프로젝트 분석 — 2026-10-05

이 절은 curl 실행 구현 이전의 분석 기록이다. 이후 구현 현황과 실행 규칙은 18절을 따른다.

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

## 18. curl 실행과 debug Excel 보고서

### 18.1 사용자 요구 및 실행 인터페이스

API 호출은 curl 명령으로 수행한다. 병렬 실행은 지원하지 않는다. `run --scenario scenario.yml [--config runtime.yml] [--output results.xlsx] [--debug]`를 사용한다. `validate`는 동일 입력을 외부 호출 없이 검사한다.

결과 출력은 Excel이며 기본값은 `results.xlsx`다. `--output`이 `resultFormat.output`보다 우선한다. 이전 시나리오의 `.yml`/`.yaml` 결과 경로는 같은 이름의 `.xlsx`로 해석한다. YAML 실행 결과 보고서는 후속 기능으로 남긴다.

### 18.2 curl 실행과 로그

`ProcessBuilder`에 인자를 개별 전달하여 셸 없이 curl을 시작한다. 본문은 UTF-8 stdin으로 전송한다. 요청마다 독립 curl 프로세스를 사용하며 쿠키 파일은 한 실행 컨텍스트 안에서 공유한다. 설정된 연결 제한시간과 전체 호출 제한시간을 적용하고 자동 재시도는 하지 않는다. Windows에서는 기본 `curl.exe`, 다른 OS에서는 `curl`을 사용한다. 런타임 설정의 `curlExecutable`로 실행 파일 경로를 지정할 수 있다.

`--debug`일 때 각 시도된 호출에 UUID를 부여하고 결과 Excel 부모 디렉터리 아래 `curl/<uuid>.txt`를 생성한다. 파일에는 명령 인자, stdin 요청 본문, curl 송수신 trace, stdout, stderr, 응답 헤더·본문, 프로세스 종료 코드를 기록한다. HTTP 실패·네트워크 실패·프로세스 시작 실패도 로그와 결과 행을 보존한다. debug가 아니면 지속 로그를 생성하지 않는다.

curl trace는 `--trace-ascii`와 `--trace-time`으로 수집한다. 원문 요청/응답 본문은 별도 섹션에도 기록하여 trace 줄 구분과 관계없이 확인할 수 있게 한다. 로그는 요청 헤더와 본문을 포함한다.

curl 옵션의 근거: [공식 curl 명령 설명](https://curl.se/docs/manpage.html#--trace-ascii). 연결 제한시간은 `connectTimeoutMs`, 전체 curl 호출 제한시간은 `connectTimeoutMs + readTimeoutMs`이며 별도의 idle-read timeout은 아니다.

### 18.3 Excel 결과

`summary` 시트는 총 호출·성공·실패 수 및 응답시간 통계를 기록한다. `calls` 시트는 iteration, order, name, method, URL, HTTP status, curl exit code, elapsedMs, success, error와 `curlLog` 컬럼을 갖는다. `curlLog`는 실제 Excel 파일 하이퍼링크이며 `curl/<uuid>.txt` 상대 주소를 사용한다. Excel과 curl 디렉터리를 함께 이동하면 링크가 유지된다. debug 미사용 시 컬럼은 유지하되 셀은 비워 둔다.

### 18.4 이번 구현 범위

버전 1 YAML과 선택적 런타임 config, host 덮어쓰기, 환경 변수·global 값 치환, main/subset 순차 호출, 반복, 상태 코드·최대 응답시간 검증, 대기 및 실패 정책을 연결한다. `sessions`는 생략 또는 `1`만 허용한다. JEXL 스크립트는 아직 구현하지 않으며 사용 시 외부 호출 전에 오류로 거부한다. 모든 요청을 사전 검증하여 입력 오류 때문에 일부 요청만 실행되는 상황을 방지한다.

현재 지원하는 치환은 `${global.name}` 형태의 단순 global 조회이며 임의 JEXL 표현식은 지원하지 않는다. `includeCallDetails`는 후속 YAML 보고서용 설정으로 유지하고 현재 Excel에는 모든 시도된 호출을 기록한다.

### 18.5 구현 검증

- 신규 CLI 통합 테스트는 로컬 HTTP 서버와 실제 curl로 실행했다. debug의 입력·응답 보존, 상대 파일 링크, 비debug 로그 미생성, 반복 순서·쿠키, HTTP 실패·timeout·curl 부재, 입력 사전 검증, 기존 결과 보호, HEAD 및 global 치환을 확인했다.
- 별도 코드 검토에서 헤더 NUL 사전 거부와 빈 헤더 전송 누락을 발견했다. 두 재현 테스트의 실패를 확인하고 수정 후 통과했다.
- JDK 21 및 임시 Maven Central 설정의 `mvn package`: 전체 테스트 **38개, 실패 0, 오류 0, 건너뜀 0**, 실행 JAR 빌드 성공.
- 실제 `api-scenario-tester.jar` smoke: 한글·공백 결과 경로에서 POST 호출 2회, 요청 원문 보존, UUID 로그 2개, Excel 파일 링크 2개 확인. 검증 산출물은 `target/curl-smoke-96a5878cfc6546f5b36186b715756b72/`에 보관.
- 현재 `run`/`validate`는 실제 동작한다. HTTP 호출은 curl 프로세스로 수행하며 JEXL·스크립트 중단 API·YAML 결과 보고서는 후속 기능으로 남는다.

## 19. 로그인·사용자 조회 샘플 Excel

### 19.1 파일과 흐름

`samples/auth/sample-scenario.xlsx`는 기존 다섯 시트와 설명용 `guide` 시트를 사용한다. 메인 시나리오는 다음 네 행이며 추가 HTTP 요청 없이 서브호출을 독립 행으로 표현한다.

| order | name | method | path | postScripts |
|---|---|---|---|---|
| 1 | call-login | SUBSET | login | 없음 (서브 행에서 실행) |
| 2 | user-list | GET | /api/user/list | extract-user-id |
| 3 | user-detail | GET | /api/user/${global['user-id']}/get | 없음 |
| 4 | call-logout | SUBSET | logout | 없음 (서브 행에서 실행) |

`method=SUBSET`은 HTTP 메서드가 아닌 서브호출 표기이며 `path`가 호출 대상 `subsetId`다. 기존 Excel 컬럼을 유지하여 Excel/YAML 변환 시 이 표기를 보존한다. 20절의 실행기에서 이를 순차 HTTP 단계로 확장한다.

### 19.2 스크립트와 데이터 전달

- `extract-token`: login의 HTTP 200–299 응답 후 `response.headers['x-token'][0]`을 `global['x-token']`에 저장한다.
- `remove-token`: logout의 HTTP 200–299 응답 후 `global.remove('x-token')`으로 키를 삭제한다.
- `extract-user-id`: 목록 조회의 HTTP 200–299 응답 후 `response.json['serch']['list'][0]['id']`를 `global['user-id']`에 저장한다.

세 스크립트는 `POST` phase이며 body 안에서 정상 상태 코드를 확인한다. `response.headers`는 소문자 헤더명과 값 목록을 제공하는 구조를 전제로 한다. 토큰은 사용자 목록·상세·로그아웃 요청의 `x-token` 헤더에 전달된다. `/serch/list`는 사용자가 지정한 표기를 그대로 사용했다. 예시 응답은 `{"serch":{"list":[{"id":"user-001"}]}}`다.

하이픈이 있는 Map 키는 bracket 표기인 `global['x-token']`, `global['user-id']`로 접근한다. 문법 근거는 [Apache Commons JEXL 공식 문법](https://commons.apache.org/proper/commons-jexl/reference/syntax.html)이다.

### 19.3 가정과 현재 실행 제한

호스트는 `http://localhost:8080`, 로그인·로그아웃은 POST, 목록·상세는 GET으로 가정했다. 로그인 body·인증 필드는 요청에 규격이 없어 비워 두었다. 실제 API 주소와 로그인 입력은 사용 환경에 맞게 작성해야 한다.

이 샘플은 20절의 JEXL 런타임으로 실행할 수 있다. 실제 API에 맞게 호스트와 인증 입력을 설정한 후 Excel을 YAML로 변환한다. 각 시트의 시각적 확인과 저장된 셀·참조 검사를 수행했다. 실제 JAR의 Excel→YAML→Excel 왕복 변환에서 다섯 시나리오 시트의 값과 스크립트 참조가 보존됨을 확인했다. 설명용 `guide` 시트는 시나리오 변환 대상이 아니다.

## 20. JEXL 런타임 구현 (2026-10-05)

### 20.1 구조 변경

- Maven에 Apache Commons JEXL 3.7.0을 추가했다.
- `script/JexlRuntime`: 스크립트·표현식 컴파일, 참조 검사, 요청 치환, 응답 Map 변환과 권한 정책.
- `script/ScriptContext`: 최상위 바인딩 재대입을 막으며 global/executor/request 내부 변경을 제공.
- `script/ExecutionControl`: `stop(reason)` 협력적 중단 상태.
- `ScenarioRunPlan`: 초기 globals, 컴파일된 런타임과 단계별 스크립트 참조를 포함.
- `ScenarioRunPlanReader`: 스크립트 및 템플릿 문법 사전 검사, phase 검증, 독립 SUBSET 확장.
- `ScenarioRunner`: 실행별 mutable global/executor, 호출별 컨텍스트, PRE → 요청 치환 → curl → FILTER → VALIDATE → POST 실행.

### 20.2 실행 계약

`response.headers`는 최종 HTTP 헤더 블록의 소문자 키와 값 목록이다. JSON 본문은 중첩 Map/List까지 읽기 전용이며 비 JSON 본문에서는 `response.json=null`이다. `scenario`는 name/order/iteration을 제공한다. `request.path`는 전체 URL이며 PRE에서 상대 경로로 덮어쓰면 원래 요청의 origin에 결합한다. method/headers/body도 수정할 수 있다.

globals는 런타임 설정 원본을 복사하고 한 실행의 subset/main 및 iteration 사이에 공유한다. 서로 다른 실행에서는 공유하지 않는다. 하이픈 키는 `${global['x-token']}`처럼 표현한다. 템플릿은 괄호와 문자열 내부 중괄호를 처리하며 불완전한 `${...}`를 사전 거부한다.

VALIDATE의 `false`와 비 boolean 반환, 미정의 값, 잘못된 치환 URL/헤더, 스크립트 예외는 호출 실패다. 실패한 단계 이후 스크립트는 생략하고 `continueOnFailure`를 적용한다. HTTP 호출 후 스크립트 실패에서도 실제 curl 응답과 debug 링크를 유지한다. PRE 실패·중단은 HTTP 없이 실패 행을 기록하며 curl 링크는 비어 있다. `control.stop(reason)`은 `continueOnFailure=true`에서도 다음 호출을 막는다. 현재 Excel의 success/error에 중단을 기록하며 별도의 STOPPED 상태 컬럼은 없다.

POST는 기본 expectedStatus/expectedMaxMs 판정 전에 실행하므로 정상 응답에서만 추출할 때는 샘플처럼 상태 코드를 확인한다. FILTER/POST 반환값은 무시한다. response는 HTTP 이후에만 존재한다. 중첩 SUBSET은 거부한다. 병렬 실행은 지원하지 않는다.

### 20.3 검증

로그인 토큰 추출·전달·삭제, `/serch/list[0].id` 추출, 상세 URI 치환, PRE 상대 경로 수정, 단계 순서, 검증 실패와 continuation, 실행별 변수 격리, 중단, 응답 불변성, 권한 제한을 검증했다. 실제 샘플 Excel을 YAML로 변환한 CLI 통합 테스트는 네 curl 요청과 결과 Excel의 네 하이퍼링크를 검사한다. 최종 Maven `package`는 전체 53 tests, failures 0, errors 0으로 성공했고 `target/api-scenario-tester.jar`를 생성했다.

최종 JAR를 별도 프로세스로 실행하여 샘플 Excel→YAML 변환과 validate를 확인했다(validate 시 HTTP 호출 0). 로컬 mock API로 login → list → detail → logout 네 요청을 실행했으며 목록·상세·로그아웃의 x-token 전달과 추출된 user-id URI를 확인했다. debug 로그 네 파일 및 저장된 결과 Excel의 네 파일 링크도 확인했다. Excel guide는 현재 실행 방법으로 갱신하고 시각적으로 확인했다.

코드 리뷰에서 발견한 PRE 경로 덮어쓰기 시 원래 경로·쿼리의 미정의 값을 불필요하게 평가하는 문제, authority 템플릿 내부 슬래시 문제, 실패와 중단이 동시에 발생할 때 사유가 유실되는 문제를 수정하고 회귀 테스트를 추가했다. 권한과 기능 정책은 [JEXL 공식 permissions API](https://commons.apache.org/proper/commons-jexl/apidocs/org/apache/commons/jexl3/introspection/JexlPermissions.html) 및 [features API](https://commons.apache.org/proper/commons-jexl/apidocs/org/apache/commons/jexl3/JexlFeatures.html)를 참고했다. 17절은 초기 분석 기록이며 18절의 JEXL 미지원 표기는 이 절의 구현으로 대체된다.

## 21. 로그인 토큰 누락 진단 개선 (2026-10-05)

### 원인과 재현

`response.headers['x-token'][0]`에서 헤더 조회 결과가 null이면 JEXL이 `null value property '0'`를 발생시킨다. 정상 상태 코드만 확인하던 샘플은 헤더 존재 여부를 검사하지 않았다. x-token 없는 HTTP 200 응답으로 사용자가 보고한 `script 'extract-token' [POST] ... null value property '0'`를 동일하게 재현했다. 대소문자 변환과 최종 헤더 블록 파싱은 기존 정상 응답 테스트에서 동작한다. 사용자의 실제 실패 로그는 워크스페이스에 없어 서버의 토큰 반환 위치나 헤더 누락 원인까지는 판단하지 않았다.

### 변경

- JEXL 컴파일 소스를 `script:<id>`로 지정하여 오류 위치에 Java 생성자 대신 스크립트 ID와 줄·열을 표시한다.
- HTTP 이후 스크립트 오류에 response status와 소문자 응답 헤더 이름 목록을 추가한다. 헤더 값과 본문은 진단 문구에 추가하지 않는다.
- Excel 샘플의 `extract-token`은 헤더가 없거나 첫 값이 비어 있으면 `control.stop('login response is missing a non-empty x-token header')`로 중단한다. 정상 토큰만 global에 저장한다.
- 샘플 `guide`, README의 예제·원인 확인 방법을 갱신한다. 변경한 Excel은 YAML로 다시 변환해야 기존 실행 시나리오에도 반영된다.
- `continueOnFailure=true`에서도 로그인 토큰 누락은 다음 요청을 막고, 이미 수행한 로그인 curl 로그와 결과 Excel 링크는 유지한다.

헤더를 임의 생성하거나 토큰 누락을 성공으로 처리하지 않는다. 실제 API가 다른 헤더 또는 JSON 필드에서 토큰을 반환한다면 실제 규격에 맞게 추출 스크립트를 수정해야 한다. debug 파일의 RESPONSE HEADERS와 RESPONSE BODY가 확인 기준이다.

### 검증

누락 헤더 오류의 상태·헤더 이름 진단, 스크립트 소스명과 값 비노출을 검사했다. 실제 샘플의 누락·빈 토큰을 unit 및 CLI 테스트로 검증했고, CLI에서는 로그인 한 건만 실패 기록하고 하이퍼링크를 유지하는지 확인했다. 정상 로그인·조회·로그아웃 흐름도 회귀 검증했다. 최종 Maven `package`는 전체 56 tests, failures 0, errors 0으로 성공했고 `target/api-scenario-tester.jar`를 갱신했다. 저장된 Excel을 테스트에서 다시 읽어 검증했으며 확장한 스크립트 행의 시각적 표시도 확인했다.

## 22. 기존 결과 파일 이름 변경 (2026-10-05)

### 구조 및 동작

`cli/OutputFiles`에 공통 결과 보관 처리를 추가했다. `convert excel`, `convert yaml`, `convert postman`, `convert jmeter`, `run`은 입력 읽기·검증 이후 기존 결과를 같은 폴더의 `<원본명>_<timestamp>.<확장자>`로 이동한다. 후속 요청에 따라 접미사를 timestamp로 변경했다. 확장자와 기존 내용은 보존하고 새 결과는 원래 출력 경로에 저장한다. 이름 변경 경로는 콘솔에 표시한다.

Postman/JMeter는 workbook과 warnings YAML의 경로를 모두 검사한 뒤 기존 파일들을 보관한다. 새 변환에 경고가 없으면 새 warnings 파일은 생성하지 않으며 이전 경고는 UUID 파일에서 확인할 수 있다. 기존 결과가 없으면 일반 생성 흐름을 따른다. `template` 명령의 기존 파일 거부 정책은 유지한다.

기존 결과가 디렉터리나 일반 파일이 아닌 경로이면 이동하지 않고 입력 오류로 처리한다. timestamp 보관 경로가 충돌하면 `_1`, `_2` 등의 일련번호로 재시도하고 기존 보관 파일을 덮어쓰지 않는다. 입력 오류 시 기존 파일을 변경하지 않으며, 이름 변경 실패 시 새 결과 생성·API 호출을 시작하지 않는다. 이후 처리 오류에도 이미 보관한 기존 파일은 유지한다. run은 보관 이후 `createFile`로 원래 경로를 예약하고 기존 원자적 결과 저장과 임시 파일 정리를 수행한다. 보관 파일은 같은 폴더에 있으므로 기존 상대 curl 링크 위치가 유지된다.

### 검증

기존 결과가 있을 때 실패하던 CLI 테스트를 새 요구사항으로 수정하고 구현 전 실패를 확인했다. Excel/YAML 양방향 변환, Postman/JMeter 결과와 경고 파일 보관, 경고 없는 새 변환의 오래된 경고 보관, run 반복 실행의 보관 파일 추가 및 바이트 보존을 검증했다. 잘못된 입력과 결과 디렉터리 경로에서는 기존 파일·디렉터리를 유지한다. 전체 Maven `package`는 62 tests, failures 0, errors 0으로 성공했고 `target/api-scenario-tester.jar`를 갱신했다.

## 23. 변환 경로 오류 진단 및 출력 폴더 생성 (2026-10-05)

기존 오류 출력은 `exception.getMessage()`만 사용하여 `Unable to convert Excel scenario: login-user-scenario.yml`처럼 파일명만 표시될 수 있었다. 실제 JAR의 상대 경로 재변환은 기존 결과 이름 변경을 포함해 정상 동작했다. 입력 파일 누락과 출력 상위 폴더 누락은 동일한 파일명 중심 메시지로 재현했다. 사용자가 실행한 명령·경로 정보가 없어 사용자의 실제 실패 원인을 단정하지 않았다.

`cli/ConversionDiagnostics`를 추가하고 네 변환 명령의 오류에 단계(`read input`, `prepare output`, `write output`), 절대 입력·출력 경로, 예외 종류와 메시지를 포함했다. `OutputFiles`는 입력 읽기 성공 및 기존 출력 경로 검사 후 상위 폴더를 생성한다. 기존 파일 보관 정책은 유지한다.

입력 누락 진단, 출력 준비 오류 진단, 없는 중첩 출력 폴더의 Excel/YAML 변환을 테스트했다. 새 테스트 세 개의 구현 전 실패를 확인했고, 최종 Maven `package`는 65 tests, failures 0, errors 0으로 성공했다. 최신 JAR로 샘플의 Excel→YAML 변환 및 `validate`를 추가 확인했다. `outputs/01a10856-2fbc-75c2-b66e-e5204b53b617/login-user-scenario.yml`을 생성했으며 네 순차 단계와 한 iteration이 유효함을 확인했다. 실제 API는 호출하지 않았다. 입력은 `.xlsx`, 출력은 YAML이며 반대 방향은 `convert yaml`이다.

## 24. subset curl 로그 계약 및 회귀 검증 (2026-10-05)

`method=SUBSET`과 `preSubsets`에서 확장한 각 HTTP 단계는 main과 같은 `ScenarioRunner` 및 `CurlHttpClient`를 사용한다. `--debug`에서는 subset 내부의 호출마다 결과 Excel 옆 `curl/<uuid>.txt`에 동일한 명령·stdin·trace·stdout/stderr·응답 헤더·본문·종료 코드를 기록하고 `calls.curlLog`에 상대 파일 링크를 저장한다. 별도 subset 출력 폴더나 별도 debug 옵션은 없다. 동일 subset 재호출과 iteration마다 새 UUID를 사용한다.

이미 동작하던 공통 실행 경로를 확인하고 `execution/SubsetCurlLoggingTest`를 추가하여 계약을 고정했다. 로컬 HTTP 서버와 실제 curl로 SUBSET/preSubsets 혼합, subset 내 여러 행의 정렬, 두 iteration의 HTTP 10건·로그 10개·링크 10개 및 로그의 요청·응답 내용을 검사했다. subset HTTP 500과 POST 스크립트 오류에서 실제 curl 로그·링크 보존과 main 실행 중단을 확인했다. 비debug 모드에서는 subset/main 모두 새 로그 폴더를 생성하지 않고 링크를 비워 둔다.

SUBSET 지정 행 자체와 curl 실행 전 PRE 실패·중단에는 실제 HTTP 호출이 없으므로 curl 로그를 만들지 않는다. README와 runner 설명에 동일 정책을 명시했다. 전체 Maven `package`: 68 tests, failures 0, errors 0, BUILD SUCCESS. `target/api-scenario-tester.jar`를 갱신했다.

## 25. 보관 파일 UUID 접미사 (2026-10-05)

사용자 요청으로 `OutputFiles`의 기존 파일 보관 이름을 UUID 접두사에서 접미사로 변경했다. 마지막 확장자는 보존하며 `results.xlsx`는 `results_<UUID>.xlsx`, `scenario.yml`은 `scenario_<UUID>.yml`, `scenario.warnings.yml`은 `scenario.warnings_<UUID>.yml`로 이동한다. 확장자가 없는 파일은 `<파일명>_<UUID>`다. 새 결과는 원래 출력 경로에 저장한다.

변환 네 명령·run·경고 YAML에 공통 적용하고 README와 현재 실행 계약을 갱신했다. 기존 변환·실행 테스트를 접미사 기대값으로 수정해 구현 전 네 실패를 확인한 뒤 로직을 변경했다. 반복 보관의 바이트 보존, UUID 형식, 확장자와 warnings 이름, 입력 오류·디렉터리 보존 및 전체 회귀 테스트를 검증했다. 최종 Maven `package`: 68 tests, failures 0, errors 0, BUILD SUCCESS. `target/api-scenario-tester.jar`를 갱신했다.

## 26. 보관 파일 timestamp 접미사 (2026-10-05)

사용자 요청으로 기존 결과 보관 접미사를 UUID에서 timestamp로 변경했다. 형식은 `yyyyMMdd_HHmmss_SSS`이고 실행 JVM의 기본 시간대를 사용한다. 예: `results_20261005_063000_123.xlsx`, `scenario.warnings_20261005_063000_123.yml`. 파일명 뒤·마지막 확장자 앞에 붙이며 새 결과는 원래 경로에 저장한다.

`OutputFiles`는 한 보관 작업의 timestamp를 한 번 생성하여 변환 결과와 경고 파일에 공유한다. 동일 이름이 이미 있으면 timestamp를 유지하고 `_1`, `_2` 등의 일련번호를 추가한다. `Files.move`는 기존 보관 파일을 교체하지 않는다. 테스트용 Clock 주입 경로를 추가하여 고정 시각에서 여러 충돌과 연속 보관을 재현했다.

기존 CLI 테스트의 UUID 기대값을 timestamp로 변경한 후 구현 전 네 실패를 확인했다. 파일 내용 보존, 유효한 날짜 형식, 같은 timestamp의 번호 충돌 처리, 기본 시간대 적용, 결과·경고 파일의 timestamp 일치, 마지막 확장자 및 확장자 없는 이름을 검증했다. 최종 Maven `package`: 70 tests, failures 0, errors 0, BUILD SUCCESS. `target/api-scenario-tester.jar`를 갱신했고 README·현재 실행 계약·보관 계획을 수정했다.
