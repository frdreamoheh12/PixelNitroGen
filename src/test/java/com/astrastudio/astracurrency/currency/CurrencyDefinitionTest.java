package com.astrastudio.astracurrency.currency;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrencyDefinitionTest {
    @Test
    void parsesDefaultStyleYamlAndPreservesPrecision() throws Exception {
        CurrencyDefinition currency = CurrencyDefinition.from(load(yaml(2, "100.00", "0.00", "1000.00")));

        assertEquals("coins", currency.id());
        assertEquals(2, currency.decimalPlaces());
        assertEquals(new BigDecimal("100.00"), currency.defaultBalance());
        assertEquals(new BigDecimal("1.23"), currency.normalizeAmount(new BigDecimal("1.2300")));
    }

    @Test
    void rejectsPrecisionLossAndInvalidLimits() throws Exception {
        CurrencyDefinition currency = CurrencyDefinition.from(load(yaml(2, "0.00", "0.00", "1000.00")));
        assertThrows(IllegalArgumentException.class, () -> currency.normalizeAmount(new BigDecimal("1.234")));
        assertThrows(IllegalArgumentException.class, () -> CurrencyDefinition.from(load(yaml(0, "0.1", "0", "1000"))));
    }

    @Test
    void negativeBalancesUseAConfiguredOrDerivedNegativeFloor() throws Exception {
        CurrencyDefinition currency = CurrencyDefinition.from(load(
                "id: test\nenabled: true\ndisplay:\n  name: Test\n  singular: Test\n  plural: Tests\n  symbol: T\n"
                        + "formatting:\n  decimals: 2\n  locale: en-US\n"
                        + "economy:\n  allow-negative: true\n  maximum-balance: '500.00'\n  default-balance: '0.00'\n"));

        assertEquals(new BigDecimal("-500.00"), currency.minimumBalance());
    }

    private static YamlConfiguration load(String content) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(content);
        return configuration;
    }

    private static String yaml(int decimals, String defaultBalance, String minimum, String maximum) {
        return "id: coins\nenabled: true\ndisplay:\n  name: Coins\n  singular: Coin\n  plural: Coins\n  symbol: '$'\n"
                + "formatting:\n  decimals: " + decimals + "\n  locale: en-US\n  grouping: true\n  rounding-mode: DOWN\n"
                + "economy:\n  default-balance: '" + defaultBalance + "'\n  minimum-balance: '" + minimum
                + "'\n  maximum-balance: '" + maximum + "'\n  transaction-tax: '0'\n";
    }
}
