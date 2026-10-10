package com.astrastudio.astracurrency.api

import com.astrastudio.astracurrency.currency.CurrencyDefinition
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Kotlin-friendly helpers. Storage operations remain asynchronous; do not call join() on Paper's main thread. */
fun AstraCurrencyAPI.requireCurrency(id: String): CurrencyDefinition =
    getCurrency(id).orElseThrow { IllegalArgumentException("Unknown currency: $id") }

fun AstraCurrencyAPI.balanceAsync(playerId: UUID, currencyId: String): CompletableFuture<BigDecimal> =
    getBalance(playerId, currencyId)
