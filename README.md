# API Scenario Tester

Excel로 정의한 REST API 시나리오를 실행하는 Java CLI 프로젝트입니다. 현재 첫 번째 기반 마일스톤으로 실행 가능한 CLI, Excel 템플릿 생성, 호출 대기 정책, 응답시간 통계 계산을 제공합니다.

## 요구 사항

- JDK 21
- Maven 3.9 이상

`JAVA_HOME`이 JDK 21을 가리키는지 확인합니다.

```powershell
$env:JAVA_HOME = 'D:\01.app\java\jdk-21.0.3'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

## 빌드와 테스트

```powershell
mvn test
mvn package
```

실행 가능한 JAR은 `target/api-scenario-tester-0.1.0-SNAPSHOT.jar`에 생성됩니다.

## CLI

도움말을 확인합니다.

```powershell
java -jar target/api-scenario-tester-0.1.0-SNAPSHOT.jar --help
```

새 Excel 시나리오 템플릿을 생성합니다. 기존 파일은 덮어쓰지 않습니다.

```powershell
java -jar target/api-scenario-tester-0.1.0-SNAPSHOT.jar template --output scenario.xlsx
```

생성되는 워크북에는 다음 시트가 포함됩니다.

- `common`
- `result_format`
- `scripts`
- `sub_scenarios`
- `main_scenarios`

Postman Collection v2.0/v2.1 export JSON을 시나리오 Excel로 변환합니다.

```powershell
java -jar target/api-scenario-tester-0.1.0-SNAPSHOT.jar convert postman `
  --input samples/postman/sample-collection.json `
  --output converted-scenario.xlsx
```

Collection의 폴더 탐색 순서, HTTP method, URL, header, raw body가 `main_scenarios`로 변환됩니다. `{{name}}` 변수는 `${global.name}`으로 보존됩니다. Postman JavaScript와 인증 helper처럼 직접 실행할 수 없는 요소가 있으면 출력 파일 옆에 `*.warnings.yml`이 생성됩니다.

입력 예제와 실제 변환 결과는 `samples/postman/`의 `sample-collection.json`, `sample-scenario.xlsx`, `sample-scenario.warnings.yml`에서 확인할 수 있습니다.

JMeter JMX Test Plan을 시나리오 Excel로 변환합니다.

```powershell
java -jar target/api-scenario-tester-0.1.0-SNAPSHOT.jar convert jmeter `
  --input samples/jmeter/sample-test-plan.jmx `
  --output converted-jmeter-scenario.xlsx
```

Thread Group의 thread/loop 수, HTTP Request Sampler, Header Manager, Constant/Uniform Random Timer가 변환됩니다. 지원하지 않는 Assertion과 Extractor는 경고 YAML에 기록됩니다. 입출력 예제는 `samples/jmeter/`에서 확인할 수 있습니다.

## 실행 기준 YAML

실행 및 검증의 기준 시나리오 형식은 YAML입니다. Excel은 직접 실행하지 않고 먼저 YAML로 변환합니다.

```powershell
java -jar target/api-scenario-tester-0.1.0-SNAPSHOT.jar convert excel `
  --input scenario.xlsx `
  --output scenario.yml
```

YAML을 다시 Excel 편집 형식으로 변환할 수도 있습니다.

```powershell
java -jar target/api-scenario-tester-0.1.0-SNAPSHOT.jar convert yaml `
  --input scenario.yml `
  --output scenario-restored.xlsx
```

YAML은 `version`, `common`, `resultFormat`, `scripts`, `subScenarios`, `mainScenarios` 구조를 사용합니다. 왕복 샘플은 `samples/yaml/`에 있습니다.

## 현재 구현 범위

- 구현됨: CLI 도움말과 명령 구조
- 구현됨: 명세 기반 Excel 템플릿 생성
- 구현됨: `FIXED`, `RANDOM_RANGE` 대기시간 정책
- 구현됨: 평균, trimmed mean, nearest-rank percentile 계산
- 구현됨: Postman Collection v2.0/v2.1의 기본 HTTP 요청 변환과 경고 YAML
- 구현됨: JMeter JMX의 Thread Group, HTTP sampler, header, timer 변환과 경고 YAML
- 구현됨: 버전 1 YAML 시나리오 모델과 Excel 양방향 변환
- 다음 단계: YAML 시나리오의 실행 규칙 사전 검증
- 다음 단계: HTTP 및 JEXL 실행 파이프라인
- 다음 단계: YAML 결과 보고서

전체 설계와 변경 이력은 [project.md](project.md)를 참고합니다.
