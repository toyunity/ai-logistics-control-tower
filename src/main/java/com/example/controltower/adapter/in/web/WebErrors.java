package com.example.controltower.adapter.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 저장소 예외 메시지에는 SQL 값이 섞일 수 있어 본문과 로그에 넣지 않는다. 스프링의 요청 오류도 입력을 되돌려 주지 않게 고정 문구로 바꾼다.
 * 채팅은 SSE 오류 이벤트로 따로 처리하므로 대상에서 뺀다.
 */
@RestControllerAdvice(assignableTypes = {
    SessionController.class, CommandController.class, PlantController.class, EventController.class, MaintenanceController.class
})
class WebErrors {

    private static final Logger log = LoggerFactory.getLogger(WebErrors.class);

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResult> handle(Exception e) {
        if (e instanceof ErrorResponse rejected) {
            return ResponseEntity.status(rejected.getStatusCode()).body(new ApiResult(null, "요청을 처리할 수 없습니다."));
        }
        if (e instanceof HttpMessageNotReadableException || e instanceof TypeMismatchException) {
            return ResponseEntity.badRequest().body(new ApiResult(null, "요청을 처리할 수 없습니다."));
        }
        log.error("request failed: {}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResult(null, "일시적인 오류로 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
