package com.astrastudio.astracurrency.economy;

import com.astrastudio.astracurrency.currency.CurrencyDefinition;

import java.math.BigDecimal;
import java.util.Objects;

public final class AmountRules {
    private AmountRules() {}

    public static BigDecimal positive(String raw, CurrencyDefinition currency) {
        Objects.requireNonNull(currency, "currency");
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid decimal amount", ex);
        }
        BigDecimal amount = currency.normalizeAmount(parsed);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return amount;
    }

    public static BigDecimal nonNegative(String raw, CurrencyDefinition currency) {
        Objects.requireNonNull(currency, "currency");
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid decimal amount", ex);
        }
        BigDecimal amount = currency.normalizeAmount(parsed);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount cannot be negative");
        }
        return amount;
    }
}
