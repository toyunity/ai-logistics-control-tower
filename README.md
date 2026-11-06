# AI 상담 챗봇

FAQ를 근거로 답하는 고객 상담 챗봇입니다. Java 25, Spring Boot 4.1.2, Spring AI 2.0.2로 만들었고, 답변은 Claude, 임베딩은 OpenAI, 벡터 저장과 대화 기록은 PostgreSQL(pgvector)을 씁니다.

`src/main/resources/faq/faqs.json`의 FAQ는 **가상의 쇼핑몰 샘플 데이터**입니다. 실제 서비스에 쓰려면 실제 데이터로 교체해야 합니다.

## 아직 검증하지 않은 것

작성 시점에 API 키가 없어 아래 항목은 실행하지 못했습니다. 자동 테스트는 가짜 모델과 로컬 스텁으로 통과했을 뿐이며, 실제 API에서 동작한다는 보장은 아닙니다.

- **실제 Claude, OpenAI 호출.** `claude-opus-5-5` 모델과 effort `low` 옵션 조합이 API에서 수락되는지, 실제 임베딩으로 기동과 검색이 되는지 확인되지 않았습니다.
- **검색 임계값 보정.** `consult.similarity-threshold`의 `0.4`는 측정값이 아닌 초기값입니다.

다음 명령으로 확인합니다. 소액의 비용이 발생합니다.

```bash
ANTHROPIC_API_KEY=... OPENAI_API_KEY=... ./gradlew test --tests '*LiveApiIT' --rerun -i
```

`--rerun`을 빼면 안 됩니다. Gradle은 환경 변수 변화를 입력으로 보지 않으므로, 키 없이 건너뛴 직후에는 테스트를 실행하지 않고 `UP-TO-DATE`로 넘어가면서 `BUILD SUCCESSFUL`을 출력합니다. 끝난 뒤 `build/test-results/test/TEST-com.example.consult.LiveApiIT.xml`에서 `tests="4"`, `skipped="0"`, `failures="0"`을 확인하세요.

임계값 보정 방법은 다음과 같습니다.

1. 출력된 표(`question | expected | score | hit`)를 봅니다. 점수는 임계값을 적용하지 않은 값입니다.
2. 정답 질문의 최저 점수와 무관 질문(`(unrelated)`)의 최고 점수 사이 값을 `application.yaml`의 `consult.similarity-threshold`에 넣습니다.
3. 두 분포가 겹치면 FAQ 문구를 개선합니다. 그래도 부족하면 hybrid search를 검토합니다.

무관 질문의 점수는 기록만 하고 판정하지 않으므로, `retrievalHitRate`가 통과해도 임계값이 적절하다는 뜻은 아닙니다. 표를 직접 확인해야 합니다.

## 사전 요구사항

- JDK 25 (`JAVA_HOME`이 JDK 25를 가리켜야 합니다. macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 25)`)
- Docker
- API 키 2개: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`

## 실행

```bash
docker compose up -d
ANTHROPIC_API_KEY=... OPENAI_API_KEY=... ./gradlew bootRun
```

http://localhost:8080 에서 채팅 페이지를 엽니다.

키 환경 변수가 없으면 기동이 `Could not resolve placeholder 'ANTHROPIC_API_KEY'`(또는 `'OPENAI_API_KEY'`)로 실패합니다(`ApiKeyGuardTest`로 확인). 값이 있는지만 확인하므로 틀린 키는 잡지 못합니다. 틀린 키가 실제로 어떻게 드러나는지는 API 키가 없어 관찰하지 못했습니다. 코드상으로는 임베딩할 FAQ가 있을 때 OpenAI 호출 실패로 기동이 중단되고, 그 외에는 첫 질문의 `UPSTREAM` 오류로 드러날 것입니다.

DB 접속 정보는 `DB_URL`, `DB_USER`, `DB_PASSWORD`로 바꿀 수 있고, 기본값은 `compose.yaml`과 같습니다.

## 테스트

```bash
./gradlew test
```

Docker 데몬이 필요합니다. `*IT` 테스트는 Docker가 없으면 건너뛰지 않고 실패합니다. 가짜 모델이나 로컬 스텁을 쓰므로 비용이 들지 않습니다.

`LiveApiIT`만 예외입니다. 키 2개가 모두 있을 때만 실제 API를 호출하고, 하나라도 없으면 건너뜁니다. 따라서 키가 환경 변수에 있는 셸에서 `./gradlew test`를 실행하면 비용이 발생합니다.

## SSE 계약

`POST /api/chat/stream`

```json
{"conversationId": "<uuid, 선택>", "message": "<1~2000자>"}
```

POST이므로 브라우저에서는 `EventSource`가 아니라 `fetch`와 stream reader를 씁니다.

| event | data | 시점 |
|---|---|---|
| `meta` | `{"conversationId":"<uuid>"}` | 항상 첫 이벤트, 1회 |
| `sources` | `{"items":[{"id","question","score"}]}` (빈 배열 가능) | 검색 완료 후 1회, 첫 `delta` 앞. 첫 청크 전에 실패하면 생략되고 바로 `error`가 옵니다 |
| `delta` | `{"text":"..."}` | 0회 이상 |
| `done` | `{}` | 정상 종료 |
| `error` | `{"code":"UPSTREAM"\|"REFUSED","message":"<고정 한국어 문구>"}` | 실패 종료 |

스트림은 `done` 또는 `error` 중 정확히 하나로 끝납니다. `error.message`에는 예외 내용을 넣지 않습니다.

첫 청크까지의 대기(질문 재작성, 임베딩 검색, 첫 토큰 포함)나 청크 사이의 침묵이 `consult.stream-idle-timeout`(기본 60초)을 넘으면 스트림은 `UPSTREAM` 오류로 끝납니다. 이 값은 대기와 침묵만 제한합니다. 응답 전체 시간은 `spring.ai.anthropic.timeout`(10분)으로 제한되어, 넘으면 역시 `UPSTREAM` 오류로 끝납니다.

클라이언트가 연결을 끊어도 Claude로의 업스트림 호출은 멈추지 않습니다. 버려진 답변도 `consult.max-tokens`(8000)나 위 10분 한도에 닿을 때까지 생성되고 비용이 청구됩니다. 답변 길이와 비용 상한을 함께 정하는 값이므로 `consult.max-tokens`를 올릴 때 이 점을 고려하세요.

## FAQ 수정

`src/main/resources/faq/faqs.json`을 수정한 뒤 재시작합니다. 기동할 때 바뀐 항목만 다시 임베딩하고, 파일에서 사라진 항목은 삭제합니다. 파일이 빈 배열(`[]`)이면 기존 데이터를 지우지 않고 기동이 실패합니다.

임베딩에는 질문과 답변 텍스트만 넣습니다. 이 설정(`spring.ai.openai.embedding.metadata-mode: none`) 이전에 만든 `vector_store` 행이 있으면 아래 "임베딩 모델 교체"의 명령으로 테이블을 지우세요. 해시가 그대로라 재임베딩되지 않습니다.

## 임베딩 모델 교체

FAQ 내용이 그대로면 기존 벡터가 재사용되므로, 모델을 바꿀 때는 `vector_store`를 비워 재색인해야 합니다. 벡터 차원이 달라지면 `spring.ai.vectorstore.pgvector.dimensions`도 함께 바꿉니다.

```bash
docker compose exec db psql -U consult -d consult -c 'DROP TABLE vector_store;'
```

그런 다음 재시작하면 테이블을 새 차원으로 만들고 전체 FAQ를 다시 임베딩합니다.

## 공개 배포 전 주의

**이 애플리케이션에는 인증과 rate limit이 없습니다.** 그대로 공개하면 누구나 채팅 API를 호출할 수 있어 API 비용이 무제한으로 발생할 수 있고, `conversationId`를 아는 사람은 그 대화를 이어갈 수 있습니다. 공개 배포 전에 인증과 rate limit을 반드시 추가하세요.
