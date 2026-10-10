package com.astrastudio.astracurrency.api;

public enum TransactionStatus {
    SUCCESS,
    INSUFFICIENT_FUNDS,
    BALANCE_LIMIT,
    CURRENCY_DISABLED,
    NOT_TRANSFERABLE,
    SAME_ACCOUNT,
    DUPLICATE_REQUEST,
    NOT_FOUND,
    NO_CHANGE
}
