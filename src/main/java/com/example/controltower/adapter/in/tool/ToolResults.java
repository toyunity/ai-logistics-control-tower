package com.example.controltower.adapter.in.tool;

import com.example.controltower.domain.DenialCode;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** 도구가 모델에게 돌려주는 결과. 문구는 모두 고정값이고 예외 내용을 담지 않는다. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ToolResults.Ok.class, name = "OK"),
    @JsonSubTypes.Type(value = ToolResults.NotFound.class, name = "NOT_FOUND"),
    @JsonSubTypes.Type(value = ToolResults.NotPermitted.class, name = "NOT_PERMITTED"),
    @JsonSubTypes.Type(value = ToolResults.Denied.class, name = "DENIED"),
    @JsonSubTypes.Type(value = ToolResults.Unavailable.class, name = "UNAVAILABLE"),
    @JsonSubTypes.Type(value = ToolResults.LimitExceeded.class, name = "LIMIT_EXCEEDED"),
    @JsonSubTypes.Type(value = ToolResults.InvalidArgument.class, name = "INVALID_ARGUMENT")
})
public sealed interface ToolResults {

    NotFound NOT_FOUND = new NotFound(DenialCode.NOT_FOUND.message());
    NotPermitted NOT_PERMITTED = new NotPermitted(DenialCode.NOT_PERMITTED.message());
    Unavailable UNAVAILABLE = new Unavailable("설비 정보를 일시적으로 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    LimitExceeded LIMIT_EXCEEDED = new LimitExceeded("이번 답변의 도구 호출 한도를 넘었습니다. 지금까지 확인한 내용으로 답하세요.");
    InvalidArgument INVALID_ARGUMENT = new InvalidArgument("도구 인자 형식이 올바르지 않습니다. 설비 ID는 문자열로 넘기세요.");

    record Ok(Object data) implements ToolResults {}

    record NotFound(String message) implements ToolResults {}

    record NotPermitted(String message) implements ToolResults {}

    record Denied(String code, String message) implements ToolResults {}

    record Unavailable(String message) implements ToolResults {}

    record LimitExceeded(String message) implements ToolResults {}

    record InvalidArgument(String message) implements ToolResults {}
}
