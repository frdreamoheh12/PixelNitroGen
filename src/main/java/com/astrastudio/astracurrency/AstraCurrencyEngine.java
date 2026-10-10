package com.astrastudio.astracurrency;

import com.astrastudio.astracurrency.api.AstraCurrencyAPI;
import com.astrastudio.astracurrency.command.CurrencyCommand;
import com.astrastudio.astracurrency.config.MessageService;
import com.astrastudio.astracurrency.currency.CurrencyRegistry;
import com.astrastudio.astracurrency.economy.BalanceFormatter;
import com.astrastudio.astracurrency.economy.EconomyService;
import com.astrastudio.astracurrency.gui.CurrencyMenu;
import com.astrastudio.astracurrency.listener.CurrencyMenuListener;
import com.astrastudio.astracurrency.storage.SqliteStorage;
import com.astrastudio.astracurrency.util.AuditLogger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Paper entrypoint for AstraCurrencyEngine. */
public final class AstraCurrencyEngine extends JavaPlugin {
    private CurrencyRegistry currencyRegistry;
    private SqliteStorage storage;
    private EconomyService economyService;
    private MessageService messageService;
    private BalanceFormatter balanceFormatter;
    private CurrencyMenu currencyMenu;
    private Object placeholderExpansion;
    private BukkitTask leaderboardTask;

    @Override
    public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.createDirectories(getDataFolder().toPath().resolve("data"));
            Files.createDirectories(getDataFolder().toPath().resolve("backups"));
            Files.createDirectories(getDataFolder().toPath().resolve("logs"));
            saveDefaultConfig();
            saveBundled("messages.yml");
            saveBundled("menus.yml");
            saveBundled("dialogs.yml");
            saveBundled("storage.yml");
            saveBundled("integrations.yml");
            saveBundled("permissions.yml");

            this.messageService = new MessageService(this);
            this.messageService.reload();
            this.currencyRegistry = new CurrencyRegistry(this);
            this.currencyRegistry.reload();
            ensurePrimaryCurrency();
            this.balanceFormatter = new BalanceFormatter();

            YamlConfiguration storageConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "storage.yml"));
            String backend = storageConfig.getString("type", "sqlite");
            if (backend == null || !backend.equalsIgnoreCase("sqlite")) {
                throw new IllegalStateException("Only SQLite storage is supported in this release; configured type was " + backend);
            }
            String relativePath = storageConfig.getString("sqlite.file", "data/astracurrency.db");
            Path dataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path databaseFile = dataDirectory.resolve(relativePath).normalize();
            if (!databaseFile.startsWith(dataDirectory)) {
                throw new IllegalArgumentException("storage.yml sqlite.file must remain inside the plugin data folder");
            }
            this.storage = new SqliteStorage(databaseFile, dataDirectory.resolve("backups"),
                    storageConfig.getInt("sqlite.busy-timeout-ms", 5000),
                    storageConfig.getInt("sqlite.worker-queue-size", 2048));
            storage.initialize();

            AuditLogger auditLogger = new AuditLogger(dataDirectory.resolve("logs"));
            this.economyService = new EconomyService(this, currencyRegistry, storage, auditLogger);
            this.currencyMenu = new CurrencyMenu(this, economyService, messageService, balanceFormatter);
            CurrencyCommand command = new CurrencyCommand(this, economyService, currencyRegistry,
                    messageService, balanceFormatter, currencyMenu);
            if (getCommand("currency") == null || getCommand("ace") == null) {
                throw new IllegalStateException("plugin.yml is missing the currency or ace command");
            }
            getCommand("currency").setExecutor(command);
            getCommand("currency").setTabCompleter(command);
            getCommand("ace").setExecutor(command);
            getCommand("ace").setTabCompleter(command);
            getServer().getPluginManager().registerEvents(new CurrencyMenuListener(currencyMenu), this);
            getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(), this);

            getServer().getServicesManager().register(AstraCurrencyAPI.class, economyService, this, ServicePriority.Normal);
            registerPlaceholderAPI();
            scheduleLeaderboardRefresh();
            getLogger().info("AstraCurrencyEngine " + getDescription().getVersion() + " enabled with SQLite and "
                    + currencyRegistry.enabledCurrencies().size() + " currency definition(s).");
        } catch (Exception ex) {
            getLogger().severe("AstraCurrencyEngine could not start: " + ex.getMessage());
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
            leaderboardTask = null;
        }
        unregisterPlaceholderExpansion();
        if (economyService != null) {
            getServer().getServicesManager().unregister(AstraCurrencyAPI.class, economyService);
        }
        if (storage != null) {
            storage.close();
        }
        getLogger().info("AstraCurrencyEngine disabled.");
    }

    public EconomyService economy() {
        return economyService;
    }

    public CurrencyRegistry currencies() {
        return currencyRegistry;
    }

    public MessageService messages() {
        return messageService;
    }

    public BalanceFormatter formatter() {
        return balanceFormatter;
    }

    /** Reloads live YAML settings and currency definitions. Storage backend changes require a restart. */
    public void reloadPluginConfiguration() throws IOException {
        reloadConfig();
        messageService.reload();
        currencyRegistry.reload();
        ensurePrimaryCurrency();
        economyService.refreshLeaderboards();
        scheduleLeaderboardRefresh();
        registerPlaceholderAPI();
    }

    /** Safely dispatches completion handlers back to the Paper primary thread. */
    public void runOnMain(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(this, task);
            }
        } catch (RuntimeException ex) {
            getLogger().fine("Skipped main-thread callback during shutdown: " + ex.getMessage());
        }
    }

    private void scheduleLeaderboardRefresh() {
        if (leaderboardTask != null) {
            leaderboardTask.cancel();
        }
        long refreshSeconds = Math.min(3600, Math.max(10, getConfig().getLong("leaderboards.refresh-seconds", 60)));
        leaderboardTask = getServer().getScheduler().runTaskTimer(this, economyService::refreshLeaderboards,
                20L, refreshSeconds * 20L);
    }

    private void ensurePrimaryCurrency() {
        String primary = getConfig().getString("primary-currency", "coins");
        if (primary != null && currencyRegistry.find(primary).filter(currency -> currency.enabled()).isPresent()) {
            return;
        }
        List<String> enabled = currencyRegistry.enabledCurrencies().stream().map(currency -> currency.id()).toList();
        String fallback = enabled.getFirst();
        getLogger().warning("Configured primary-currency is missing or disabled; using " + fallback);
        getConfig().set("primary-currency", fallback);
        saveConfig();
    }

    private void registerPlaceholderAPI() {
        unregisterPlaceholderExpansion();
        YamlConfiguration integrations = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "integrations.yml"));
        if (!integrations.getBoolean("placeholderapi.enabled", true)
                || getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            String primary = getConfig().getString("primary-currency", "coins");
            String className = "com.astrastudio.astracurrency.integration.AstraPlaceholderExpansion";
            Class<?> expansionType = Class.forName(className, true, getClass().getClassLoader());
            Object expansion = expansionType.getConstructor(AstraCurrencyEngine.class, EconomyService.class,
                            BalanceFormatter.class, String.class)
                    .newInstance(this, economyService, balanceFormatter,
                            primary == null ? "coins" : primary.toLowerCase(Locale.ROOT));
            boolean registered = (Boolean) expansionType.getMethod("register").invoke(expansion);
            if (registered) {
                placeholderExpansion = expansion;
                getLogger().info("Registered PlaceholderAPI expansion 'astracurrency'.");
            } else {
                getLogger().warning("PlaceholderAPI expansion could not register.");
            }
        } catch (Throwable ex) {
            getLogger().warning("PlaceholderAPI was detected but the optional integration failed to load: " + ex.getMessage());
            placeholderExpansion = null;
        }
    }

    private void unregisterPlaceholderExpansion() {
        if (placeholderExpansion == null) {
            return;
        }
        try {
            placeholderExpansion.getClass().getMethod("unregister").invoke(placeholderExpansion);
        } catch (Throwable ignored) {
            // PlaceholderAPI can be reloading or already stopped.
        } finally {
            placeholderExpansion = null;
        }
    }

    private void saveBundled(String path) {
        File file = new File(getDataFolder(), path);
        if (!file.exists()) {
            saveResource(path, false);
        }
    }

    private final class PlayerLifecycleListener implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            Player player = event.getPlayer();
            storage.rememberPlayer(player.getUniqueId(), player.getName())
                    .exceptionally(error -> {
                        getLogger().warning("Could not record player name for " + player.getUniqueId() + ": " + error.getMessage());
                        return null;
                    });
            for (var currency : currencyRegistry.enabledCurrencies()) {
                economyService.getBalance(player.getUniqueId(), currency.id()).exceptionally(error -> {
                    getLogger().warning("Could not warm balance cache for " + player.getUniqueId() + ": " + error.getMessage());
                    return null;
                });
            }
        }
    }
}
