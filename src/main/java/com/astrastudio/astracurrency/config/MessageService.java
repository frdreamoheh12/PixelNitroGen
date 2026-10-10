package com.astrastudio.astracurrency.config;

import com.astrastudio.astracurrency.AstraCurrencyEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Central message loader with MiniMessage, hex colors, and legacy ampersand support. */
public final class MessageService {
    private static final Pattern LEGACY_CODES = Pattern.compile("(?i)(?:&|§)[0-9a-fk-orx]");
    private static final Set<String> TRUSTED_MINIMESSAGE_VALUES = Set.of(
            "currency_name", "symbol", "balance", "amount", "tax", "usage", "action"
    );

    private final AstraCurrencyEngine plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private YamlConfiguration messages;
    private YamlConfiguration menus;
    private YamlConfiguration dialogs;

    public MessageService(AstraCurrencyEngine plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        this.messages = load("messages.yml");
        this.menus = load("menus.yml");
        this.dialogs = load("dialogs.yml");
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    public void send(CommandSender sender, String key, Map<String, String> replacements) {
        String raw = messages.getString(key, "<red>Missing message: " + key + "</red>");
        Component component = component(raw, replacements);
        if (plugin.getConfig().getBoolean("messages.prefix-enabled", true)
                && !key.equals("help-header") && !raw.isBlank()) {
            component = component(messages.getString("prefix", ""), Map.of()).append(component);
        }
        sender.sendMessage(component);
    }

    public void sendLines(CommandSender sender, String key, Map<String, String> replacements) {
        List<String> lines = messages.getStringList(key);
        for (String line : lines) {
            Component component = component(line, replacements);
            if (plugin.getConfig().getBoolean("messages.prefix-enabled", true)) {
                component = component(messages.getString("prefix", ""), Map.of()).append(component);
            }
            sender.sendMessage(component);
        }
    }

    public void sendUnprefixed(CommandSender sender, String raw) {
        sender.sendMessage(component(raw, Map.of()));
    }

    public String message(String key) {
        return messages.getString(key, "");
    }

    public String menuString(String key, String fallback) {
        return menus.getString(key, fallback);
    }

    public int menuInt(String key, int fallback) {
        return menus.getInt(key, fallback);
    }

    public boolean menuBoolean(String key, boolean fallback) {
        return menus.getBoolean(key, fallback);
    }

    public List<String> menuStringList(String key) {
        return menus.getStringList(key);
    }

    public String dialogString(String key, String fallback) {
        return dialogs.getString(key, fallback);
    }

    public Component component(String raw, Map<String, String> replacements) {
        String text = substitute(raw, replacements);
        if (text.contains("<")) {
            return miniMessage.deserialize(text);
        }
        if (LEGACY_CODES.matcher(text).find()) {
            if (text.indexOf('§') >= 0) {
                return LegacyComponentSerializer.legacySection().deserialize(text);
            }
            return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
        }
        return Component.text(text);
    }

    public String legacy(String raw, Map<String, String> replacements) {
        return LegacyComponentSerializer.legacySection().serialize(component(raw, replacements));
    }

    private YamlConfiguration load(String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        return YamlConfiguration.loadConfiguration(file);
    }

    private String substitute(String raw, Map<String, String> replacements) {
        String result = raw == null ? "" : raw;
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (!TRUSTED_MINIMESSAGE_VALUES.contains(entry.getKey())) {
                    value = miniMessage.escapeTags(value);
                }
                result = result.replace("{" + entry.getKey() + "}", value);
            }
        }
        return result;
    }
}
