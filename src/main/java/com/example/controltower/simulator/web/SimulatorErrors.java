package com.example.controltower.simulator.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 제조사 장비처럼 오류에 본문을 주지 않는다. WebFlux 기본 처리기는 처리되지 않은 500을 경로(요청이 보낸 ID 포함)와
 * 예외 문구째 남기므로 여기서 클래스 이름만 남긴다. 업무 코드의 WebErrors와는 HTTP로만 만나는 규칙 때문에 따로 둔다.
 */
@RestControllerAdvice(basePackageClasses = SimulatorErrors.class)
class SimulatorErrors {

    private static final Logger log = LoggerFactory.getLogger(SimulatorErrors.class);

    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> handle(Exception e) {
        if (e instanceof ErrorResponse rejected) {
            return ResponseEntity.status(rejected.getStatusCode()).build();
        }
        log.error("simulator request failed: {}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
