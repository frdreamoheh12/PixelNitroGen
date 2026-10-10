package com.astrastudio.astracurrency.currency;

import com.astrastudio.astracurrency.AstraCurrencyEngine;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Loads one self-contained YAML file per currency and publishes immutable definitions. */
public final class CurrencyRegistry {
    private final AstraCurrencyEngine plugin;
    private final Path directory;
    private final Map<String, CurrencyDefinition> currencies = new ConcurrentHashMap<>();

    public CurrencyRegistry(AstraCurrencyEngine plugin) {
        this.plugin = plugin;
        this.directory = plugin.getDataFolder().toPath().resolve("currencies");
    }

    public synchronized void reload() throws IOException {
        Files.createDirectories(directory);
        saveBundledDefault("currencies/coins.yml", "coins.yml");
        saveBundledDefault("currencies/shards.yml", "shards.yml");

        Map<String, CurrencyDefinition> loaded = new ConcurrentHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted().toList()) {
                try {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
                    CurrencyDefinition definition = CurrencyDefinition.from(yaml);
                    if (!path.getFileName().toString().equalsIgnoreCase(definition.id() + ".yml")) {
                        plugin.getLogger().warning("Currency file " + path.getFileName() + " declares id '" + definition.id()
                                + "'; filenames must be <id>.yml. Skipping it.");
                        continue;
                    }
                    if (loaded.putIfAbsent(definition.id(), definition) != null) {
                        plugin.getLogger().warning("Duplicate currency id '" + definition.id() + "'; keeping the first definition.");
                    }
                } catch (RuntimeException ex) {
                    plugin.getLogger().severe("Invalid currency file " + path.getFileName() + ": " + ex.getMessage());
                }
            }
        }
        if (loaded.values().stream().noneMatch(currency -> currency.enabled())) {
            throw new IllegalStateException("No enabled currencies are configured");
        }
        currencies.clear();
        currencies.putAll(loaded);
        plugin.getLogger().info("Loaded " + loaded.size() + " currency definition(s).");
    }

    public synchronized CurrencyDefinition create(String rawId, String displayName) throws IOException {
        String id = CurrencyDefinition.normalizeId(rawId);
        Path file = directory.resolve(id + ".yml");
        if (Files.exists(file) || currencies.containsKey(id)) {
            throw new IllegalArgumentException("Currency '" + id + "' already exists");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", id);
        yaml.set("enabled", true);
        yaml.set("display.name", displayName == null || displayName.isBlank() ? "<gold><bold>" + titleCase(id) + "</bold></gold>" : displayName);
        yaml.set("display.singular", titleCase(id));
        yaml.set("display.plural", titleCase(id) + "s");
        yaml.set("display.symbol", "◆");
        yaml.set("display.description", "Custom AstraCurrencyEngine currency.");
        yaml.set("formatting.decimals", 2);
        yaml.set("formatting.grouping", true);
        yaml.set("formatting.pattern", "{symbol} {amount}");
        yaml.set("formatting.compact-pattern", "{amount_compact} {symbol}");
        yaml.set("formatting.locale", "en-US");
        yaml.set("formatting.rounding-mode", "DOWN");
        yaml.set("economy.default-balance", "0.00");
        yaml.set("economy.minimum-balance", "0.00");
        yaml.set("economy.maximum-balance", "1000000000000.00");
        yaml.set("economy.transferable", true);
        yaml.set("economy.allow-negative", false);
        yaml.set("economy.transaction-tax", "0");
        yaml.set("economy.leaderboard-enabled", true);
        yaml.set("menus.visible", true);
        yaml.set("menus.display-item", "GOLD_INGOT");
        yaml.set("menus.slot", 0);
        yaml.set("menus.model-data", 0);
        yaml.set("notifications.title-enabled", false);
        yaml.set("notifications.sound-enabled", false);
        yaml.set("notifications.actionbar-enabled", false);
        yaml.save(file.toFile());
        CurrencyDefinition definition = CurrencyDefinition.from(yaml);
        currencies.put(id, definition);
        return definition;
    }

    public synchronized boolean delete(String rawId) throws IOException {
        String id = CurrencyDefinition.normalizeId(rawId);
        Path file = directory.resolve(id + ".yml");
        if (!Files.deleteIfExists(file)) {
            return false;
        }
        currencies.remove(id);
        return true;
    }

    public synchronized CurrencyDefinition reloadOne(String rawId) {
        String id = CurrencyDefinition.normalizeId(rawId);
        Path file = directory.resolve(id + ".yml");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Currency file does not exist: " + id);
        }
        CurrencyDefinition definition = CurrencyDefinition.from(YamlConfiguration.loadConfiguration(file.toFile()));
        if (!definition.id().equals(id)) {
            throw new IllegalArgumentException("Currency id in file must match its filename");
        }
        currencies.put(id, definition);
        return definition;
    }

    public Optional<CurrencyDefinition> find(String rawId) {
        if (rawId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(currencies.get(rawId.trim().toLowerCase(Locale.ROOT)));
    }

    public List<CurrencyDefinition> enabledCurrencies() {
        return currencies.values().stream().filter(CurrencyDefinition::enabled)
                .sorted(Comparator.comparing(CurrencyDefinition::id)).toList();
    }

    public Collection<CurrencyDefinition> allCurrencies() {
        return currencies.values().stream().sorted(Comparator.comparing(CurrencyDefinition::id)).toList();
    }

    public Path directory() {
        return directory;
    }

    private void saveBundledDefault(String resourcePath, String fileName) throws IOException {
        Path target = directory.resolve(fileName);
        if (Files.exists(target)) {
            return;
        }
        try (var resource = plugin.getResource(resourcePath)) {
            if (resource == null) {
                throw new IOException("Bundled resource not found: " + resourcePath);
            }
        }
        plugin.saveResource(resourcePath, false);
    }

    private static String titleCase(String value) {
        String[] parts = value.replace('-', ' ').replace('_', ' ').split("\\s+");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
            }
        }
        return String.join(" ", words);
    }
}
