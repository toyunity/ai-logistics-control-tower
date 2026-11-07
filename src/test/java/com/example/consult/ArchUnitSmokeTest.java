package com.example.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.consult.chat.ChatService;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchUnitSmokeTest {

    @Test
    void readsJava25ClassFiles() {
        var classes = new ClassFileImporter().importPackages("com.example.consult");

        // 지원하지 않는 바이트코드 버전은 예외 없이 건너뛸 수 있어 건수만으로는 부족하다.
        assertThat(classes.contain(ChatService.class)).isTrue();
    }
}
