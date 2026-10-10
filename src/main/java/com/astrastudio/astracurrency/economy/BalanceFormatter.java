package com.astrastudio.astracurrency.economy;

import com.astrastudio.astracurrency.currency.CurrencyDefinition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Objects;

/** Exact-decimal display formatting. The raw balance is never converted through double. */
public final class BalanceFormatter {
    public String format(BigDecimal amount, CurrencyDefinition currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        BigDecimal scaled = amount.setScale(currency.decimalPlaces(), currency.roundingMode());
        String number = number(scaled, currency);
        return currency.formatPattern()
                .replace("{symbol}", currency.symbol())
                .replace("{amount}", number)
                .replace("{amount_compact}", compact(scaled, currency));
    }

    public String formatCompact(BigDecimal amount, CurrencyDefinition currency) {
        BigDecimal scaled = amount.setScale(currency.decimalPlaces(), currency.roundingMode());
        return currency.compactPattern()
                .replace("{symbol}", currency.symbol())
                .replace("{amount}", number(scaled, currency))
                .replace("{amount_compact}", compact(scaled, currency));
    }

    public String number(BigDecimal amount, CurrencyDefinition currency) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(currency.locale());
        DecimalFormat decimalFormat = new DecimalFormat("#,##0", symbols);
        decimalFormat.setGroupingUsed(currency.grouping());
        decimalFormat.setRoundingMode(currency.roundingMode());
        decimalFormat.setMinimumFractionDigits(currency.decimalPlaces());
        decimalFormat.setMaximumFractionDigits(currency.decimalPlaces());
        return decimalFormat.format(amount);
    }

    private String compact(BigDecimal amount, CurrencyDefinition currency) {
        BigDecimal abs = amount.abs();
        BigDecimal divisor;
        String suffix;
        if (abs.compareTo(new BigDecimal("1000000000")) >= 0) {
            divisor = new BigDecimal("1000000000");
            suffix = "B";
        } else if (abs.compareTo(new BigDecimal("1000000")) >= 0) {
            divisor = new BigDecimal("1000000");
            suffix = "M";
        } else if (abs.compareTo(new BigDecimal("1000")) >= 0) {
            divisor = new BigDecimal("1000");
            suffix = "K";
        } else {
            return number(amount, currency);
        }
        BigDecimal compact = amount.divide(divisor, Math.min(2, currency.decimalPlaces() == 0 ? 1 : currency.decimalPlaces()),
                RoundingMode.DOWN);
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(currency.locale());
        DecimalFormat format = new DecimalFormat("0.##", symbols);
        format.setRoundingMode(RoundingMode.DOWN);
        return format.format(compact) + suffix;
    }
}
