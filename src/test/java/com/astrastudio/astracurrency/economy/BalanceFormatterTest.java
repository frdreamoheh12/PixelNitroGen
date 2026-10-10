package com.astrastudio.astracurrency.economy;

import com.astrastudio.astracurrency.currency.CurrencyDefinition;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BalanceFormatterTest {
    @Test
    void formatsExactDecimalWithLocaleGrouping() throws Exception {
        CurrencyDefinition currency = CurrencyDefinition.from(load(
                "id: coins\nenabled: true\ndisplay:\n  name: Coins\n  singular: Coin\n  plural: Coins\n  symbol: '⛁'\n"
                        + "formatting:\n  decimals: 2\n  grouping: true\n  pattern: '{symbol} {amount}'\n  locale: en-US\n  rounding-mode: DOWN\n"
                        + "economy:\n  default-balance: '0.00'\n  minimum-balance: '0.00'\n  maximum-balance: '1000000000.00'\n"));

        assertEquals("⛁ 12,345.67", new BalanceFormatter().format(new BigDecimal("12345.679"), currency));
    }

    @Test
    void supportsCompactSuffixesWithoutFloatingPointConversion() throws Exception {
        CurrencyDefinition currency = CurrencyDefinition.from(load(
                "id: shards\nenabled: true\ndisplay:\n  name: Shards\n  singular: Shard\n  plural: Shards\n  symbol: '✦'\n"
                        + "formatting:\n  decimals: 2\n  grouping: true\n  pattern: '{symbol} {amount}'\n  compact-pattern: '{amount_compact} {symbol}'\n  locale: en-US\n  rounding-mode: DOWN\n"
                        + "economy:\n  default-balance: '0.00'\n  minimum-balance: '0.00'\n  maximum-balance: '1000000000000.00'\n"));

        assertEquals("1.23M ✦", new BalanceFormatter().formatCompact(new BigDecimal("1234567.89"), currency));
    }

    private static YamlConfiguration load(String content) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(content);
        return configuration;
    }
}
