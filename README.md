# API Scenario Tester

Excel로 작성하고 YAML로 변환한 REST API 시나리오를 curl 명령으로 순차 실행하는 Java CLI 프로젝트입니다. 실행 결과는 Excel로 생성하며, debug 모드에서는 각 호출의 입력·출력 로그를 결과 Excel의 하이퍼링크로 확인할 수 있습니다. 병렬 실행은 지원하지 않습니다.

## 요구 사항

- JDK 21
- Maven 3.9 이상
- PATH에 등록된 curl 실행 파일 (Windows: `curl.exe`)

`JAVA_HOME`이 JDK 21을 가리키는지 확인합니다.

```powershell
$env:JAVA_HOME = 'C:\Develop\app\java\jdk-21.0.3'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

## 빌드와 테스트

```powershell
mvn test
mvn package
```

실행 가능한 JAR은 `target/api-scenario-tester.jar`에 생성됩니다.

## CLI

도움말을 확인합니다.

```powershell
java -jar target/api-scenario-tester.jar --help
```

새 Excel 시나리오 템플릿을 생성합니다. 기존 파일은 덮어쓰지 않습니다.

```powershell
java -jar target/api-scenario-tester.jar template --output scenario.xlsx
```

생성되는 워크북에는 다음 시트가 포함됩니다.

- `common`
- `result_format`
- `scripts`
- `sub_scenarios`
- `main_scenarios`

Postman Collection v2.0/v2.1 export JSON을 시나리오 Excel로 변환합니다.

```powershell
java -jar target/api-scenario-tester.jar convert postman `
  --input samples/postman/sample-collection.json `
  --output converted-scenario.xlsx
```

Collection의 폴더 탐색 순서, HTTP method, URL, header, raw body가 `main_scenarios`로 변환됩니다. `{{name}}` 변수는 `${global.name}`으로 보존됩니다. Postman JavaScript와 인증 helper처럼 직접 실행할 수 없는 요소가 있으면 출력 파일 옆에 `*.warnings.yml`이 생성됩니다.

입력 예제와 실제 변환 결과는 `samples/postman/`의 `sample-collection.json`, `sample-scenario.xlsx`, `sample-scenario.warnings.yml`에서 확인할 수 있습니다.

JMeter JMX Test Plan을 시나리오 Excel로 변환합니다.

```powershell
java -jar target/api-scenario-tester.jar convert jmeter `
  --input samples/jmeter/sample-test-plan.jmx `
  --output converted-jmeter-scenario.xlsx
```

Thread Group의 thread/loop 수, HTTP Request Sampler, Header Manager, Constant/Uniform Random Timer가 변환됩니다. 지원하지 않는 Assertion과 Extractor는 경고 YAML에 기록됩니다. 입출력 예제는 `samples/jmeter/`에서 확인할 수 있습니다.

## 실행 기준 YAML

실행 및 검증의 기준 시나리오 형식은 YAML입니다. Excel은 직접 실행하지 않고 먼저 YAML로 변환합니다.

```powershell
java -jar target/api-scenario-tester.jar convert excel `
  --input scenario.xlsx `
  --output scenario.yml
```

YAML을 다시 Excel 편집 형식으로 변환할 수도 있습니다.

```powershell
java -jar target/api-scenario-tester.jar convert yaml `
  --input scenario.yml `
  --output scenario-restored.xlsx
```

YAML은 `version`, `common`, `resultFormat`, `scripts`, `subScenarios`, `mainScenarios` 구조를 사용합니다. 왕복 샘플은 `samples/yaml/`에 있습니다.

`convert excel/yaml/postman/jmeter`와 `run`은 결과 파일이 이미 있으면 파일명 뒤·마지막 확장자 앞에 timestamp 접미사를 추가해 기존 파일을 보관합니다. 형식은 `yyyyMMdd_HHmmss_SSS`이며 실행 JVM의 기본 시간대를 사용합니다. 예를 들어 `results.xlsx`는 `results_20261005_063000_123.xlsx`로 변경하고 새 결과는 `results.xlsx`에 저장합니다. 같은 이름이 있으면 `results_20261005_063000_123_1.xlsx`처럼 번호를 추가합니다. Postman/JMeter의 기존 `scenario.warnings.yml`은 `scenario.warnings_<timestamp>.yml`로 보관하며 동일 변환의 결과·경고 파일은 같은 timestamp를 사용합니다. 새 변환에 경고가 없으면 새 경고 파일은 만들지 않습니다. 입력 검증 오류에서는 기존 결과를 변경하지 않습니다. 이름 변경에 실패하면 새 결과 작성이나 API 호출을 시작하지 않습니다. 이후 생성·실행 도중 오류가 발생해도 보관한 기존 파일은 유지합니다.

없는 출력 폴더는 입력 읽기 성공 후 자동 생성합니다. 변환 오류는 `stage=read input`(입력 읽기), `stage=prepare output`(폴더 준비·기존 파일 이름 변경), `stage=write output`(새 파일 작성)과 입력·출력 절대 경로, 예외 종류를 표시합니다. `NoSuchFileException`은 경로를 찾지 못한 오류이고 `AccessDeniedException`은 접근 거부입니다. `convert excel`의 `--input`은 Excel `.xlsx`, `--output`은 실행 YAML입니다. YAML에서 Excel을 만들 때는 `convert yaml`을 사용하세요.

## 현재 구현 범위

- 구현됨: CLI 도움말과 명령 구조
- 구현됨: 명세 기반 Excel 템플릿 생성
- 구현됨: `FIXED`, `RANDOM_RANGE` 대기시간 정책
- 구현됨: 평균, trimmed mean, nearest-rank percentile 계산
- 구현됨: Postman Collection v2.0/v2.1의 기본 HTTP 요청 변환과 경고 YAML
- 구현됨: JMeter JMX의 Thread Group, HTTP sampler, header, timer 변환과 경고 YAML
- 구현됨: 버전 1 YAML 시나리오 모델과 Excel 양방향 변환
- 구현됨: YAML 실행 사전 검증, 런타임 host/global 설정
- 구현됨: curl 기반 main/subset 순차 호출, 반복, 쿠키 유지, 대기·실패 정책
- 구현됨: Excel 실행 결과와 debug curl 로그 하이퍼링크
- 구현됨: JEXL PRE/FILTER/VALIDATE/POST, 전역변수 저장·삭제, 표현식 치환 및 중단 API
- 다음 단계: YAML 결과 보고서

## curl 실행과 debug 로그

외부 호출 없이 시나리오를 검증합니다.

```powershell
java -jar target/api-scenario-tester.jar validate --scenario scenario.yml --config runtime.yml
```

시나리오를 실행하고 결과 Excel을 생성합니다. `--config`는 환경별 host와 global 값이 필요한 경우 사용합니다.

```powershell
java -jar target/api-scenario-tester.jar run `
  --scenario scenario.yml --config runtime.yml `
  --output results.xlsx --debug
```

- `--debug`: 결과 Excel 옆의 `curl/<uuid>.txt`에 호출별 curl 명령 인자, stdin 본문, 송수신 trace, stdout/stderr, 응답 헤더·본문, 종료 코드를 기록합니다. 실패한 호출도 기록합니다.
- subset 호출도 main과 같은 규칙을 사용합니다. `method=SUBSET`과 `preSubsets` 모두 subset 내부의 각 HTTP 호출마다 UUID 로그와 `calls.curlLog` 링크를 생성합니다. 여러 행이나 반복 실행에서는 각 호출마다 별도 파일을 만듭니다. HTTP 실패나 호출 이후 스크립트 오류에서도 로그를 유지합니다. SUBSET 지정 행 자체와 curl 전에 실패·중단한 단계는 실제 HTTP 호출이 없으므로 curl 로그를 만들지 않습니다.
- 결과 Excel은 `summary`, `calls` 시트를 포함합니다. `calls.curlLog`는 로그 파일을 여는 상대 파일 하이퍼링크입니다. Excel과 `curl` 폴더를 함께 이동하면 링크를 유지할 수 있습니다.
- debug 미사용 시 로그 폴더를 생성하지 않으며 `curlLog` 셀은 비어 있습니다.
- `--output` 생략 시 `resultFormat.output`을 사용합니다. 기본값은 `results.xlsx`이며, 이전 `.yml`/`.yaml` 설정은 `.xlsx`로 치환합니다. 기존 결과 파일은 같은 폴더의 `<원본명>_<timestamp>.<확장자>`로 이름을 변경해 보관하고 새 결과를 원래 경로에 저장합니다.
- 종료 코드: 전체 성공 `0`, HTTP·curl 호출 실패 `1`, 입력·파일 처리 오류 `2`.

런타임 설정 예시입니다. `curlExecutable`은 curl이 PATH에 없거나 다른 설치본을 지정할 때 사용합니다.

```yaml
curlExecutable: curl.exe
globals:
  userId: "123"
  tenantId: sample
hosts:
  api:
    baseUrl: http://localhost:8080
    connectTimeoutMs: 3000
    readTimeoutMs: 10000
```

요청 path·header 값·body의 `${global.userId}`, `${global['x-token']}`, `${1 + 2}`와 같은 JEXL 표현식을 지원합니다. 런타임 YAML 값의 `${NAME}`은 환경 변수로 치환하며 미정의 환경 변수는 사전 오류입니다. 요청 표현식은 PRE 이후 평가하므로 이전 POST에서 추출한 값을 사용할 수 있습니다. 연결 제한시간은 `connectTimeoutMs`, 전체 curl 제한시간은 `connectTimeoutMs + readTimeoutMs`입니다.

`sessions`는 생략 또는 `1`만 허용합니다. 현재 Excel 보고서는 모든 시도된 호출을 포함하며, 기존 `includeCallDetails` 설정은 후속 YAML 보고서용으로 남겨 둡니다.

## JEXL 스크립트 실행

`scripts`의 `id`, `phase`, `body`를 정의하고 호출 행의 `preScripts`, `filterScripts`, `validationScripts`, `postScripts`에 쉼표로 구분한 ID를 지정합니다. 문법, 중복 ID, 참조 및 phase는 실행 전에 검사합니다.

순서는 PRE → 요청 치환 → curl → FILTER → VALIDATE → POST입니다. `global`과 `executor`는 실행 전체에서 공유하는 변경 가능한 Map이고, 실행을 다시 시작하면 초기화됩니다. `scenario`와 `response`는 읽기 전용입니다. PRE의 `request.path`는 기본적으로 전체 URL이며 상대 경로로 덮어쓸 수도 있습니다. `request.method`, `request.headers`, `request.body`도 수정할 수 있습니다.

```jexl
// POST: 로그인 토큰 추출
if (response.status >= 200 && response.status <= 299) {
  var tokens = response.headers['x-token'];
  if (tokens == null || tokens.size() == 0 || tokens[0] == null || tokens[0].trim() == '') {
    control.stop('login response is missing a non-empty x-token header');
  } else {
    global['x-token'] = tokens[0];
  }
}
// POST: 로그아웃 토큰 삭제
global.remove('x-token');
```

응답 헤더는 소문자 키와 값 목록으로 제공하며 JSON 본문은 `response.json`으로 접근합니다. JSON이 아닌 본문은 `response.json=null`이고 원문은 `response.body`에 있습니다. VALIDATE는 `true`를 반환해야 성공입니다. 스크립트 예외는 호출 실패로 기록하고 `continueOnFailure`를 따릅니다. 실패한 단계 이후 스크립트는 생략합니다. POST는 기본 상태 코드 판정에 관계없이 실행되므로 정상 응답에서만 추출하려면 위 예제처럼 조건을 사용합니다.

HTTP 200–299만으로 토큰 존재를 보장하지 않습니다. 로그인 응답에 `x-token`이 없거나 비어 있으면 위 샘플은 사유를 기록하고 후속 요청을 중단합니다. API가 다른 헤더명 또는 JSON 필드로 토큰을 반환한다면 추출 경로를 실제 규격에 맞춰 변경하세요. `--debug` 로그의 `RESPONSE HEADERS`에서 실제 응답 헤더를 확인할 수 있습니다. 스크립트 예외에는 상태 코드와 수신 헤더 이름을 함께 표시하며 헤더 값은 포함하지 않습니다. Excel 샘플을 변경한 뒤에는 YAML로 다시 변환해야 실행에 반영됩니다.

`control.stop('사유')`는 다음 curl 호출을 막고 사유를 결과에 기록합니다. PRE에서 중단하면 HTTP 호출 없이 실패 행이 기록됩니다. 클래스 생성, 반사·파일·프로세스 접근, pragma, lambda, 반복문은 제한하며 템플릿에서는 대입을 허용하지 않습니다. 실행 중 알 수 없는 전역변수나 잘못된 요청 값은 호출 실패입니다.

메인 행의 `method=SUBSET`, `path=login`은 해당 `subsetId`의 HTTP 행들을 순서대로 실행합니다. 중첩 SUBSET은 지원하지 않습니다. 로그인·조회·로그아웃 예제는 `samples/auth/sample-scenario.xlsx`에 있으며 Excel을 YAML로 변환한 후 `validate`와 `run`을 사용합니다.

전체 설계와 변경 이력은 [project.md](project.md)를 참고합니다.
