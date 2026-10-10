package com.astrastudio.astracurrency.api;

import com.astrastudio.astracurrency.currency.CurrencyDefinition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public plugin service. All storage-backed calls are asynchronous and must not be joined on the
 * Paper server thread. Future completion runs on a bounded storage worker unless documented otherwise.
 */
public interface AstraCurrencyAPI {
    Optional<CurrencyDefinition> getCurrency(String id);

    List<CurrencyDefinition> getCurrencies();

    CompletableFuture<BigDecimal> getBalance(UUID playerId, String currencyId);

    /** A non-blocking cache lookup intended for integrations such as PlaceholderAPI. */
    Optional<BigDecimal> getCachedBalance(UUID playerId, String currencyId);

    CompletableFuture<TransactionResult> deposit(UUID playerId, String currencyId, BigDecimal amount,
                                                 String reason, String idempotencyKey);

    CompletableFuture<TransactionResult> withdraw(UUID playerId, String currencyId, BigDecimal amount,
                                                  String reason, String idempotencyKey);

    CompletableFuture<TransactionResult> setBalance(UUID playerId, String currencyId, BigDecimal amount,
                                                    String reason, String idempotencyKey);

    CompletableFuture<TransactionResult> transfer(UUID senderId, UUID recipientId, String currencyId,
                                                  BigDecimal amount, String idempotencyKey);

    CompletableFuture<List<LeaderboardEntry>> getTopBalances(String currencyId, int limit);

    CompletableFuture<Integer> getPlayerRank(UUID playerId, String currencyId);

    CompletableFuture<List<TransactionRecord>> getHistory(UUID playerId, String currencyId, int page, int pageSize);
}
