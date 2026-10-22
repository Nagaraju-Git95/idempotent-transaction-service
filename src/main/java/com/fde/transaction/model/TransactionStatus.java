package com.fde.transaction.model;

public enum TransactionStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    DUPLICATE,
    FAILED,
    RETRY_PENDING
}
