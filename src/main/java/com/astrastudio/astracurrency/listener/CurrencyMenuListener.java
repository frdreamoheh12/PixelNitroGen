package com.astrastudio.astracurrency.listener;

import com.astrastudio.astracurrency.gui.CurrencyMenu;
import com.astrastudio.astracurrency.gui.CurrencyMenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class CurrencyMenuListener implements Listener {
    private final CurrencyMenu menu;

    public CurrencyMenuListener(CurrencyMenu menu) {
        this.menu = menu;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CurrencyMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        String currency = holder.currencyAt(event.getSlot());
        if (currency != null) {
            menu.showBalance(player, currency);
        }
    }
}
