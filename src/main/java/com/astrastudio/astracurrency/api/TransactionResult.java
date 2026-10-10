package com.astrastudio.astracurrency.api;

import java.math.BigDecimal;
import java.util.Optional;

/** Result of a committed or rejected transaction. Balances are exact decimals. */
public record TransactionResult(
        TransactionStatus status,
        BigDecimal amount,
        BigDecimal tax,
        BigDecimal senderBalance,
        BigDecimal recipientBalance,
        TransactionRecord transaction
) {
    public boolean successful() {
        return status == TransactionStatus.SUCCESS;
    }

    public Optional<TransactionRecord> transactionOptional() {
        return Optional.ofNullable(transaction);
    }

    public static TransactionResult rejected(TransactionStatus status, BigDecimal amount,
                                             BigDecimal senderBalance, BigDecimal recipientBalance) {
        return new TransactionResult(status, amount, BigDecimal.ZERO, senderBalance, recipientBalance, null);
    }
}
