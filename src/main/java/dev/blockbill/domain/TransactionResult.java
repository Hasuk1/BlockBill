package dev.blockbill.domain;

public enum TransactionResult {
    SUCCESS,
    INSUFFICIENT_CREDITS,
    INSUFFICIENT_ITEMS,
    INVALID_AMOUNT,
    NOT_SURVIVAL,
    OVERFLOW
}
