package com.astrastudio.astracurrency.command;

import com.astrastudio.astracurrency.AstraCurrencyEngine;
import com.astrastudio.astracurrency.config.MessageService;
import com.astrastudio.astracurrency.currency.CurrencyDefinition;
import com.astrastudio.astracurrency.currency.CurrencyRegistry;
import com.astrastudio.astracurrency.economy.AmountRules;
import com.astrastudio.astracurrency.economy.BalanceFormatter;
import com.astrastudio.astracurrency.economy.EconomyService;
import com.astrastudio.astracurrency.storage.SqliteStorage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Administration surface registered under /ace admin and /currency admin. */
public final class AdminCommand {
    private static final Set<String> EDITABLE_PROPERTIES = Set.of(
            "display.name", "display.singular", "display.plural", "display.symbol", "display.description",
            "formatting.decimals", "formatting.grouping", "formatting.pattern", "formatting.compact-pattern",
            "formatting.locale", "formatting.rounding-mode",
            "economy.default-balance", "economy.minimum-balance", "economy.maximum-balance",
            "economy.transferable", "economy.allow-negative", "economy.transaction-tax",
            "economy.leaderboard-enabled", "menus.visible", "menus.display-item", "menus.slot", "menus.model-data"
    );

    private final AstraCurrencyEngine plugin;
    private final EconomyService economy;
    private final CurrencyRegistry registry;
    private final MessageService messages;
    private final BalanceFormatter formatter;

    public AdminCommand(AstraCurrencyEngine plugin, EconomyService economy, CurrencyRegistry registry,
                       MessageService messages, BalanceFormatter formatter) {
        this.plugin = plugin;
        this.economy = economy;
        this.registry = registry;
        this.messages = messages;
        this.formatter = formatter;
    }

    /** startIndex is 0 for /ace and 1 for /currency admin. */
    public boolean execute(CommandSender sender, String[] args, int startIndex) {
        if (!sender.hasPermission("astracurrency.admin")) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length <= startIndex || !args[startIndex].equalsIgnoreCase("admin")) {
            messages.send(sender, "admin-help");
            return true;
        }
        int subIndex = startIndex + 1;
        if (args.length <= subIndex || args[subIndex].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String subcommand = args[subIndex].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "create" -> create(sender, args, subIndex);
            case "delete" -> delete(sender, args, subIndex);
            case "edit" -> edit(sender, args, subIndex);
            case "give" -> adjust(sender, args, subIndex, SqliteStorage.Adjustment.DEPOSIT, "give");
            case "take" -> adjust(sender, args, subIndex, SqliteStorage.Adjustment.WITHDRAW, "take");
            case "set" -> adjust(sender, args, subIndex, SqliteStorage.Adjustment.SET, "set");
            case "reset" -> reset(sender, args, subIndex);
            case "resetall" -> resetAll(sender, args, subIndex);
            case "balance" -> balance(sender, args, subIndex);
            case "list" -> list(sender);
            case "info" -> info(sender, args, subIndex);
            case "reload" -> reload(sender);
            case "backup" -> backup(sender);
            case "restore" -> restore(sender, args, subIndex);
            case "migrate" -> migrate(sender);
            case "debug" -> debug(sender);
            case "status" -> status(sender);
            default -> help(sender);
        }
        return true;
    }

    public List<String> complete(CommandSender sender, String[] args, int startIndex) {
        List<String> commands = List.of("help", "create", "delete", "edit", "give", "take", "set", "reset", "resetall", "balance", "list", "info", "reload", "backup", "restore", "migrate", "debug", "status");
        int commandIndex = startIndex + 1;
        if (args.length <= commandIndex) {
            return match("", commands);
        }
        if (args.length == commandIndex + 1) {
            return match(args[commandIndex], commands);
        }
        String sub = args[commandIndex].toLowerCase(Locale.ROOT);
        int argumentCount = args.length - commandIndex - 1;
        List<String> currencies = registry.enabledCurrencies().stream().map(CurrencyDefinition::id).toList();
        List<String> onlinePlayers = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (Set.of("delete", "info", "edit", "resetall").contains(sub) && argumentCount == 1) {
            return match(args[args.length - 1], currencies);
        }
        if (Set.of("delete", "resetall", "restore").contains(sub)
                && argumentCount == (sub.equals("restore") ? 1 : 2)) {
            return match(args[args.length - 1], List.of("confirm"));
        }
        if (Set.of("give", "take", "set", "reset", "balance").contains(sub) && argumentCount == 1) {
            return match(args[args.length - 1], onlinePlayers);
        }
        if (Set.of("give", "take", "set").contains(sub) && argumentCount == 2) {
            return match(args[args.length - 1], currencies);
        }
        if (Set.of("give", "take", "set").contains(sub) && argumentCount == 3) {
            return match(args[args.length - 1], List.of("1", "10", "100"));
        }
        if (sub.equals("reset") && argumentCount == 3) {
            return match(args[args.length - 1], List.of("confirm"));
        }
        return List.of();
    }

    private void create(CommandSender sender, String[] args, int index) {
        if (!allowed(sender, "create")) return;
        if (args.length < index + 2) {
            usage(sender, "/ace admin create <id> [display name]");
            return;
        }
        String displayName = args.length > index + 2 ? String.join(" ", Arrays.copyOfRange(args, index + 2, args.length)) : null;
        try {
            CurrencyDefinition currency = registry.create(args[index + 1], displayName);
            economy.refreshLeaderboard(currency.id());
            messages.send(sender, "admin-created", Map.of("currency", currency.id()));
        } catch (Exception ex) {
            messages.sendUnprefixed(sender, "<red>Could not create currency: " + safe(ex.getMessage()) + "</red>");
        }
    }

    private void delete(CommandSender sender, String[] args, int index) {
        if (!allowed(sender, "delete")) return;
        if (args.length < index + 3 || !args[ index + 2 ].equalsIgnoreCase("confirm")) {
            usage(sender, "/ace admin delete <currency> confirm");
            return;
        }
        String id = args[index + 1].toLowerCase(Locale.ROOT);
        var currency = registry.find(id);
        if (currency.isEmpty()) {
            messages.send(sender, "unknown-currency", Map.of("currency", id));
            return;
        }
        if (currency.get().enabled() && registry.enabledCurrencies().size() <= 1) {
            messages.sendUnprefixed(sender, "<red>At least one enabled currency must remain.</red>");
            return;
        }
        economy.deleteCurrencyBalances(id).whenComplete((count, error) -> plugin.runOnMain(() -> {
            if (error != null) {
                storageError(sender, error);
                return;
            }
            try {
                registry.delete(id);
                economy.refreshLeaderboards();
                messages.send(sender, "admin-deleted", Map.of("currency", id));
            } catch (IOException ex) {
                plugin.getLogger().severe("Balances were removed, but currency file could not be deleted: " + ex.getMessage());
                messages.send(sender, "transaction-error");
            }
        }));
    }

    private void edit(CommandSender sender, String[] args, int index) {
        if (!allowed(sender, "edit")) return;
        if (args.length < index + 4) {
            usage(sender, "/ace admin edit <currency> <property> <value>");
            return;
        }
        String id = args[index + 1].toLowerCase(Locale.ROOT);
        String property = args[index + 2].toLowerCase(Locale.ROOT);
        if (!EDITABLE_PROPERTIES.contains(property)) {
            messages.sendUnprefixed(sender, "<red>Property is not editable: <white>" + safe(property) + "</white></red>");
            return;
        }
        var current = registry.find(id);
        if (current.isEmpty()) {
            messages.send(sender, "unknown-currency", Map.of("currency", id));
            return;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, index + 3, args.length));
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(registry.directory().resolve(id + ".yml").toFile());
            Object parsed = parseProperty(property, value);
            yaml.set(property, parsed);
            CurrencyDefinition candidate = CurrencyDefinition.from(yaml);
            if (!candidate.id().equals(id)) {
                throw new IllegalArgumentException("currency id cannot be changed with edit");
            }
            economy.balancesFitCurrency(candidate).whenComplete((valid, error) -> plugin.runOnMain(() -> {
                if (error != null) {
                    storageError(sender, error);
                } else if (!valid) {
                    messages.sendUnprefixed(sender, "<red>Existing balances would violate the edited precision or limits. Update them before changing this setting.</red>");
                } else {
                    saveEdit(sender, id, property, yaml);
                }
            }));
        } catch (Exception ex) {
            messages.sendUnprefixed(sender, "<red>Invalid currency edit: " + safe(ex.getMessage()) + "</red>");
        }
    }

    private void saveEdit(CommandSender sender, String id, String property, YamlConfiguration yaml) {
        try {
            yaml.save(registry.directory().resolve(id + ".yml").toFile());
            CurrencyDefinition updated = registry.reloadOne(id);
            economy.refreshLeaderboard(updated.id());
            messages.send(sender, "admin-edited", Map.of("property", property, "currency", updated.id()));
        } catch (Exception ex) {
            messages.sendUnprefixed(sender, "<red>Could not save currency edit: " + safe(ex.getMessage()) + "</red>");
        }
    }

    private Object parseProperty(String property, String value) {
        if (property.equals("formatting.decimals") || property.equals("menus.slot") || property.equals("menus.model-data")) {
            return Integer.parseInt(value);
        }
        if (Set.of("formatting.grouping", "economy.transferable", "economy.allow-negative",
                "economy.leaderboard-enabled", "menus.visible").contains(property)) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("expected true or false");
            }
            return Boolean.parseBoolean(value);
        }
        if (Set.of("economy.default-balance", "economy.minimum-balance", "economy.maximum-balance",
                "economy.transaction-tax").contains(property)) {
            return new BigDecimal(value).toPlainString();
        }
        if (property.equals("formatting.rounding-mode")) {
            java.math.RoundingMode.valueOf(value.toUpperCase(Locale.ROOT));
            return value.toUpperCase(Locale.ROOT);
        }
        if (property.equals("formatting.locale")) {
            if (Locale.forLanguageTag(value).getLanguage().isBlank()) {
                throw new IllegalArgumentException("invalid BCP-47 locale");
            }
            return value;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("value cannot be blank");
        }
        return value;
    }

    private void adjust(CommandSender sender, String[] args, int index,
                        SqliteStorage.Adjustment adjustment, String permission) {
        if (!allowed(sender, permission)) return;
        if (args.length < index + 4) {
            usage(sender, "/ace admin " + permission + " <player> <currency> <amount> [reason]");
            return;
        }
        String targetName = args[index + 1];
        String currencyId = args[index + 2].toLowerCase(Locale.ROOT);
        CurrencyDefinition currency = registry.find(currencyId).filter(CurrencyDefinition::enabled).orElse(null);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        BigDecimal amount;
        try {
            amount = adjustment == SqliteStorage.Adjustment.SET
                    ? currency.normalizeAmount(new BigDecimal(args[index + 3]))
                    : AmountRules.positive(args[index + 3], currency);
            if (adjustment == SqliteStorage.Adjustment.SET && !currency.isWithinLimits(amount)) {
                throw new IllegalArgumentException("amount is outside the currency limits");
            }
        } catch (RuntimeException ex) {
            messages.send(sender, "invalid-amount", Map.of("decimals", Integer.toString(currency.decimalPlaces())));
            return;
        }
        String reason = args.length > index + 4 ? String.join(" ", Arrays.copyOfRange(args, index + 4, args.length)) : "admin command";
        resolvePlayer(targetName, targetId -> economy.adminAdjust(sender instanceof Player player ? player.getUniqueId() : null,
                targetId, currencyId, amount, adjustment, reason, UUID.randomUUID().toString())
                .whenComplete((result, error) -> plugin.runOnMain(() -> {
                    if (error != null) {
                        storageError(sender, error);
                        return;
                    }
                    if (!result.successful()) {
                        reportFailure(sender, result, currency, targetName);
                        return;
                    }
                    messages.send(sender, "admin-transaction", Map.of(
                            "action", permission,
                            "amount", formatter.format(amount, currency),
                            "currency_name", currency.displayName(),
                            "player", targetName,
                            "balance", formatter.format(result.senderBalance(), currency)
                    ));
                })), () -> messages.send(sender, "invalid-player", Map.of("player", targetName)));
    }

    private void reset(CommandSender sender, String[] args, int index) {
        if (!allowed(sender, "reset")) return;
        if (args.length < index + 4 || !args[index + 3].equalsIgnoreCase("confirm")) {
            usage(sender, "/ace admin reset <player> <currency> confirm");
            return;
        }
        String playerName = args[index + 1];
        String currencyId = args[index + 2].toLowerCase(Locale.ROOT);
        CurrencyDefinition currency = registry.find(currencyId).filter(CurrencyDefinition::enabled).orElse(null);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        resolvePlayer(playerName, target -> economy.reset(sender instanceof Player p ? p.getUniqueId() : null,
                target, currencyId, UUID.randomUUID().toString()).whenComplete((result, error) -> plugin.runOnMain(() -> {
                    if (error != null) {
                        storageError(sender, error);
                    } else if (result.successful()) {
                        messages.send(sender, "admin-transaction", Map.of(
                                "action", "reset", "amount", formatter.format(currency.defaultBalance(), currency),
                                "currency_name", currency.displayName(), "player", playerName,
                                "balance", formatter.format(currency.defaultBalance(), currency)));
                    } else {
                        reportFailure(sender, result, currency, playerName);
                    }
                })), () -> messages.send(sender, "invalid-player", Map.of("player", playerName)));
    }

    private void resetAll(CommandSender sender, String[] args, int index) {
        if (!allowed(sender, "resetall")) return;
        if (args.length < index + 3 || !args[index + 2].equalsIgnoreCase("confirm")) {
            usage(sender, "/ace admin resetall <currency> confirm");
            return;
        }
        String currencyId = args[index + 1].toLowerCase(Locale.ROOT);
        CurrencyDefinition currency = registry.find(currencyId).filter(CurrencyDefinition::enabled).orElse(null);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        economy.resetAll(currencyId, UUID.randomUUID().toString()).whenComplete((changed, error) -> plugin.runOnMain(() -> {
            if (error != null) {
                storageError(sender, error);
            } else {
                messages.sendUnprefixed(sender, "<green>Reset <white>" + changed + "</white> " + currency.displayName() + " balance(s).</green>");
            }
        }));
    }

    private void balance(CommandSender sender, String[] args, int index) {
        if (!allowed(sender, "balance")) return;
        if (args.length < index + 3) {
            usage(sender, "/ace admin balance <player> <currency>");
            return;
        }
        String playerName = args[index + 1];
        String currencyId = args[index + 2].toLowerCase(Locale.ROOT);
        CurrencyDefinition currency = registry.find(currencyId).filter(CurrencyDefinition::enabled).orElse(null);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", currencyId));
            return;
        }
        resolvePlayer(playerName, target -> economy.getBalance(target, currencyId).whenComplete((value, error) -> plugin.runOnMain(() -> {
            if (error != null) {
                storageError(sender, error);
            } else {
                messages.send(sender, "balance-other", Map.of("player", playerName,
                        "balance", formatter.format(value, currency), "currency_name", currency.displayName()));
            }
        })), () -> messages.send(sender, "invalid-player", Map.of("player", playerName)));
    }

    private void list(CommandSender sender) {
        if (!allowed(sender, "status")) return;
        messages.send(sender, "currency-list-header");
        for (CurrencyDefinition currency : registry.enabledCurrencies()) {
            messages.send(sender, "currency-list-entry", Map.of("currency_name", currency.displayName(),
                    "id", currency.id(), "symbol", currency.symbol()));
        }
    }

    private void info(CommandSender sender, String[] args, int index) {
        if (!allowed(sender, "status")) return;
        if (args.length <= index + 1) {
            usage(sender, "/ace admin info <currency>");
            return;
        }
        String id = args[index + 1].toLowerCase(Locale.ROOT);
        CurrencyDefinition currency = registry.find(id).orElse(null);
        if (currency == null) {
            messages.send(sender, "unknown-currency", Map.of("currency", id));
            return;
        }
        messages.send(sender, "currency-info", Map.of("currency_name", currency.displayName(), "id", id,
                "decimals", Integer.toString(currency.decimalPlaces()), "balance", "—", "symbol", currency.symbol()));
        if (!currency.description().isBlank()) {
            messages.sendUnprefixed(sender, "<gray>" + currency.description() + "</gray>");
        }
    }

    private void reload(CommandSender sender) {
        if (!allowed(sender, "reload")) return;
        try {
            plugin.reloadPluginConfiguration();
            messages.send(sender, "admin-reloaded");
        } catch (Exception ex) {
            plugin.getLogger().severe("Reload failed: " + ex.getMessage());
            messages.send(sender, "transaction-error");
        }
    }

    private void backup(CommandSender sender) {
        if (!allowed(sender, "backup")) return;
        economy.createBackup().whenComplete((backup, error) -> plugin.runOnMain(() -> {
            if (error != null) {
                storageError(sender, error);
            } else {
                messages.send(sender, "admin-backup", Map.of("backup", backup));
            }
        }));
    }

    private void restore(CommandSender sender, String[] args, int index) {
        if (!allowed(sender, "restore")) return;
        if (args.length < index + 3 || !args[index + 2].equalsIgnoreCase("confirm")) {
            usage(sender, "/ace admin restore <backup-id> confirm");
            return;
        }
        String backup = args[index + 1];
        economy.restoreBackup(backup).whenComplete((ignored, error) -> plugin.runOnMain(() -> {
            if (error != null) {
                storageError(sender, error);
            } else {
                messages.send(sender, "admin-restore", Map.of("backup", backup));
            }
        }));
    }

    private void migrate(CommandSender sender) {
        if (!allowed(sender, "migrate")) return;
        messages.sendUnprefixed(sender, "<green>SQLite schema migration runs automatically at startup. Current schema: <white>1</white>.</green>");
    }

    private void debug(CommandSender sender) {
        if (!allowed(sender, "debug")) return;
        messages.send(sender, "admin-debug", Map.of("queue", Integer.toString(economy.queuedTasks()),
                "cache", Integer.toString(economy.cacheSize()), "file", economy.storage().databaseFile().toString()));
    }

    private void status(CommandSender sender) {
        if (!allowed(sender, "status")) return;
        messages.send(sender, "admin-status", Map.of("version", plugin.getDescription().getVersion(),
                "server", Bukkit.getVersion(), "count", Integer.toString(registry.enabledCurrencies().size())));
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
            // Not a UUID; fall back to the local last-known-name index.
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

    private void reportFailure(CommandSender sender, com.astrastudio.astracurrency.api.TransactionResult result,
                               CurrencyDefinition currency, String target) {
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

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission("astracurrency.admin." + permission) || sender.hasPermission("astracurrency.admin")) {
            return true;
        }
        messages.send(sender, "no-permission");
        return false;
    }

    private void storageError(CommandSender sender, Throwable error) {
        Throwable cause = unwrap(error);
        plugin.getLogger().severe("Storage operation failed: " + cause.getMessage());
        messages.send(sender, "transaction-error");
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "usage", Map.of("usage", usage));
    }

    private void help(CommandSender sender) {
        messages.send(sender, "help-header");
        messages.sendLines(sender, "help-admin", Map.of());
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safe(String value) {
        return value == null ? "unknown error" : value.replace('<', ' ').replace('>', ' ').replace('\n', ' ');
    }

    private static List<String> match(String prefix, List<String> candidates) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
