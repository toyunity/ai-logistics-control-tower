package com.example.controltower.adapter.out.persistence;

import com.example.controltower.application.port.out.PendingCommands;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 전송 중 죽은 프로세스가 남긴 EXECUTING 행을 닫는다. 그대로 두면 그 설비의 다음 명령이 영영 막힌다.
 * 웹 서버가 시작되기 전(싱글턴 생성 직후)에 돈다. 요청을 받은 뒤에 돌면 그 사이 확정이 막힌다.
 * 한계: 인스턴스가 여럿이면 다른 인스턴스의 진행 중인 행도 닫는다. 실제 결과는 그쪽 DecideCommandService가 감사에 남긴다.
 */
@Component
public class StaleCommandCleanup implements SmartInitializingSingleton {

    private final PendingCommands pending;

    public StaleCommandCleanup(PendingCommands pending) {
        this.pending = pending;
    }

    @Override
    public void afterSingletonsInstantiated() {
        pending.markStaleExecutingUnconfirmed();
    }
}
