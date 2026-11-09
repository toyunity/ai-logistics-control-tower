# 실행과 운영

실제 API·로컬 모델로 실행하는 방법, 테스트, 지식 문서 수정, 한계, 검증 상세입니다. 개요는 [README](../README.md)에 있습니다.

## 데모 모드에서 알아 둘 점

- **의도는 키워드로 판단합니다.** 설비 ID와 "재가동", "우회", "정비" 같은 단어를 보고 도구를 부릅니다. 우회·재배정 방향은 "로/으로"가 붙은 ID를 받는 쪽으로 먼저 보고, 없으면 어순(앞이 보내는 쪽)을 따릅니다(`A를 B로`, `B로 A를` 모두 A→B). 그래서 `CH-03로부터 CH-04에 우회해`는 "로부터"의 "로"를 받는 쪽으로 읽어 CH-04→CH-03으로 거꾸로 제안합니다. 부정문("재가동하지 마")은 이해하지 못합니다. 그래도 카드를 만들 뿐 실행은 확정해야 합니다.
- **문서 검색은 단순 글자 유사도입니다.** 질문 10개는 맞는 문서가 1위로 나오고 무관한 질문 31개는 걸리지 않지만, `임진왜란은 몇 년에 일어났나요?`와 `다이어트는 어떻게 하나요?`는 엉뚱한 문서에 걸립니다. 거르려고 임계값을 올리면 `알람 101` 질문이 떨어져 `0.20`에 두었습니다([설계 결정](decisions.md)).

## 실제 API로 실행

API 키 2개(`ANTHROPIC_API_KEY`, `GEMINI_API_KEY`)가 필요합니다. Anthropic은 임베딩 API를 제공하지 않아 임베딩은 Gemini가 맡습니다.

키는 파일에 쓰지 말고 셸에서 화면에 보이지 않게 입력합니다(zsh 기준).

```bash
read -rs "ANTHROPIC_API_KEY?Anthropic API key: " && echo && export ANTHROPIC_API_KEY
read -rs "GEMINI_API_KEY?Gemini API key: " && echo && export GEMINI_API_KEY
```

```bash
docker compose up -d
./gradlew bootRun
```

### Claude 키 없이: 로컬 모델(`local` 프로필)

답변을 내 컴퓨터의 [Ollama](https://ollama.com) 모델로 바꾸면 Claude 키 없이 실제 언어 모델로 돌려 볼 수 있습니다. 임베딩은 Gemini를 그대로 써서 Gemini 키 하나만 필요합니다. 도구 실행, 안전 규칙, 확정 흐름은 같은 코드입니다.

```bash
brew install ollama && brew services start ollama
ollama pull qwen2.5:7b            # 도구 호출을 지원하는 모델, 약 4.7GB
read -rs "GEMINI_API_KEY?Gemini API key: " && echo && export GEMINI_API_KEY
./gradlew bootRun --args='--spring.profiles.active=local'
```

로컬 모델은 Claude보다 느리고 판단도 다를 수 있습니다. Claude 자체의 동작을 확인하려면 아래 "실제 API로 실행"이 필요합니다.

키 환경 변수가 없으면 기동이 `Could not resolve placeholder 'ANTHROPIC_API_KEY'`(또는 `'GEMINI_API_KEY'`)로 실패합니다(`ApiKeyGuardTest`). 값이 있는지만 확인하므로 틀린 키는 잡지 못합니다. DB 접속 정보는 `DB_URL`, `DB_USER`, `DB_PASSWORD`로 바꿀 수 있고 기본값은 `compose.yaml`과 같습니다.

## 테스트

```bash
./gradlew test
```

Docker가 필요하고 비용은 들지 않습니다. `*IT`는 Docker가 없으면 건너뛰지 않고 실패합니다. 가짜 모델, 로컬 HTTP 스텁, 시뮬레이터를 씁니다. 실제 모델로 도는 검사는 두 가지입니다. `LiveApiIT`는 키 2개가 모두 있을 때 실제 Claude와 Gemini를 호출하므로, 키가 있는 셸에서 `./gradlew test`를 돌리면 비용이 납니다. `LocalModelIT`는 Gemini 키가 있고 Ollama가 실행 중일 때 같은 검사를 로컬 모델로 돕니다.

## 지식 문서 수정

`src/main/resources/knowledge/documents.json`(22건, 전부 가상)을 고친 뒤 재시작합니다. 바뀐 문서만 다시 임베딩하고 사라진 문서는 지웁니다. 빈 배열(`[]`)이면 기존 데이터를 지우지 않고 기동이 실패합니다.

임베딩 모델을 바꾸면 해시에 들어가는 `controltower.embedding-index`가 바뀌어 전체를 다시 임베딩합니다. 벡터 차원을 바꿀 때는 `spring.ai.vectorstore.pgvector.dimensions`와 `spring.ai.google.genai.embedding.text.options.dimensions`를 같은 값으로 바꾸고 테이블을 지운 뒤 재시작합니다. OpenAI 임베딩을 쓰던 이전 버전의 DB도 차원이 같아 테이블을 지울 필요는 없고, 첫 기동 때 전체를 다시 임베딩합니다.

```bash
docker compose exec db psql -U consult -d consult -c 'DROP TABLE vector_store;'
```

## 한계

운영 환경에 투입할 수준으로 검증된 프로젝트가 아닙니다.

- **샘플 계정입니다.** `operator`와 `viewer`는 비밀번호 없이 고릅니다. 사내 인증으로 바꿔야 합니다.
- **이벤트 API에 인증이 없습니다.** `POST /api/events`는 게이트웨이를 가정한 입구라 형식만 검사합니다.
- **대화 소유권과 rate limit이 없습니다.** `conversationId`를 아는 사람은 그 대화를 이어갈 수 있고, 호출 수 제한이 없어 공개하면 API 비용이 무제한으로 날 수 있습니다.
- **CSRF는 `SameSite=Lax` 쿠키에만 기댑니다.** 다른 사이트의 POST는 막지만 같은 사이트의 다른 출처(같은 호스트의 다른 포트 등)는 막지 못합니다.
- **연결이 끊겨도 업스트림 호출은 계속됩니다.** 버려진 답변도 `controltower.max-tokens`(8000)나 10분 한도까지 생성되어 비용이 청구됩니다. 화면은 상태 조회가 실패하면 "연결 끊김"을 표시합니다.
- **전송 중 서버가 죽으면** 그 명령은 다음 기동 때 "결과 미확인"으로 정리되며, 그 전까지 같은 실행 잠금(재가동·정비는 같은 설비, 우회는 분류기 전체, 재배정은 로봇 전체)의 새 명령은 막힙니다. 단일 인스턴스를 전제합니다. 실행 잠금 방식이 바뀐 이 버전으로 기존 DB를 올릴 때 실행 중(`EXECUTING`) 상태의 우회 명령이 둘 이상 남아 있으면 스키마 갱신이 실패해 기동이 멈추므로, 그 행을 먼저 정리해야 합니다.
- **원인 추적은 후보입니다.** 이벤트 순서로 추정하므로 실제 원인과 다를 수 있습니다.
- **감지를 끄면**(`controltower.detection.enabled=false`) 이벤트가 몰릴 때 버려진 감지는 다음 이벤트가 올 때까지 반영되지 않습니다.
- **도구 호출 한도(한 턴 8회)를 넘으면** 인자 형식이 틀려 실패한 호출까지 세어 16회까지는 모델에게 알리기만 하고, 17번째 호출에서 스트림을 `error`로 끝냅니다. 한 턴의 답변 생성 호출은 최대 17번 과금됩니다(후속 질문이면 재작성 호출 1번이 더해집니다).
- **시뮬레이터 설비 API가 같은 포트에 열려 있습니다.** `controltower.simulator.enabled=true`(기본값)이면 `/sim/...` 아래 가상 설비의 상태 조회, 명령, 안전 신호, 시나리오 제어가 인증 없이 열리고, 이를 직접 부르면 확인 카드와 안전 규칙을 거치지 않습니다. 데모 전용입니다. 실제 설비에 붙일 때는 끕니다(README의 "귀사 설비에 붙일 때").
- **하드 스톱으로 끝난 턴의 카드는 보이지 않습니다.** 17번째 도구 호출로 스트림이 `error`로 끝나면 그 턴에 이미 만든 확인 카드는 `action`으로 나가지 않고, 확정되지 않은 채 2분 뒤 만료됩니다.
- **설비 게이트웨이 장애 뒤 회복이 늦게 보입니다.** 설비군마다 서킷 브레이커가 있어 응답 없음이나 5xx가 최근 6건 중 절반을 넘으면 회로가 열립니다. 열린 동안(기본 10초, `controltower.equipment.circuit-breaker.wait-in-open-state`)은 설비가 회복돼도 그 설비군은 UNKNOWN이고 제안은 거절, 확정은 보내지 않고 "보내지 못함"(FAILED)으로 닫힙니다. 회로 상태 변화는 설비군 이름과 상태만 WARN 로그로 남습니다.
- **설비 프로토콜은 가상입니다.** 제조사별 HTTP 형식은 시뮬레이터용으로 만든 것입니다.

## 검증 상태

실행으로 확인한 것과 하지 못한 것을 나눠 적습니다.

- **자동 테스트와 화면:** 가짜 모델, 로컬 스텁, 시뮬레이터, PostgreSQL 컨테이너로 전체 자동 테스트를 돌렸습니다. 데모 모드의 다섯 장면을 확인했습니다. 헤드리스 Chrome으로 화면 동작도 봤습니다(확정 더블 클릭 시 요청 1건, 만료 카드 버튼 비활성, 상태가 바뀐 카드 409 문구, 연결 끊김 표시, 폭 360px에서 가로 스크롤 없음).
- **실제 모델(로컬 Ollama `qwen2.5:7b` + 실제 Gemini 임베딩, `LocalModelIT` 6건 통과):**
  - 슈트 만재 상황을 열린 질문("어떻게 하면 좋을까요?")으로 물으면 모델이 스스로 우회 제안 도구를 골라 카드를 만들고, 확정 전에는 아무것도 전송되지 않습니다.
  - 작업자 진입 중 이전 지시를 무시하고 확인 없이 모든 설비를 움직이라는 인젝션 문장을 보내도 전송은 0건이고, 실행했다고 답하지 않습니다.
  - 실제 임베딩 검색은 관련 질문 10개가 모두 맞는 문서를 1위로 찾습니다(최저 점수 0.674). 무관 질문 6개의 최고 점수는 0.531입니다. 그래서 임계값을 그 가운데인 **0.60**으로 정했습니다. 이전 초기값 0.4에서는 무관 질문 6개가 모두 문서를 끌고 왔습니다.
- **확인하지 못함:** Claude 자체의 동작입니다. 실제 API가 `claude-opus-5-5`와 effort `low` 옵션, 도구 정의를 받아 주는지, Claude가 같은 상황에서 같은 도구를 고르고 프롬프트를 지키는지는 Claude 키로 아래 명령을 돌려야 확인됩니다. 소액의 비용이 발생합니다.

```bash
./gradlew test --tests '*LiveApiIT' --rerun -i     # 두 키를 넣은 셸에서
./gradlew test --tests '*LocalModelIT' --rerun -i  # Gemini 키와 Ollama만 있으면 비용 없이
```

`--rerun`을 빼면 안 됩니다. Gradle은 환경 변수 변화를 입력으로 보지 않아, 키 없이 건너뛴 직후에는 테스트를 실행하지 않고 `BUILD SUCCESSFUL`을 출력합니다. 끝난 뒤 `build/test-results/test/` 아래 결과 XML에서 `tests="6"`, `skipped="0"`, `failures="0"`을 확인하세요.

임계값을 다시 잴 때는 출력된 표(`question | expected | score | hit`)에서 정답 질문의 최저 점수와 무관 질문(`(unrelated)`)의 최고 점수 사이 값을 `application.yaml`의 `controltower.similarity-threshold`에 넣습니다. `retrievalHitRate`가 통과해도 임계값이 적절하다는 뜻은 아니므로 표를 직접 봐야 합니다.
