package com.astrastudio.astracurrency.economy;

import com.astrastudio.astracurrency.api.TransactionStatus;
import com.astrastudio.astracurrency.currency.CurrencyDefinition;
import com.astrastudio.astracurrency.storage.SqliteStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void transferIsAtomicTaxedAndSurvivesRestart() throws Exception {
        CurrencyDefinition currency = currency("10");
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        Path db = temporaryDirectory.resolve("economy.db");

        SqliteStorage first = new SqliteStorage(db, temporaryDirectory.resolve("backups"), 5000, 64);
        first.initialize();
        first.adjust("seed-sender", null, sender, currency, new BigDecimal("100.00"),
                SqliteStorage.Adjustment.SET, "ADMIN_SET", "test seed").join();
        var result = first.transfer("transfer-1", sender, recipient, currency, new BigDecimal("10.00")).join();
        assertTrue(result.successful());
        assertEquals(new BigDecimal("1.00"), result.tax());
        assertEquals(new BigDecimal("89.00"), first.getBalance(sender, currency).join());
        assertEquals(new BigDecimal("10.00"), first.getBalance(recipient, currency).join());
        first.close();

        SqliteStorage reopened = new SqliteStorage(db, temporaryDirectory.resolve("backups"), 5000, 64);
        reopened.initialize();
        assertEquals(new BigDecimal("89.00"), reopened.getBalance(sender, currency).join());
        assertEquals(new BigDecimal("10.00"), reopened.getBalance(recipient, currency).join());
        reopened.close();
    }

    @Test
    void duplicateRequestCannotApplyTwice() throws Exception {
        CurrencyDefinition currency = currency("0");
        UUID player = UUID.randomUUID();
        SqliteStorage storage = new SqliteStorage(temporaryDirectory.resolve("dupe.db"),
                temporaryDirectory.resolve("backups"), 5000, 64);
        storage.initialize();

        var first = storage.adjust("same-request", null, player, currency, new BigDecimal("5.00"),
                SqliteStorage.Adjustment.DEPOSIT, "DEPOSIT", "test").join();
        var duplicate = storage.adjust("same-request", null, player, currency, new BigDecimal("5.00"),
                SqliteStorage.Adjustment.DEPOSIT, "DEPOSIT", "test").join();

        assertEquals(TransactionStatus.SUCCESS, first.status());
        assertEquals(TransactionStatus.DUPLICATE_REQUEST, duplicate.status());
        assertEquals(new BigDecimal("5.00"), storage.getBalance(player, currency).join());
        storage.close();
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawOrLoseUpdates() throws Exception {
        CurrencyDefinition currency = currency("0");
        UUID player = UUID.randomUUID();
        SqliteStorage storage = new SqliteStorage(temporaryDirectory.resolve("concurrent.db"),
                temporaryDirectory.resolve("backups"), 5000, 64);
        storage.initialize();
        storage.adjust("seed-balance", null, player, currency, new BigDecimal("100.00"),
                SqliteStorage.Adjustment.SET, "ADMIN_SET", "test seed").join();
        List<CompletableFuture<com.astrastudio.astracurrency.api.TransactionResult>> operations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            operations.add(storage.adjust("withdraw-" + i, null, player, currency, new BigDecimal("20.00"),
                    SqliteStorage.Adjustment.WITHDRAW, "WITHDRAW", "concurrency test"));
        }
        CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new)).join();

        long successCount = operations.stream().map(CompletableFuture::join).filter(result -> result.status() == TransactionStatus.SUCCESS).count();
        assertEquals(5, successCount);
        assertEquals(new BigDecimal("0.00"), storage.getBalance(player, currency).join());
        storage.close();
    }

    private CurrencyDefinition currency(String tax) throws Exception {
        String yaml = "id: coins\nenabled: true\ndisplay:\n  name: Coins\n  singular: Coin\n  plural: Coins\n  symbol: '$'\n"
                + "formatting:\n  decimals: 2\n  grouping: true\n  pattern: '{symbol} {amount}'\n  locale: en-US\n  rounding-mode: DOWN\n"
                + "economy:\n  default-balance: '0.00'\n  minimum-balance: '0.00'\n  maximum-balance: '1000.00'\n  transaction-tax: '" + tax + "'\n";
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return CurrencyDefinition.from(configuration);
    }
}
