package com.astrastudio.astracurrency.gui;

import com.astrastudio.astracurrency.AstraCurrencyEngine;
import com.astrastudio.astracurrency.config.MessageService;
import com.astrastudio.astracurrency.currency.CurrencyDefinition;
import com.astrastudio.astracurrency.economy.BalanceFormatter;
import com.astrastudio.astracurrency.economy.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** YAML-configurable currency dashboard with all database work performed asynchronously. */
public final class CurrencyMenu {
    private final AstraCurrencyEngine plugin;
    private final EconomyService economy;
    private final MessageService messages;
    private final BalanceFormatter formatter;

    public CurrencyMenu(AstraCurrencyEngine plugin, EconomyService economy,
                        MessageService messages, BalanceFormatter formatter) {
        this.plugin = plugin;
        this.economy = economy;
        this.messages = messages;
        this.formatter = formatter;
    }

    public void openDashboard(Player player) {
        int rows = Math.max(1, Math.min(6, messages.menuInt("dashboard.rows", 6)));
        String title = messages.legacy(messages.menuString("dashboard.title", "<gold>Astra Currency</gold>"), Map.of());
        CurrencyMenuHolder holder = new CurrencyMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, rows * 9, title);
        holder.setInventory(inventory);

        if (messages.menuBoolean("dashboard.filler.enabled", true)) {
            Material filler = material(messages.menuString("dashboard.filler.material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
            String fillerName = messages.legacy(messages.menuString("dashboard.filler.name", " "), Map.of());
            ItemStack fillerItem = item(filler, fillerName, List.of());
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                inventory.setItem(slot, fillerItem);
            }
        }

        Set<Integer> occupied = new HashSet<>();
        for (CurrencyDefinition currency : economy.getCurrencies()) {
            if (!currency.menuVisible()) {
                continue;
            }
            int preferred = currency.menuSlot();
            int slot = preferred < inventory.getSize() && !occupied.contains(preferred)
                    ? preferred : firstFree(occupied, inventory.getSize());
            if (slot < 0) {
                plugin.getLogger().warning("Dashboard is full; skipping currency " + currency.id());
                continue;
            }
            occupied.add(slot);
            holder.setCurrency(slot, currency.id());
            inventory.setItem(slot, currencyItem(currency, null));
            economy.getBalance(player.getUniqueId(), currency.id()).whenComplete((balance, error) ->
                    plugin.runOnMain(() -> {
                        if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) {
                            return;
                        }
                        inventory.setItem(slot, currencyItem(currency, error == null ? balance : null));
                    }));
        }
        player.openInventory(inventory);
    }

    public void showBalance(Player player, String currencyId) {
        var currency = economy.getCurrency(currencyId).filter(CurrencyDefinition::enabled);
        if (currency.isEmpty()) {
            messages.send(player, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        economy.getBalance(player.getUniqueId(), currencyId).whenComplete((balance, error) ->
                plugin.runOnMain(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().warning("Could not load balance for " + player.getUniqueId() + ": " + error.getMessage());
                        messages.send(player, "transaction-error");
                        return;
                    }
                    CurrencyDefinition definition = currency.get();
                    messages.send(player, "balance-self", Map.of(
                            "currency_name", definition.displayName(),
                            "balance", formatter.format(balance, definition)
                    ));
                }));
    }

    private ItemStack currencyItem(CurrencyDefinition currency, java.math.BigDecimal balance) {
        Material fallback = currency.id().equalsIgnoreCase("shards") ? Material.AMETHYST_SHARD : Material.GOLD_INGOT;
        Material icon = material(currency.menuMaterial(), fallback);
        String name = messages.legacy(currency.displayName(), Map.of());
        List<String> lore = new ArrayList<>();
        for (String line : messages.menuStringList("dashboard.currency-item.lore")) {
            String balanceText = balance == null ? "<yellow>Loading...</yellow>" : formatter.format(balance, currency);
            lore.add(messages.legacy(line, Map.of("currency_name", currency.displayName(), "balance", balanceText)));
        }
        return item(icon, name, lore, currency.customModelData());
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return item(material, name, lore, 0);
    }

    private ItemStack item(Material material, String name, List<String> lore, int modelData) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            if (modelData > 0) {
                meta.setCustomModelData(modelData);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material material(String name, Material fallback) {
        Material material = Material.matchMaterial(name == null ? "" : name);
        if (material == null) {
            plugin.getLogger().warning("Unknown menu material '" + name + "'; using " + fallback.name());
            return fallback;
        }
        return material;
    }

    private int firstFree(Set<Integer> occupied, int size) {
        for (int slot = 0; slot < size; slot++) {
            if (!occupied.contains(slot)) {
                return slot;
            }
        }
        return -1;
    }
}
