package com.astrastudio.astracurrency.integration;

import com.astrastudio.astracurrency.AstraCurrencyEngine;
import com.astrastudio.astracurrency.currency.CurrencyDefinition;
import com.astrastudio.astracurrency.economy.BalanceFormatter;
import com.astrastudio.astracurrency.economy.EconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional PlaceholderAPI expansion; every request is served from already-refreshed memory caches. */
public final class AstraPlaceholderExpansion extends PlaceholderExpansion {
    private static final Pattern TOP_PATTERN = Pattern.compile("top_(\\d+)_(name|balance|formatted)");

    private final AstraCurrencyEngine plugin;
    private final EconomyService economy;
    private final BalanceFormatter formatter;
    private final String primaryCurrency;

    public AstraPlaceholderExpansion(AstraCurrencyEngine plugin, EconomyService economy,
                                     BalanceFormatter formatter, String primaryCurrency) {
        this.plugin = plugin;
        this.economy = economy;
        this.formatter = formatter;
        this.primaryCurrency = primaryCurrency;
    }

    @Override
    public String getIdentifier() {
        return "astracurrency";
    }

    @Override
    public String getAuthor() {
        return "AstraStudio";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String rawParams) {
        if (rawParams == null) {
            return "";
        }
        String params = rawParams.toLowerCase(Locale.ROOT);
        if (params.equals("cureency_balance")) {
            return balance(player, primaryCurrency, false);
        }
        if (params.equals("currency_balance_formmated")) {
            return balance(player, primaryCurrency, true);
        }
        if (params.equals("cureency_top")) {
            return top(primaryCurrency, 1, "name");
        }

        for (CurrencyDefinition currency : economy.getAllCurrencies()) {
            String plainPrefix = currency.id() + "_";
            String explicitPrefix = "currency_" + currency.id() + "_";
            String operation;
            if (params.startsWith(explicitPrefix)) {
                operation = params.substring(explicitPrefix.length());
            } else if (params.startsWith(plainPrefix)) {
                operation = params.substring(plainPrefix.length());
            } else {
                continue;
            }
            return resolve(player, currency, operation);
        }
        return "";
    }

    private String resolve(OfflinePlayer player, CurrencyDefinition currency, String operation) {
        return switch (operation) {
            case "balance" -> balance(player, currency.id(), false);
            case "balance_formatted" -> balance(player, currency.id(), true);
            case "symbol" -> currency.symbol();
            case "name" -> currency.displayName();
            case "enabled" -> Boolean.toString(currency.enabled());
            case "total" -> economy.cachedTotal(currency.id()).map(BigDecimal::toPlainString).orElse("");
            case "rank" -> rank(player, currency.id());
            default -> {
                Matcher matcher = TOP_PATTERN.matcher(operation);
                if (!matcher.matches()) {
                    yield "";
                }
                int position;
                try {
                    position = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ex) {
                    yield "";
                }
                if (position < 1 || position > 100) {
                    yield "";
                }
                yield top(currency.id(), position, matcher.group(2));
            }
        };
    }

    private String balance(OfflinePlayer player, String currencyId, boolean formatted) {
        if (player == null) {
            return "";
        }
        Optional<BigDecimal> value = economy.getCachedBalance(player.getUniqueId(), currencyId);
        Optional<CurrencyDefinition> currency = economy.getCurrency(currencyId);
        if (value.isEmpty()) {
            economy.warmBalance(player.getUniqueId(), currencyId);
            return "";
        }
        if (currency.isEmpty()) {
            return "";
        }
        return formatted ? formatter.format(value.get(), currency.get()) : value.get().toPlainString();
    }

    private String rank(OfflinePlayer player, String currencyId) {
        if (player == null) {
            return "";
        }
        if (economy.getCachedBalance(player.getUniqueId(), currencyId).isEmpty()) {
            economy.warmBalance(player.getUniqueId(), currencyId);
            return "";
        }
        int rank = economy.cachedRank(player.getUniqueId(), currencyId);
        return rank == 0 ? "" : Integer.toString(rank);
    }

    private String top(String currencyId, int position, String field) {
        return economy.cachedLeaderboardEntry(currencyId, position)
                .map(entry -> switch (field) {
                    case "name" -> entry.lastKnownName();
                    case "balance" -> entry.balance().toPlainString();
                    case "formatted" -> economy.getCurrency(currencyId)
                            .map(currency -> formatter.format(entry.balance(), currency)).orElse("");
                    default -> "";
                }).orElse("");
    }
}
