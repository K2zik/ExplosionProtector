package org.SlidrusForeal.explosionProtector;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import net.coreprotect.CoreProtectAPI.ParseResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.entity.Enderman;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ExplosionProtector extends JavaPlugin implements Listener, TabCompleter {
    private CoreProtectAPI coreProtect;
    private Cache<String, Boolean> placementCache;
    private FileConfiguration messages;
    private int lastProtectedCount = 0;

    @Override
    public void onEnable() {
        // 1) Save default config.yml
        saveDefaultConfig();

        // 2) Extract all messages_*.yml files from the JAR into the plugin folder if they do not exist
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        List<String> langs = List.of("en", "ru", "es", "zh", "hi", "ar", "fr", "de", "ja", "pt");
        for (String lang : langs) {
            String resource = lang.equals("en") ? "messages.yml" : "messages_" + lang + ".yml";
            File out = new File(getDataFolder(), resource);
            if (!out.exists()) {
                saveResource(resource, false);
                getLogger().info("[ExplosionProtector] Extracted " + resource);
            }
        }

        // 3) Load the language selected in config
        String lang = getConfig().getString("language", "en");
        loadLanguageMessages(lang);

        // 4) Initialize CoreProtect lookup cache
        placementCache = CacheBuilder.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(10000)
                .build();

        // 5) Obtain CoreProtect API instance
        coreProtect = fetchCoreProtectAPI();
        if (coreProtect == null || coreProtect.APIVersion() < 7) {
            getLogger().severe(messages.getString("coreprotect_not_found",
                    "[ExplosionProtector] CoreProtect not found or API incompatible."));
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 6) Register event listeners and command
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ep").setExecutor(this);
        getCommand("ep").setTabCompleter(this);
        getLogger().info(messages.getString("plugin_enabled",
                "[ExplosionProtector] Plugin enabled: protecting player-placed blocks from explosions."));
    }

    @Override
    public void onDisable() {
        String msg = messages != null
                ? messages.getString("plugin_disabled", "[ExplosionProtector] Plugin disabled.")
                : "[ExplosionProtector] Plugin disabled.";
        getLogger().info(msg);
    }

    /** Load message file for the specified language code */
    private void loadLanguageMessages(String lang) {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        String fileName = lang.equals("en") ? "messages.yml" : "messages_" + lang + ".yml";
        File msgFile = new File(getDataFolder(), fileName);
        if (!msgFile.exists()) {
            getLogger().warning("[ExplosionProtector] Language file " + fileName +
                    " not found, using default messages.yml");
            msgFile = new File(getDataFolder(), "messages.yml");
        }
        messages = YamlConfiguration.loadConfiguration(msgFile);
    }

    /** Retrieve CoreProtectAPI instance */
    private CoreProtectAPI fetchCoreProtectAPI() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        return (plugin instanceof CoreProtect) ? ((CoreProtect) plugin).getAPI() : null;
    }

    /** Check if entity is an item frame or a painting that must never break. */
    private boolean isProtectedHanging(Entity entity) {
        EntityType type = entity.getType();
        return type == EntityType.ITEM_FRAME ||
               type == EntityType.GLOW_ITEM_FRAME ||
               type == EntityType.PAINTING;
    }

    /**
     * Handle entity-based explosions:
     * - For TNT: allow chain reactions to break TNT and natural blocks,
     *   but protect all other player-placed blocks and frames.
     * - For other entities: protect all player-placed blocks and frames.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof TNTPrimed) {
            int before = event.blockList().size();
            event.blockList().removeIf(block ->
                    block.getType() != Material.TNT && isPlayerPlaced(block)
            );
            lastProtectedCount = before - event.blockList().size();
            return;
        }
        int before = event.blockList().size();
        event.blockList().removeIf(this::isPlayerPlaced);
        lastProtectedCount = before - event.blockList().size();
    }

    /** Handle block-based explosions (e.g., beds, respawn anchors) */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Material type = event.getBlock().getType();
        if (type == Material.RESPAWN_ANCHOR || Tag.BEDS.isTagged(type)) {
            lastProtectedCount = 0;
            return;
        }
        int before = event.blockList().size();
        event.blockList().removeIf(this::isPlayerPlaced);
        lastProtectedCount = before - event.blockList().size();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        // no special handling
    }

    /** Prevent Endermen from moving blocks (pickup/place) */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Enderman) {
            event.setCancelled(true);
        }
    }

    /** Prevent TNT projectile explosions */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof TNTPrimed) {
            ((TNTPrimed) event.getEntity()).remove();
            getLogger().info(messages.getString("tnt_projectile_prevented",
                    "[ExplosionProtector] TNT projectile explosion prevented."));
        }
    }

    /** Keep paintings and item frames from being removed by any break cause. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHangingBreak(HangingBreakEvent event) {
        if (isProtectedHanging(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Block direct damage to paintings and item frames (players, projectiles, explosions, etc.). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (isProtectedHanging(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Check via CoreProtect if a block was placed by a player */
    private boolean isPlayerPlaced(Block block) {
        if (coreProtect == null) return false;
        String key = block.getWorld().getName() + ":" +
                block.getX() + "," + block.getY() + "," + block.getZ();
        Boolean cached = placementCache.getIfPresent(key);
        if (cached != null) return cached;

        boolean placed = false;
        try {
            List<String[]> lookup = coreProtect.blockLookup(block, 0);
            if (lookup != null) {
                for (int i = lookup.size() - 1; i >= 0; i--) {
                    ParseResult result = coreProtect.parseResult(lookup.get(i));
                    if (result != null && result.getActionId() == 1) {
                        String player = result.getPlayer();
                        if (player != null && !player.startsWith("#")) {
                            placed = true;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning(messages.getString("lookup_error",
                    "[ExplosionProtector] Error during CoreProtect lookup: ") + e.getMessage());
        }
        placementCache.put(key, placed);
        return placed;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("ep")) return false;
        if (!sender.hasPermission("explosionprotector.info")) {
            sender.sendMessage(ChatColor.RED +
                    messages.getString("no_permission", "You do not have permission to use this command."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED +
                    messages.getString("usage", "Usage: /ep <status|info|language>"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status":
            case "info":
                boolean enabled = coreProtect != null && coreProtect.APIVersion() >= 7;
                sender.sendMessage(ChatColor.GREEN +
                        messages.getString("status", "Status") + ": " +
                        (enabled ? ChatColor.DARK_GREEN + messages.getString("enabled", "enabled")
                                : ChatColor.DARK_RED + messages.getString("disabled", "disabled")));
                sender.sendMessage(ChatColor.GREEN +
                        messages.getString("protected_count", "Blocks protected in last operation: ") +
                        ChatColor.YELLOW + lastProtectedCount);
                break;

            case "language":
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /ep language <code>");
                } else {
                    String newLang = args[1];
                    getConfig().set("language", newLang);
                    saveConfig();
                    loadLanguageMessages(newLang);
                    sender.sendMessage(ChatColor.GREEN +
                            "Language set to '" + newLang + "'.");
                }
                break;

            default:
                sender.sendMessage(ChatColor.RED +
                        messages.getString("unknown_subcommand",
                                "Unknown subcommand. Use status, info or language."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command cmd,
                                      String alias,
                                      String[] args) {
        if (!cmd.getName().equalsIgnoreCase("ep")) return null;
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            for (String sub : List.of("status", "info", "language")) {
                if (sub.startsWith(args[0].toLowerCase())) subs.add(sub);
            }
            Collections.sort(subs);
            return subs;
        }
        return Collections.emptyList();
    }
}
