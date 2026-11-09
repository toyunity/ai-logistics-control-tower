# API

대화, 확인 카드, 현황, 이벤트 수신, 시뮬레이터 제어 API와 SSE 이벤트 형식입니다. 개요는 [README](../README.md)에 있습니다.

| 메서드와 경로 | 설명 | 응답 |
|---|---|---|
| `POST /api/session` `{"accountId":"operator"\|"viewer"}` | 샘플 계정 로그인 | 200, 그 외 계정 400 |
| `DELETE /api/session` | 로그아웃 | 204 |
| `POST /api/chat/stream` `{"conversationId"?, "message"}` | 대화(SSE) | 아래 표 |
| `POST /api/commands/{id}/confirm` | 카드 확정. AI가 거치지 않는 경로 | 200 `{status, message}`, 403 권한 없음, 404, 409 상태 변경·만료·이미 처리 |
| `POST /api/commands/{id}/reject` | 카드 거절 | 확정과 같음 |
| `GET /api/plant/status` | 설비 상태, 열린 장애 건, 정비 목록 | 200. 읽지 못한 설비군은 알 수 없음으로 표시 |
| `POST /api/events` | 게이트웨이 이벤트 수신 | 200(중복 포함), 형식 오류 400 |
| `POST /api/maintenance/{id}/close` | 정비 완료 | 200, 403, 404 |
| `GET /api/knowledge/search?q=` | 지식 문서 검색 | 200 |
| `POST /sim/scenarios/{name}`, `POST /sim/reset` | 시뮬레이터 제어(데모용) | 200, 404, 409 |

응답 본문의 `message`는 고정 문구입니다. 예외 내용을 내보내지 않습니다.

## SSE 이벤트

POST이므로 브라우저에서는 `EventSource`가 아니라 `fetch`와 stream reader를 씁니다.

| event | data | 시점 |
|---|---|---|
| `meta` | `{"conversationId"}` | 항상 첫 이벤트 |
| `sources` | `{"items":[{"id","title","score"}]}` (빈 배열 가능) | 첫 `delta` 앞 1회 |
| `delta` | `{"text"}` | 0회 이상 |
| `action` | `{"commandId","kind","targetId","summary","impact","expiresAt"}` | 마지막 `delta` 뒤, `done` 앞. 이번 턴에 만든 카드마다 1회 |
| `done` | `{}` | 정상 종료 |
| `error` | `{"code":"UPSTREAM"\|"REFUSED","message"}` | 실패 종료. 이때 `action`은 나가지 않습니다 |

스트림은 `done` 또는 `error` 중 정확히 하나로 끝납니다. 첫 청크까지의 대기나 청크 사이의 침묵이 `controltower.stream-idle-timeout`(60초)을 넘으면 `UPSTREAM`으로 끝나고, 응답 전체는 `spring.ai.anthropic.timeout`(10분)으로 제한됩니다. `action`의 값은 서버가 저장한 카드 값이며, 정비 카드의 `summary`만 모델이 쓴 문장입니다(화면은 텍스트로만 넣습니다).
