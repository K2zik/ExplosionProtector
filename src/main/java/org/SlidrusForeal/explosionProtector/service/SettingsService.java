package org.SlidrusForeal.explosionProtector.service;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SettingsService {
    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public SettingsService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureLanguageResources() {
        List<String> langs = List.of("en", "ru", "es", "zh", "hi", "ar", "fr", "de", "ja", "pt");
        for (String lang : langs) {
            String resource = lang.equals("en") ? "messages.yml" : "messages_" + lang + ".yml";
            File out = new File(plugin.getDataFolder(), resource);
            if (!out.exists()) {
                plugin.saveResource(resource, false);
                plugin.getLogger().info("[ExplosionProtector] Extracted " + resource);
            }
        }
    }

    public PluginSettings loadFromConfig() {
        FileConfiguration cfg = plugin.getConfig();
        String lang = cfg.getString("language", "en");
        loadLanguageMessages(lang == null ? "en" : lang);
        long lookupTimeoutMs = readLookupTimeoutMs(cfg);

        return new PluginSettings(
                Math.max(1L, cfg.getLong("cache-expire-seconds", 30L)),
                Math.max(1L, cfg.getLong("cache-negative-expire-seconds", 10L)),
                Math.max(1000L, cfg.getLong("cache-max-size", 10000L)),
                Math.max(100L, cfg.getLong("cache-negative-max-size", 2500L)),
                Math.max(1, cfg.getInt("lookup-history-limit", 10)),
                Math.max(1, cfg.getInt("coreprotect-async-batch-size", 64)),
                Math.max(100, cfg.getInt("coreprotect-async-queue-max-size", 10000)),
                Math.max(1, cfg.getInt("coreprotect-async-worker-threads", 2)),
                Math.max(100L, lookupTimeoutMs),
                Math.max(32, cfg.getInt("coreprotect-resolved-apply-batch-size", 512)),
                Math.max(1, cfg.getInt("coreprotect-circuit-failure-threshold", 8)),
                Math.max(1000L, cfg.getLong("coreprotect-circuit-open-ms", 45000L)),
                Math.max(1, cfg.getInt("coreprotect-health-min-batch-size", 8)),
                Math.max(1, cfg.getInt("coreprotect-health-max-drain-interval-ticks", 4)),
                Math.max(1, cfg.getInt("coreprotect-health-recovery-successes", 20)),
                cfg.getBoolean("protect-first-unknown", true),
                cfg.getBoolean("protect-player-placed-blocks", true),
                cfg.getBoolean("protect-hanging-from-explosions", true),
                cfg.getBoolean("protect-item-frames", true),
                cfg.getBoolean("protect-paintings", true),
                cfg.getBoolean("block-enderman-grief", true),
                cfg.getBoolean("tnt-chain-breaks-only-tnt", true),
                cfg.getBoolean("persist-player-placed-blocks", true),
                cfg.getBoolean("enable-local-tracker", true),
                cfg.getBoolean("require-coreprotect", true),
                cfg.getBoolean("plugin-active", true),
                cfg.getBoolean("autosave-enabled", true),
                Math.max(30, cfg.getInt("autosave-interval-seconds", 300)),
                Math.max(1000, cfg.getInt("max-tracked-blocks", 500000)),
                cfg.getBoolean("cleanup-on-chunk-unload", true),
                cfg.getBoolean("cleanup-unloaded-worlds-on-start", true),
                normalizeWorlds(cfg.getStringList("enabled-worlds")),
                normalizeWorlds(cfg.getStringList("disabled-worlds")),
                cfg.getBoolean("debug", false),
                Math.max(100L, cfg.getLong("debug-cache-log-cooldown-ms", 2000L)),
                Math.max(1000L, cfg.getLong("out-of-range-pack-warn-cooldown-ms", 30000L))
        );
    }

    private long readLookupTimeoutMs(FileConfiguration cfg) {
        if (cfg.contains("coreprotect-lookup-timeout-ms")) {
            return cfg.getLong("coreprotect-lookup-timeout-ms", 2000L);
        }
        return cfg.getLong("coreprotect-sync-lookup-timeout-ms", 2000L);
    }

    public String msg(String key, String def) {
        String value = def;
        if (messages != null && messages.contains(key)) {
            value = messages.getString(key, def);
        }
        if (value == null) {
            value = def;
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public void loadLanguageMessages(String lang) {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        String fileName = lang.equals("en") ? "messages.yml" : "messages_" + lang + ".yml";
        File msgFile = new File(plugin.getDataFolder(), fileName);
        if (!msgFile.exists()) {
            plugin.getLogger().warning("[ExplosionProtector] Language file " + fileName
                    + " not found, using default messages.yml");
            msgFile = new File(plugin.getDataFolder(), "messages.yml");
        }
        messages = YamlConfiguration.loadConfiguration(msgFile);
    }

    public boolean isWorldEnabled(String worldName, PluginSettings settings) {
        String world = normalizeWorldName(worldName);
        if (world == null || world.isBlank()) {
            return true;
        }
        if (!settings.enabledWorlds().isEmpty() && !settings.enabledWorlds().contains(world)) {
            return false;
        }
        return !settings.disabledWorlds().contains(world);
    }

    public String normalizeWorldName(String worldName) {
        if (worldName == null) {
            return null;
        }
        return worldName.trim().toLowerCase(Locale.ROOT);
    }

    private Set<String> normalizeWorlds(List<String> worlds) {
        if (worlds == null || worlds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<>();
        for (String world : worlds) {
            String name = normalizeWorldName(world);
            if (name == null || name.isBlank()) {
                continue;
            }
            normalized.add(name);
        }
        return normalized;
    }
}
