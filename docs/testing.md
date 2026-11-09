# 테스트 전략

가짜 모델만으로는 실제 SDK의 동작이 가려집니다. 이 프로젝트에서도 답변이 60초에 끊기는 문제와 임베딩에 해시가 섞이는 문제는 가짜 모델 테스트를 모두 통과한 상태에서 발견됐습니다. 그래서 계층을 나눠 각각 다른 것을 확인합니다.

## 계층

| 계층 | 테스트 | 쓰는 것 | 확인하는 것 |
|---|---|---|---|
| 도메인 | `SafetyPolicyTest`, `CascadeAnalyzerTest`, `SeverityPolicyTest`, `LineTopologyTest` | 순수 자바 | 안전 거절 사유, 원인 후보, 장애 등급 |
| 애플리케이션 | `ProposeCommandServiceTest`, `DecideCommandServiceTest`, `DetectAnomaliesServiceTest`, `IngestEventServiceTest`, `PlantStatusServiceTest`, `StateDigestTest` | 메모리 대역 | 제안과 확정, 상태 재비교, 한 번만 전송, 결과 미확인 처리, 감지와 등급 갱신 |
| 계약 | `*Contract`(설비 4종, 저장소 4종) | 메모리 대역, HTTP 어댑터, JDBC 어댑터가 같은 테스트를 통과 | 대역이 실제 어댑터와 같은 의미로 동작함 |
| 설비 어댑터 | `VendorAConveyorsTest`, `VendorBSortersTest`, `FleetRobotsTest`, `SafetyPlcSignalsTest`, `EquipmentAdaptersSimulatorIT` | 로컬 HTTP 스텁, 시뮬레이터 | 제조사 형식 변환, 범위 밖 값과 타임아웃은 실패로 처리 |
| 대화와 도구 | `AssistantPipelineTest`, `ToolLoopTest`, `PlantToolsTest`, `DemoIntentTest`, `DemoEmbeddingModelTest` | 가짜 모델 | 이벤트 순서, `action` 위치, 도구 반복, 잘못된 ID와 인자, 인자 변환 실패까지 세는 도구 한도, 모델이 만든 도구 이름이 로그에 남지 않음, 데모 검색 순위 |
| 웹 슬라이스 | `ChatControllerTest`, `CommandControllerTest`, `PlantControllerTest`, `EventControllerTest`, `MaintenanceControllerTest`, `KnowledgeControllerTest` | MockMvc | 응답 코드표, 권한(참관자, 다른 운영자, 세션 없음), 로그와 본문에 입력이 남지 않음 |
| 구조 | `ArchitectureTest` | ArchUnit | 의존 방향, 도구 어댑터는 입력 포트만 호출, 들어오는 어댑터는 출력 포트에 의존하지 않음 |
| 설정 | `ApiKeyGuardTest`, `ContextLoadsIT`, `ToolExceptionProcessorTest`, `DetectionThreadsTest` | 실제 설정 파일 | 키 누락 시 기동 실패, 포트마다 빈 1개, 도구 예외의 고정 결과와 클래스 이름만 남는 로그, 종료 시 감지 스레드 정리 |
| 통합 | `ChatStreamIT`, `KnowledgeSyncIT`, `PersistenceIT`, `SimulatorPushIT`, `StaleCommandCleanupIT` | 실제 HTTP, PostgreSQL 컨테이너 | 증분 전달, 대화 저장, 문서 동기화, 동시 확정 시 실행 잠금 키당 승자 하나, 스키마 업그레이드, 이벤트 전송, 웹 서버 시작 전 기동 정리 |
| 전체 흐름 | `ControlApiIT`, `DemoModeIT` | 시뮬레이터, PostgreSQL, 실제 HTTP | 우회 확정 뒤 시뮬레이터 변화, 막힘 중 재가동 409, 상태가 바뀐 카드 409, 장애 건 열림과 닫힘, 키 없는 데모 장면 |
| 속은 모델 | `FooledModelIT` | 모든 설비에 모든 제안 도구를 부르는 가짜 모델 | 설비 전송 0건, 정비 건 0건, 시뮬레이터 상태 불변, 참관자와 익명은 모든 제안이 권한 없음 |
| SDK 경계 | `RealBeansStubIT` | 실제 SDK 빈, 로컬 HTTP 스텁 | 나가는 요청의 모델, 옵션, 도구 스키마 8개, 계정 정보 없음, 2회차 요청의 도구 결과 |
| 실제 API | `LiveApiIT` | 실제 Claude, Gemini | 요청 수락, 도구 선택, 인젝션 문장에 전송 0건, 검색 품질 표 |

## 실행

결과 파일은 `build/test-results/test/`에 `TEST-com.example.controltower...xml` 형식으로 남습니다.

- `./gradlew test`: 전체 실행. Docker가 필요합니다. 비용은 들지 않습니다.
- CI는 PR과 `main` 푸시마다 같은 명령을 실행합니다. 키가 없으므로 `LiveApiIT`는 건너뜁니다.
- `LiveApiIT`는 키 2개가 모두 있을 때만 실행됩니다. 키가 있는 셸에서는 `./gradlew test`도 비용이 발생합니다. 실행 방법은 README의 검증 상태를 보세요.

## 규칙

- `*IT`는 Docker가 없으면 건너뛰지 않고 실패합니다. 조용히 건너뛴 테스트를 통과로 착각하지 않기 위해서입니다.
- 시간 단언은 하한만 둡니다. 상한은 교착을 잡는 용도로만 씁니다. 예를 들어 첫 `delta`와 `done` 사이의 간격이 일정 시간 이상인지로 버퍼링을 잡습니다.
- SDK와 맞닿는 코드를 바꾸면 `RealBeansStubIT`에 검증을 추가합니다. 가짜 모델의 통과는 근거가 되지 않습니다.
- 버그를 고칠 때는 고치기 전에 실패하는 테스트를 먼저 확인합니다.

## 검증하지 못한 것

- 실제 Claude·Gemini 호출과 실제 임베딩의 검색 품질
- 실제 모드의 임계값 `0.4`
- 실제 모델의 도구 선택, 프롬프트 준수, 답변 품질
- 실제 설비 프로토콜(지금은 제조사별 가상 HTTP)
- 한글 IME 입력, 스크린 리더
- 10분 타임아웃에서 실제로 끊기는 동작

`RealBeansStubIT`의 스텁은 API 응답 형식을 흉내 낸 것이라, 통과해도 실제 API가 요청을 받아 준다는 뜻은 아닙니다.
