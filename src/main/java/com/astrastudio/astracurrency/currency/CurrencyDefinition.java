package com.astrastudio.astracurrency.currency;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable, validated configuration for one currency. Amounts are always exact decimals. */
public record CurrencyDefinition(
        String id,
        boolean enabled,
        String displayName,
        String singularName,
        String pluralName,
        String symbol,
        String description,
        int decimalPlaces,
        boolean grouping,
        String formatPattern,
        String compactPattern,
        Locale locale,
        RoundingMode roundingMode,
        BigDecimal defaultBalance,
        BigDecimal minimumBalance,
        BigDecimal maximumBalance,
        boolean transferable,
        boolean allowNegative,
        BigDecimal transactionTaxPercent,
        boolean leaderboardEnabled,
        boolean menuVisible,
        String menuMaterial,
        int menuSlot,
        int customModelData,
        boolean titleNotifications,
        boolean soundNotifications,
        boolean actionbarNotifications
) {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final int MAX_DECIMAL_PLACES = 8;

    public CurrencyDefinition {
        id = normalizeId(id);
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(singularName, "singularName");
        Objects.requireNonNull(pluralName, "pluralName");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(formatPattern, "formatPattern");
        Objects.requireNonNull(compactPattern, "compactPattern");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(roundingMode, "roundingMode");
        Objects.requireNonNull(defaultBalance, "defaultBalance");
        Objects.requireNonNull(minimumBalance, "minimumBalance");
        Objects.requireNonNull(maximumBalance, "maximumBalance");
        Objects.requireNonNull(transactionTaxPercent, "transactionTaxPercent");
        menuMaterial = menuMaterial == null || menuMaterial.isBlank() ? "GOLD_INGOT" : menuMaterial;

        if (displayName.isBlank() || singularName.isBlank() || pluralName.isBlank() || symbol.isBlank()) {
            throw new IllegalArgumentException("Currency display name, singular, plural, and symbol must not be blank");
        }
        if (decimalPlaces < 0 || decimalPlaces > MAX_DECIMAL_PLACES) {
            throw new IllegalArgumentException("decimal places must be between 0 and " + MAX_DECIMAL_PLACES);
        }
        if (menuSlot < 0 || menuSlot > 53) {
            throw new IllegalArgumentException("menu slot must be between 0 and 53");
        }
        if (customModelData < 0) {
            throw new IllegalArgumentException("model data cannot be negative");
        }
        if (minimumBalance.compareTo(maximumBalance) > 0) {
            throw new IllegalArgumentException("minimum balance cannot exceed maximum balance");
        }
        if (defaultBalance.compareTo(minimumBalance) < 0 || defaultBalance.compareTo(maximumBalance) > 0) {
            throw new IllegalArgumentException("default balance must be within configured limits");
        }
        if (transactionTaxPercent.signum() < 0 || transactionTaxPercent.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("transaction-tax must be between 0 and 100 percent");
        }
        defaultBalance = exactScale(defaultBalance, decimalPlaces, "default-balance");
        minimumBalance = exactScale(minimumBalance, decimalPlaces, "minimum-balance");
        maximumBalance = exactScale(maximumBalance, decimalPlaces, "maximum-balance");
        transactionTaxPercent = transactionTaxPercent.stripTrailingZeros();
    }

    public static CurrencyDefinition from(ConfigurationSection root) {
        Objects.requireNonNull(root, "root");
        String id = requiredString(root, "id");
        ConfigurationSection display = requiredSection(root, "display");
        ConfigurationSection formatting = requiredSection(root, "formatting");
        ConfigurationSection economy = requiredSection(root, "economy");
        ConfigurationSection menus = root.getConfigurationSection("menus");
        ConfigurationSection notifications = root.getConfigurationSection("notifications");

        int places = formatting.getInt("decimals", 2);
        boolean negative = economy.getBoolean("allow-negative", false);
        BigDecimal max = decimal(economy, "maximum-balance", "1000000000000");
        BigDecimal min = decimal(economy, "minimum-balance", negative ? max.negate().toPlainString() : "0");
        String localeTag = formatting.getString("locale", "en-US");
        Locale locale = Locale.forLanguageTag(localeTag == null ? "en-US" : localeTag);
        if (locale.getLanguage().isBlank()) {
            locale = Locale.US;
        }
        RoundingMode roundingMode;
        try {
            roundingMode = RoundingMode.valueOf(formatting.getString("rounding-mode", "DOWN").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid formatting.rounding-mode", ex);
        }

        return new CurrencyDefinition(
                id,
                root.getBoolean("enabled", true),
                requiredString(display, "name"),
                display.getString("singular", id),
                display.getString("plural", id + "s"),
                requiredString(display, "symbol"),
                display.getString("description", ""),
                places,
                formatting.getBoolean("grouping", true),
                formatting.getString("pattern", "{symbol} {amount}"),
                formatting.getString("compact-pattern", "{amount_compact} {symbol}"),
                locale,
                roundingMode,
                decimal(economy, "default-balance", "0"),
                min,
                max,
                economy.getBoolean("transferable", true),
                negative,
                decimal(economy, "transaction-tax", "0"),
                economy.getBoolean("leaderboard-enabled", true),
                menus == null || menus.getBoolean("visible", true),
                menus == null ? "GOLD_INGOT" : menus.getString("display-item", "GOLD_INGOT"),
                menus == null ? 0 : menus.getInt("slot", 0),
                menus == null ? 0 : menus.getInt("model-data", 0),
                notifications != null && notifications.getBoolean("title-enabled", false),
                notifications != null && notifications.getBoolean("sound-enabled", false),
                notifications != null && notifications.getBoolean("actionbar-enabled", false)
        );
    }

    public static String normalizeId(String rawId) {
        if (rawId == null) {
            throw new IllegalArgumentException("currency id cannot be null");
        }
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("currency id must match [a-z0-9][a-z0-9_-]{0,31}");
        }
        return normalized;
    }

    /** Rejects excess fractional digits instead of silently rounding a requested transaction. */
    public BigDecimal normalizeAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.scale() > decimalPlaces && amount.stripTrailingZeros().scale() > decimalPlaces) {
            throw new IllegalArgumentException("amount has more than " + decimalPlaces + " decimal places");
        }
        try {
            return amount.setScale(decimalPlaces, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("amount has more than " + decimalPlaces + " decimal places", ex);
        }
    }

    public boolean isWithinLimits(BigDecimal amount) {
        return amount.compareTo(minimumBalance) >= 0 && amount.compareTo(maximumBalance) <= 0;
    }

    private static BigDecimal exactScale(BigDecimal amount, int places, String field) {
        try {
            return amount.setScale(places, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(field + " has more precision than formatting.decimals", ex);
        }
    }

    private static String requiredString(ConfigurationSection section, String path) {
        String value = section.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing or blank setting: " + path);
        }
        return value;
    }

    private static ConfigurationSection requiredSection(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("Missing section: " + path);
        }
        return section;
    }

    private static BigDecimal decimal(ConfigurationSection section, String path, String fallback) {
        String raw = section.getString(path, fallback);
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid decimal setting " + path + ": " + raw, ex);
        }
    }
}
