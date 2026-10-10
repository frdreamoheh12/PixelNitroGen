package com.astrastudio.astracurrency.economy;

import com.astrastudio.astracurrency.AstraCurrencyEngine;
import com.astrastudio.astracurrency.api.AstraCurrencyAPI;
import com.astrastudio.astracurrency.api.CurrencyTransactionEvent;
import com.astrastudio.astracurrency.api.LeaderboardEntry;
import com.astrastudio.astracurrency.api.TransactionRecord;
import com.astrastudio.astracurrency.api.TransactionResult;
import com.astrastudio.astracurrency.api.TransactionStatus;
import com.astrastudio.astracurrency.currency.CurrencyDefinition;
import com.astrastudio.astracurrency.currency.CurrencyRegistry;
import com.astrastudio.astracurrency.storage.SqliteStorage;
import com.astrastudio.astracurrency.util.AuditLogger;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe economy facade exposed through Bukkit's ServicesManager. */
public final class EconomyService implements AstraCurrencyAPI {
    private final AstraCurrencyEngine plugin;
    private final CurrencyRegistry registry;
    private final SqliteStorage storage;
    private final AuditLogger auditLogger;
    private final Map<AccountKey, BigDecimal> balanceCache = new ConcurrentHashMap<>();
    private final java.util.Set<AccountKey> pendingBalanceLookups = ConcurrentHashMap.newKeySet();
    private final Map<AccountKey, Integer> rankCache = new ConcurrentHashMap<>();
    private final java.util.Set<AccountKey> pendingRankLookups = ConcurrentHashMap.newKeySet();
    private final Map<String, LeaderboardSnapshot> leaderboardCache = new ConcurrentHashMap<>();

    public EconomyService(AstraCurrencyEngine plugin, CurrencyRegistry registry,
                          SqliteStorage storage, AuditLogger auditLogger) {
        this.plugin = plugin;
        this.registry = registry;
        this.storage = storage;
        this.auditLogger = auditLogger;
    }

    @Override
    public Optional<CurrencyDefinition> getCurrency(String id) {
        return registry.find(id);
    }

    @Override
    public List<CurrencyDefinition> getCurrencies() {
        return registry.enabledCurrencies();
    }

    public List<CurrencyDefinition> getAllCurrencies() {
        return List.copyOf(registry.allCurrencies());
    }

    @Override
    public CompletableFuture<BigDecimal> getBalance(UUID playerId, String currencyId) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown or disabled currency: " + currencyId));
        }
        return storage.getBalance(playerId, currency).thenApply(balance -> {
            balanceCache.put(new AccountKey(playerId, currency.id()), balance);
            return balance;
        });
    }

    @Override
    public Optional<BigDecimal> getCachedBalance(UUID playerId, String currencyId) {
        if (playerId == null || currencyId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(balanceCache.get(new AccountKey(playerId, currencyId.toLowerCase(Locale.ROOT))));
    }

    /** Schedules a single async cache fill for a placeholder lookup; it never blocks the caller. */
    public void warmBalance(UUID playerId, String currencyId) {
        if (playerId == null || currencyId == null) {
            return;
        }
        String normalizedId = currencyId.toLowerCase(Locale.ROOT);
        AccountKey key = new AccountKey(playerId, normalizedId);
        if (balanceCache.containsKey(key) || !pendingBalanceLookups.add(key)) {
            return;
        }
        getBalance(playerId, normalizedId).whenComplete((ignored, error) -> {
            pendingBalanceLookups.remove(key);
            if (error != null) {
                plugin.getLogger().warning("Could not warm placeholder balance for " + playerId + ": " + error.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<TransactionResult> deposit(UUID playerId, String currencyId, BigDecimal amount,
                                                        String reason, String idempotencyKey) {
        return adjust(null, playerId, currencyId, amount, SqliteStorage.Adjustment.DEPOSIT,
                "DEPOSIT", reason, idempotencyKey);
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(UUID playerId, String currencyId, BigDecimal amount,
                                                         String reason, String idempotencyKey) {
        return adjust(null, playerId, currencyId, amount, SqliteStorage.Adjustment.WITHDRAW,
                "WITHDRAW", reason, idempotencyKey);
    }

    @Override
    public CompletableFuture<TransactionResult> setBalance(UUID playerId, String currencyId, BigDecimal amount,
                                                           String reason, String idempotencyKey) {
        return adjust(null, playerId, currencyId, amount, SqliteStorage.Adjustment.SET,
                "ADMIN_SET", reason, idempotencyKey);
    }

    public CompletableFuture<TransactionResult> adminAdjust(UUID actorId, UUID accountId, String currencyId,
                                                            BigDecimal amount, SqliteStorage.Adjustment adjustment,
                                                            String reason, String idempotencyKey) {
        String type = switch (adjustment) {
            case DEPOSIT -> "ADMIN_GIVE";
            case WITHDRAW -> "ADMIN_TAKE";
            case SET -> "ADMIN_SET";
        };
        return adjust(actorId, accountId, currencyId, amount, adjustment, type, reason, idempotencyKey);
    }

    public CompletableFuture<TransactionResult> reset(UUID actorId, UUID accountId, String currencyId,
                                                       String idempotencyKey) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return failedCurrency(currencyId);
        }
        return storage.adjust(idempotencyKey, actorId, accountId, currency, currency.defaultBalance(),
                        SqliteStorage.Adjustment.SET, "ADMIN_RESET", "balance reset to currency default")
                .thenApply(result -> completeMutation(result, currency, accountId, null));
    }

    @Override
    public CompletableFuture<TransactionResult> transfer(UUID senderId, UUID recipientId, String currencyId,
                                                         BigDecimal amount, String idempotencyKey) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return failedCurrency(currencyId);
        }
        if (!currency.transferable()) {
            return CompletableFuture.completedFuture(TransactionResult.rejected(
                    TransactionStatus.NOT_TRANSFERABLE, amount, null, null));
        }
        final BigDecimal normalized;
        try {
            normalized = positiveAmount(amount, currency);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return storage.transfer(idempotencyKey, senderId, recipientId, currency, normalized)
                .thenApply(result -> completeMutation(result, currency, senderId, recipientId));
    }

    @Override
    public CompletableFuture<List<LeaderboardEntry>> getTopBalances(String currencyId, int limit) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return failedCurrency(currencyId);
        }
        if (!currency.leaderboardEnabled()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return storage.getTopBalances(currency.id(), limit);
    }

    @Override
    public CompletableFuture<Integer> getPlayerRank(UUID playerId, String currencyId) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return failedCurrency(currencyId);
        }
        return storage.getPlayerRank(playerId, currency.id()).thenApply(rank -> {
            rankCache.put(new AccountKey(playerId, currency.id()), rank);
            return rank;
        });
    }

    @Override
    public CompletableFuture<List<TransactionRecord>> getHistory(UUID playerId, String currencyId, int page, int pageSize) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return failedCurrency(currencyId);
        }
        return storage.getHistory(playerId, currency.id(), page, pageSize);
    }

    public CompletableFuture<Integer> resetAll(String currencyId, String requestId) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return failedCurrency(currencyId);
        }
        return storage.resetAll(requestId, currency).thenApply(changed -> {
            balanceCache.keySet().removeIf(key -> key.currencyId().equals(currency.id()));
            pendingBalanceLookups.removeIf(key -> key.currencyId().equals(currency.id()));
            rankCache.keySet().removeIf(key -> key.currencyId().equals(currency.id()));
            refreshLeaderboard(currency);
            return changed;
        });
    }

    public CompletableFuture<Integer> deleteCurrencyBalances(String currencyId) {
        return storage.deleteCurrencyBalances(currencyId).thenApply(deleted -> {
            balanceCache.keySet().removeIf(key -> key.currencyId().equals(currencyId));
            pendingBalanceLookups.removeIf(key -> key.currencyId().equals(currencyId));
            rankCache.keySet().removeIf(key -> key.currencyId().equals(currencyId));
            leaderboardCache.remove(currencyId);
            return deleted;
        });
    }

    public CompletableFuture<java.util.Optional<UUID>> findPlayerIdByName(String name) {
        return storage.findPlayerIdByName(name);
    }

    public CompletableFuture<Boolean> balancesFitCurrency(CurrencyDefinition currency) {
        return storage.balancesFitCurrency(currency);
    }

    public CompletableFuture<String> createBackup() {
        return storage.createBackup();
    }

    public CompletableFuture<Void> restoreBackup(String backupId) {
        return storage.restoreBackup(backupId).thenRun(() -> {
            balanceCache.clear();
            pendingBalanceLookups.clear();
            rankCache.clear();
            refreshLeaderboards();
        });
    }

    public CompletableFuture<BigDecimal> getTotal(String currencyId) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return failedCurrency(currencyId);
        }
        return storage.getTotal(currency.id());
    }

    public void refreshLeaderboards() {
        for (CurrencyDefinition currency : registry.enabledCurrencies()) {
            refreshLeaderboard(currency);
        }
    }

    public void refreshLeaderboard(String currencyId) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency != null) {
            refreshLeaderboard(currency);
        }
    }

    public Optional<LeaderboardEntry> cachedLeaderboardEntry(String currencyId, int rank) {
        if (rank < 1) {
            return Optional.empty();
        }
        LeaderboardSnapshot snapshot = leaderboardCache.get(currencyId.toLowerCase(Locale.ROOT));
        if (snapshot == null || rank > snapshot.entries().size()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.entries().get(rank - 1));
    }

    public int cachedRank(UUID playerId, String currencyId) {
        String normalizedId = currencyId.toLowerCase(Locale.ROOT);
        AccountKey key = new AccountKey(playerId, normalizedId);
        Integer rank = rankCache.get(key);
        if (rank != null) {
            return rank;
        }
        CurrencyDefinition currency = enabledCurrency(normalizedId);
        if (currency != null && pendingRankLookups.add(key)) {
            storage.getPlayerRank(playerId, currency.id()).whenComplete((resolvedRank, error) -> {
                pendingRankLookups.remove(key);
                if (error == null) {
                    rankCache.put(key, resolvedRank);
                } else {
                    plugin.getLogger().warning("Could not update cached rank for " + playerId + ": " + error.getMessage());
                }
            });
        }
        return 0;
    }

    public Optional<BigDecimal> cachedTotal(String currencyId) {
        LeaderboardSnapshot snapshot = leaderboardCache.get(currencyId.toLowerCase(Locale.ROOT));
        return snapshot == null ? Optional.empty() : Optional.of(snapshot.total());
    }

    public int cacheSize() {
        return balanceCache.size();
    }

    public int queuedTasks() {
        return storage.queuedTasks();
    }

    public SqliteStorage storage() {
        return storage;
    }

    public CurrencyRegistry registry() {
        return registry;
    }

    private CompletableFuture<TransactionResult> adjust(UUID actor, UUID account, String currencyId,
                                                        BigDecimal amount, SqliteStorage.Adjustment adjustment,
                                                        String type, String reason, String requestId) {
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            return failedCurrency(currencyId);
        }
        final BigDecimal normalized;
        try {
            normalized = adjustment == SqliteStorage.Adjustment.SET
                    ? currency.normalizeAmount(amount) : positiveAmount(amount, currency);
            if (adjustment == SqliteStorage.Adjustment.SET && !currency.isWithinLimits(normalized)) {
                throw new IllegalArgumentException("target balance is outside configured limits");
            }
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        return storage.adjust(requestId, actor, account, currency, normalized, adjustment, type, reason)
                .thenApply(result -> completeMutation(result, currency, account, null));
    }

    private TransactionResult completeMutation(TransactionResult result, CurrencyDefinition currency,
                                               UUID senderId, UUID recipientId) {
        if (!result.successful() || result.transaction() == null) {
            return result;
        }
        if (senderId != null && result.senderBalance() != null) {
            balanceCache.put(new AccountKey(senderId, currency.id()), result.senderBalance());
        }
        if (recipientId != null && result.recipientBalance() != null) {
            balanceCache.put(new AccountKey(recipientId, currency.id()), result.recipientBalance());
        }
        rankCache.keySet().removeIf(key -> key.currencyId().equals(currency.id()));
        TransactionRecord transaction = result.transaction();
        try {
            auditLogger.transaction(transaction.type(), transaction.currencyId(),
                    transaction.sender() == null ? null : transaction.sender().toString(),
                    transaction.recipient() == null ? null : transaction.recipient().toString(),
                    transaction.amount().toPlainString(), transaction.tax().toPlainString(), transaction.reason());
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not append economy audit record: " + ex.getMessage());
        }
        plugin.runOnMain(() -> Bukkit.getPluginManager().callEvent(new CurrencyTransactionEvent(transaction)));
        return result;
    }

    private void refreshLeaderboard(CurrencyDefinition currency) {
        if (!currency.leaderboardEnabled()) {
            leaderboardCache.remove(currency.id());
            return;
        }
        storage.getLeaderboardSnapshot(currency.id()).thenCombine(storage.getTotal(currency.id()), (entries, total) -> {
            Map<UUID, Integer> ranks = new ConcurrentHashMap<>();
            for (LeaderboardEntry entry : entries) {
                ranks.put(entry.playerId(), entry.rank());
            }
            return new LeaderboardSnapshot(entries, Map.copyOf(ranks), total);
        }).whenComplete((snapshot, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Could not refresh " + currency.id() + " leaderboard: " + error.getMessage());
            } else {
                leaderboardCache.put(currency.id(), snapshot);
                for (LeaderboardEntry entry : snapshot.entries()) {
                    rankCache.put(new AccountKey(entry.playerId(), currency.id()), entry.rank());
                }
            }
        });
    }

    private CurrencyDefinition enabledCurrency(String id) {
        return registry.find(id).filter(CurrencyDefinition::enabled).orElse(null);
    }

    private <T> CompletableFuture<T> failedCurrency(String currencyId) {
        return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown or disabled currency: " + currencyId));
    }

    private static BigDecimal positiveAmount(BigDecimal amount, CurrencyDefinition currency) {
        BigDecimal normalized = currency.normalizeAmount(amount);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        return normalized;
    }

    private record AccountKey(UUID playerId, String currencyId) {}
    private record LeaderboardSnapshot(List<LeaderboardEntry> entries, Map<UUID, Integer> ranks, BigDecimal total) {}
}
