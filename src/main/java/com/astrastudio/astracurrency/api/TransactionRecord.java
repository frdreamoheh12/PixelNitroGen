package com.astrastudio.astracurrency.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A committed, immutable audit record. Null actor/target values denote an unowned side. */
public record TransactionRecord(
        long id,
        String type,
        String currencyId,
        UUID sender,
        UUID recipient,
        BigDecimal amount,
        BigDecimal tax,
        String reason,
        Instant createdAt
) {}
