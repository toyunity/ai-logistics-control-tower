package com.example.controltower.support;

import com.example.controltower.application.port.out.UnitOfWork;
import java.util.function.Supplier;

/** 즉시 실행한다. 롤백은 흉내 내지 않는다. */
public class InMemoryUnitOfWork implements UnitOfWork {

    private int transactions;

    public int transactions() {
        return transactions;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        transactions++;
        return work.get();
    }
}
