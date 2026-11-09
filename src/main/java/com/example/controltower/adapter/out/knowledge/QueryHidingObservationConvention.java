package com.example.controltower.adapter.out.knowledge;

import io.micrometer.common.KeyValues;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.stereotype.Component;

/**
 * 기본 규칙은 검색어 원문(사용자 질문이나 재작성한 질문)을 추적 속성 db.vector.query.content로 남긴다.
 * 이 값은 끄는 설정이 없어 규칙을 바꿔 뺀다. pgvector 자동 구성이 이 빈을 받는다.
 */
@Component
class QueryHidingObservationConvention extends DefaultVectorStoreObservationConvention {

    @Override
    protected KeyValues queryContent(KeyValues keyValues, VectorStoreObservationContext context) {
        return keyValues;
    }
}
