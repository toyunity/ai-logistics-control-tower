package com.example.controltower.adapter.in.web;

import com.example.controltower.application.port.in.MaintenanceAdmin;
import com.example.controltower.domain.Actor;
import com.example.controltower.domain.Role;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MaintenanceController {

    private final MaintenanceAdmin admin;

    public MaintenanceController(MaintenanceAdmin admin) {
        this.admin = admin;
    }

    @PostMapping("/api/maintenance/{id}/close")
    ResponseEntity<ApiResult> close(@PathVariable UUID id, HttpServletRequest request) {
        Actor actor = SessionActor.of(request);
        if (admin.close(id, actor)) {
            return ResponseEntity.ok(new ApiResult("CLOSED", "정비 건을 완료 처리했습니다."));
        }
        // 서비스는 권한 없음과 없는 건을 구분하지 않으므로 응답 코드만 여기서 가른다.
        return actor.role() == Role.OPERATOR
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiResult(null, "열린 정비 건을 찾을 수 없습니다."))
                : ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiResult(null, "권한이 없습니다."));
    }
}
