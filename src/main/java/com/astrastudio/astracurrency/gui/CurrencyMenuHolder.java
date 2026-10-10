package com.astrastudio.astracurrency.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class CurrencyMenuHolder implements InventoryHolder {
    private Inventory inventory;
    private final Map<Integer, String> currenciesBySlot = new HashMap<>();

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    void setCurrency(int slot, String currencyId) {
        currenciesBySlot.put(slot, currencyId);
    }

    public String currencyAt(int slot) {
        return currenciesBySlot.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
