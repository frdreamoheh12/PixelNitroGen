package com.astrastudio.astracurrency.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the Paper primary thread after a transaction has committed. It is informational and
 * cannot cancel or roll back the already-committed operation.
 */
public final class CurrencyTransactionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final TransactionRecord transaction;

    public CurrencyTransactionEvent(TransactionRecord transaction) {
        this.transaction = transaction;
    }

    public TransactionRecord getTransaction() {
        return transaction;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
