package io.github.freecoreagent.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Manages plugin language files and translations.
 */
public final class LanguageManager {
    private final JavaPlugin plugin;
    private FileConfiguration langConfig;
    private File langFile;

    public LanguageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        String locale = plugin.getConfig().getString("locale", "zh_CN");
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        langFile = new File(langDir, locale + ".yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang/" + locale + ".yml", false);
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);

        // Load defaults from jar if available
        InputStream defStream = plugin.getResource("lang/" + locale + ".yml");
        if (defStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defStream, StandardCharsets.UTF_8));
            langConfig.setDefaults(defConfig);
        }
    }

    public void reload() {
        load();
    }

    public String message(String path, Object... replacements) {
        if (langConfig == null) return path;
        String raw = langConfig.getString(path, path);
        if (replacements != null && replacements.length > 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                if (i + 1 < replacements.length) {
                    raw = raw.replace(String.valueOf(replacements[i]), String.valueOf(replacements[i + 1]));
                }
            }
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String prefix() {
        return message("prefix");
    }
}
