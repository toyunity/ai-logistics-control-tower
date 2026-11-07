package com.example.controltower.application.port.out;

import java.util.function.Supplier;

public interface UnitOfWork {

    <T> T inTransaction(Supplier<T> work);
}
