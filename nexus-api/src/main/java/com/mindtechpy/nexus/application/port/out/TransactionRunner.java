package com.mindtechpy.nexus.application.port.out;

import java.util.function.Supplier;

/** Runs a unit of work atomically, so a change and its audit event commit together. */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
