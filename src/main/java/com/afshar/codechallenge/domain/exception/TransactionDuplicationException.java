package com.afshar.codechallenge.domain.exception;

public class TransactionDuplicationException extends RuntimeException {
    public TransactionDuplicationException(String message) {
        super(message);
    }

    public TransactionDuplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
