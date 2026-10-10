package com.astrastudio.astracurrency.command;

import com.astrastudio.astracurrency.AstraCurrencyEngine;
import com.astrastudio.astracurrency.api.LeaderboardEntry;
import com.astrastudio.astracurrency.api.TransactionRecord;
import com.astrastudio.astracurrency.api.TransactionResult;
import com.astrastudio.astracurrency.config.MessageService;
import com.astrastudio.astracurrency.currency.CurrencyDefinition;
import com.astrastudio.astracurrency.currency.CurrencyRegistry;
import com.astrastudio.astracurrency.economy.AmountRules;
import com.astrastudio.astracurrency.economy.BalanceFormatter;
import com.astrastudio.astracurrency.economy.EconomyService;
import com.astrastudio.astracurrency.gui.CurrencyMenu;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Player command executor and tab completer for /currency, /astracurrency, and /ace admin. */
public final class CurrencyCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("help", "list", "balance", "bal", "pay", "top", "history", "info", "gui");

    private final AstraCurrencyEngine plugin;
    private final EconomyService economy;
    private final CurrencyRegistry registry;
    private final MessageService messages;
    private final BalanceFormatter formatter;
    private final CurrencyMenu menu;
    private final AdminCommand admin;

    public CurrencyCommand(AstraCurrencyEngine plugin, EconomyService economy, CurrencyRegistry registry,
                           MessageService messages, BalanceFormatter formatter, CurrencyMenu menu) {
        this.plugin = plugin;
        this.economy = economy;
        this.registry = registry;
        this.messages = messages;
        this.formatter = formatter;
        this.menu = menu;
        this.admin = new AdminCommand(plugin, economy, registry, messages, formatter);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ace")) {
            return admin.execute(sender, args, 0);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            return admin.execute(sender, args, 0);
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!sender.hasPermission("astracurrency.gui")) {
                    messages.send(sender, "no-permission");
                } else {
                    menu.openDashboard(player);
                }
            } else {
                help(sender);
            }
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "help" -> help(sender);
            case "list" -> list(sender);
            case "balance", "bal" -> balance(sender, args);
            case "pay" -> pay(sender, args);
            case "top" -> top(sender, args);
            case "history" -> history(sender, args);
            case "info" -> info(sender, args);
            case "gui" -> gui(sender);
            default -> help(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("ace")) {
            if (args.length <= 1) {
                return match(args.length == 0 ? "" : args[0], List.of("admin"));
            }
            return admin.complete(sender, args, 0);
        }
        if (args.length == 0) {
            return PLAYER_SUBCOMMANDS;
        }
        if (args.length == 1) {
            List<String> values = new ArrayList<>(PLAYER_SUBCOMMANDS);
            values.add("admin");
            return match(args[0], values);
        }
        if (args[0].equalsIgnoreCase("admin")) {
            return admin.complete(sender, args, 0);
        }
        if (Set.of("balance", "bal", "pay", "top", "history", "info").contains(args[0].toLowerCase(Locale.ROOT))) {
            if (args[0].equalsIgnoreCase("balance") || args[0].equalsIgnoreCase("bal")) {
                if (args.length == 2) {
                    List<String> currencies = new ArrayList<>(registry.enabledCurrencies().stream().map(CurrencyDefinition::id).toList());
                    currencies.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                    return match(args[1], currencies);
                }
                if (args.length == 3) {
                    return match(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                }
            }
            if (args[0].equalsIgnoreCase("pay")) {
                if (args.length == 2) return match(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                if (args.length == 3) return match(args[2], registry.enabledCurrencies().stream().map(CurrencyDefinition::id).toList());
            }
            if (Set.of("top", "history", "info").contains(args[0].toLowerCase(Locale.ROOT)) && args.length == 2) {
                return match(args[1], registry.enabledCurrencies().stream().map(CurrencyDefinition::id).toList());
            }
        }
        return List.of();
    }

    private void help(CommandSender sender) {
        if (!sender.hasPermission("astracurrency.help")) {
            messages.send(sender, "no-permission");
            return;
        }
        messages.send(sender, "help-header");
        messages.sendLines(sender, "help-player", Map.of());
    }

    private void list(CommandSender sender) {
        if (!sender.hasPermission("astracurrency.list")) {
            messages.send(sender, "no-permission");
            return;
        }
        messages.send(sender, "currency-list-header");
        for (CurrencyDefinition currency : economy.getCurrencies()) {
            if (!hasCurrencyPermission(sender, "list", currency.id())) continue;
            messages.send(sender, "currency-list-entry", Map.of("currency_name", currency.displayName(),
                    "id", currency.id(), "symbol", currency.symbol()));
        }
    }

    private void balance(CommandSender sender, String[] args) {
        String primary = primaryCurrency();
        String currencyId = primary;
        String targetName = null;
        if (args.length >= 2) {
            if (registry.find(args[1]).isPresent()) {
                currencyId = args[1].toLowerCase(Locale.ROOT);
                if (args.length >= 3) targetName = args[2];
            } else if (args.length == 2 && sender instanceof Player) {
                targetName = args[1];
            } else {
                messages.send(sender, "unknown-currency", Map.of("currency", args[1]));
                return;
            }
        }
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        if (!hasCurrencyPermission(sender, "balance", currency.id())) {
            messages.send(sender, "no-permission");
            return;
        }
        if (targetName == null) {
            if (!(sender instanceof Player player)) {
                usage(sender, "/currency balance <currency> <player>");
                return;
            }
            economy.getBalance(player.getUniqueId(), currency.id()).whenComplete((value, error) -> plugin.runOnMain(() -> {
                if (!player.isOnline()) return;
                if (error != null) storageError(sender, error);
                else messages.send(sender, "balance-self", Map.of("currency_name", currency.displayName(),
                        "balance", formatter.format(value, currency)));
            }));
            return;
        }
        if (!sender.hasPermission("astracurrency.balance.others")) {
            messages.send(sender, "no-permission");
            return;
        }
        String finalCurrencyId = currency.id();
        String requestedPlayer = targetName;
        resolvePlayer(requestedPlayer, target -> economy.getBalance(target, finalCurrencyId).whenComplete((value, error) -> plugin.runOnMain(() -> {
            if (error != null) storageError(sender, error);
            else messages.send(sender, "balance-other", Map.of("player", requestedPlayer,
                    "currency_name", currency.displayName(), "balance", formatter.format(value, currency)));
        })), () -> messages.send(sender, "invalid-player", Map.of("player", requestedPlayer)));
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return;
        }
        if (args.length < 4) {
            usage(sender, "/currency pay <player> <currency> <amount>");
            return;
        }
        String targetName = args[1];
        String currencyId = args[2].toLowerCase(Locale.ROOT);
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        if (!hasCurrencyPermission(sender, "pay", currency.id())) {
            messages.send(sender, "no-permission");
            return;
        }
        if (!currency.transferable()) {
            messages.sendUnprefixed(sender, "<red>This currency cannot be transferred.</red>");
            return;
        }
        BigDecimal amount;
        try {
            amount = AmountRules.positive(args[3], currency);
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("greater than zero")) {
                messages.send(sender, "amount-must-be-positive");
            } else {
                messages.send(sender, "invalid-amount", Map.of("decimals", Integer.toString(currency.decimalPlaces())));
            }
            return;
        }
        resolvePlayer(targetName, target -> {
            if (target.equals(player.getUniqueId())) {
                messages.send(player, "self-payment");
                return;
            }
            economy.transfer(player.getUniqueId(), target, currency.id(), amount, UUID.randomUUID().toString())
                    .whenComplete((result, error) -> plugin.runOnMain(() -> {
                        if (!player.isOnline()) return;
                        if (error != null) {
                            storageError(player, error);
                            return;
                        }
                        if (!result.successful()) {
                            reportFailure(player, result, currency);
                            return;
                        }
                        messages.send(player, "payment-sent", Map.of(
                                "amount", formatter.format(result.amount(), currency),
                                "currency_name", currency.displayName(),
                                "player", targetName,
                                "tax", formatter.format(result.tax(), currency),
                                "balance", formatter.format(result.senderBalance(), currency)
                        ));
                        Player onlineTarget = Bukkit.getPlayer(target);
                        if (onlineTarget != null) {
                            messages.send(onlineTarget, "payment-received", Map.of(
                                    "amount", formatter.format(result.amount(), currency),
                                    "currency_name", currency.displayName(),
                                    "player", player.getName()
                            ));
                        }
                    }));
        }, () -> messages.send(player, "invalid-player", Map.of("player", targetName)));
    }

    private void top(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender, "/currency top <currency> [page] [page-size]");
            return;
        }
        String currencyId = args[1].toLowerCase(Locale.ROOT);
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        if (!hasCurrencyPermission(sender, "top", currency.id())) {
            messages.send(sender, "no-permission");
            return;
        }
        int page;
        int pageSize;
        try {
            page = args.length > 2 ? Integer.parseInt(args[2]) : 1;
            pageSize = args.length > 3 ? Integer.parseInt(args[3]) : plugin.getConfig().getInt("leaderboards.default-page-size", 10);
            if (page < 1 || page > 10_000 || pageSize < 1 || pageSize > 100 || !Set.of(10, 50, 100).contains(pageSize)) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            usage(sender, "/currency top <currency> [page] [10|50|100]");
            return;
        }
        int limit = (int) Math.min(10_000L, (long) page * pageSize);
        economy.getTopBalances(currency.id(), limit).whenComplete((entries, error) -> plugin.runOnMain(() -> {
            if (error != null) {
                storageError(sender, error);
                return;
            }
            messages.send(sender, "leaderboard-header", Map.of("currency_name", currency.displayName(), "page", Integer.toString(page)));
            int start = (page - 1) * pageSize;
            if (start >= entries.size()) {
                messages.send(sender, "leaderboard-empty");
                return;
            }
            for (int i = start; i < Math.min(entries.size(), start + pageSize); i++) {
                LeaderboardEntry entry = entries.get(i);
                messages.send(sender, "leaderboard-entry", Map.of("rank", Integer.toString(entry.rank()),
                        "player", entry.lastKnownName(), "balance", formatter.format(entry.balance(), currency)));
            }
        }));
    }

    private void history(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return;
        }
        if (args.length < 2) {
            usage(sender, "/currency history <currency> [page]");
            return;
        }
        String currencyId = args[1].toLowerCase(Locale.ROOT);
        CurrencyDefinition currency = enabledCurrency(currencyId);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        if (!hasCurrencyPermission(sender, "history", currency.id())) {
            messages.send(sender, "no-permission");
            return;
        }
        int page;
        try {
            page = args.length > 2 ? Integer.parseInt(args[2]) : 1;
            if (page < 1) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            usage(sender, "/currency history <currency> [page]");
            return;
        }
        int pageSize = Math.max(1, Math.min(100, plugin.getConfig().getInt("leaderboards.default-page-size", 10)));
        economy.getHistory(player.getUniqueId(), currency.id(), page, pageSize).whenComplete((records, error) -> plugin.runOnMain(() -> {
            if (!player.isOnline()) return;
            if (error != null) {
                storageError(sender, error);
                return;
            }
            messages.send(sender, "history-header", Map.of("currency_name", currency.displayName(), "page", Integer.toString(page)));
            if (records.isEmpty()) {
                messages.send(sender, "history-empty");
                return;
            }
            for (TransactionRecord record : records) {
                String details = record.sender() != null && record.sender().equals(player.getUniqueId())
                        ? record.recipient() == null ? "" : "to " + shortId(record.recipient())
                        : record.sender() == null ? "" : "from " + shortId(record.sender());
                messages.send(sender, "history-entry", Map.of("id", Long.toString(record.id()), "type", record.type(),
                        "amount", formatter.format(record.amount(), currency), "details", details));
            }
        }));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender, "/currency info <currency>");
            return;
        }
        CurrencyDefinition currency = enabledCurrency(args[1]);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", args[1]));
            return;
        }
        if (!hasCurrencyPermission(sender, "balance", currency.id())) {
            messages.send(sender, "no-permission");
            return;
        }
        if (sender instanceof Player player) {
            economy.getBalance(player.getUniqueId(), currency.id()).whenComplete((balance, error) -> plugin.runOnMain(() -> {
                if (!player.isOnline()) return;
                if (error != null) storageError(sender, error);
                else showInfo(sender, currency, formatter.format(balance, currency));
            }));
        } else {
            showInfo(sender, currency, "—");
        }
    }

    private void showInfo(CommandSender sender, CurrencyDefinition currency, String balance) {
        messages.send(sender, "currency-info", Map.of("currency_name", currency.displayName(), "id", currency.id(),
                "decimals", Integer.toString(currency.decimalPlaces()), "balance", balance));
        if (!currency.description().isBlank()) {
            messages.sendUnprefixed(sender, "<gray>" + currency.description() + "</gray>");
        }
    }

    private void gui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return;
        }
        if (!sender.hasPermission("astracurrency.gui")) {
            messages.send(sender, "no-permission");
            return;
        }
        menu.openDashboard(player);
    }

    private void resolvePlayer(String name, Consumer<UUID> success, Runnable failure) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            success.accept(online.getUniqueId());
            return;
        }
        try {
            success.accept(UUID.fromString(name));
            return;
        } catch (IllegalArgumentException ignored) {
            // Only locally-known offline players are resolved by name; no blocking web profile lookup is used.
        }
        economy.findPlayerIdByName(name).whenComplete((id, error) -> plugin.runOnMain(() -> {
            if (error != null) {
                plugin.getLogger().warning("Could not resolve offline player '" + name + "': " + error.getMessage());
                failure.run();
            } else if (id.isPresent()) {
                success.accept(id.get());
            } else {
                failure.run();
            }
        }));
    }

    private CurrencyDefinition enabledCurrency(String id) {
        return registry.find(id).filter(CurrencyDefinition::enabled).orElse(null);
    }

    private String primaryCurrency() {
        String id = plugin.getConfig().getString("primary-currency", "coins");
        return id == null || id.isBlank() ? "coins" : id.toLowerCase(Locale.ROOT);
    }

    private boolean hasCurrencyPermission(CommandSender sender, String action, String currencyId) {
        String specific = "astracurrency." + action + "." + currencyId;
        for (PermissionAttachmentInfo info : sender.getEffectivePermissions()) {
            if (info.getPermission().equalsIgnoreCase(specific)) {
                return info.getValue();
            }
        }
        return sender.hasPermission("astracurrency." + action)
                || sender.hasPermission("astracurrency.currency.*")
                || sender.hasPermission("astracurrency.*");
    }

    private void reportFailure(CommandSender sender, TransactionResult result, CurrencyDefinition currency) {
        switch (result.status()) {
            case INSUFFICIENT_FUNDS -> messages.send(sender, "insufficient-funds", Map.of(
                    "currency_name", currency.displayName(), "balance", formatter.format(
                            result.senderBalance() == null ? BigDecimal.ZERO : result.senderBalance(), currency)));
            case BALANCE_LIMIT -> messages.send(sender, "balance-limit");
            case DUPLICATE_REQUEST -> messages.send(sender, "transaction-duplicate");
            case SAME_ACCOUNT -> messages.send(sender, "self-payment");
            case NOT_TRANSFERABLE -> messages.sendUnprefixed(sender, "<red>This currency cannot be transferred.</red>");
            case NO_CHANGE -> messages.send(sender, "admin-no-change");
            default -> messages.send(sender, "transaction-error");
        }
    }

    private void storageError(CommandSender sender, Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        plugin.getLogger().severe("Storage operation failed: " + cause.getMessage());
        messages.send(sender, "transaction-error");
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "usage", Map.of("usage", usage));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static List<String> match(String prefix, List<String> candidates) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().toList();
    }
}
