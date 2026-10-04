# JEXL runtime 구현 계획

- 스크립트와 템플릿 문법, phase 및 참조를 HTTP 호출 전에 검증한다.
- 실행별 global/executor와 호출별 request/response/scenario/control 컨텍스트를 제공한다.
- PRE → 요청 치환 → curl → FILTER → VALIDATE → POST 순으로 실행한다.
- SUBSET 행은 순차 HTTP 단계로 확장하고, 실패는 결과 Excel에 기록한다.
- SECURE 권한을 사용하고 생성자, pragma, lambda, 반복문을 제한한다.
- 로그인/조회/로그아웃, 실패 처리, 권한 제한을 테스트하고 기존 테스트를 실행한다.

진행: 완료.

- 로그인 흐름 통합 테스트가 기존 미구현 오류로 실패하는 것을 확인한 뒤 구현했다.
- JEXL 런타임·SUBSET·요청 치환·단계 실행과 실패 보고를 완료했다.
- 코드 리뷰의 PRE origin 추출, 불완전한 템플릿, 토큰 삭제 검증, 중단 사유 유실 문제를 수정하고 회귀 테스트를 추가했다.
- 전체 Maven package: 53 tests, failures 0, errors 0; BUILD SUCCESS.
- 최종 실행 JAR로 샘플 Excel→YAML 변환, validate(호출 0), run(debug)을 수행했다. 로컬 mock API의 인증 요청 4건, 로그 4개, 결과 Excel 하이퍼링크 4개를 확인했다.
- README, project.md 구조 변경 이력, Excel guide를 갱신했다.
