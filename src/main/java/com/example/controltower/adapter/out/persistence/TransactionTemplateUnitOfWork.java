package com.example.controltower.adapter.out.persistence;

import com.example.controltower.application.port.out.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TransactionTemplateUnitOfWork implements UnitOfWork {

    private final TransactionTemplate transaction;

    public TransactionTemplateUnitOfWork(PlatformTransactionManager transactions) {
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
