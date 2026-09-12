package org.SlidrusForeal.explosionProtector.service;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ExplosionProtectorCommand implements CommandExecutor, TabCompleter {
    private final CommandFacade facade;

    public ExplosionProtectorCommand(CommandFacade facade) {
        this.facade = facade;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("ep")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + facade.msg("usage",
                    "Usage: /ep <status|info|language|reload|toggle|save|cacheclear>"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status":
            case "info":
                if (!sender.hasPermission("explosionprotector.info")) {
                    sender.sendMessage(ChatColor.RED
                            + facade.msg("no_permission", "You do not have permission to use this command."));
                    return true;
                }
                StatusView view = facade.status();
                sender.sendMessage(ChatColor.GREEN
                        + facade.msg("status", "Status") + ": "
                        + (view.enabled() ? ChatColor.DARK_GREEN + facade.msg("enabled", "enabled")
                        : ChatColor.DARK_RED + facade.msg("disabled", "disabled")));
                sender.sendMessage(ChatColor.GREEN + "CoreProtect: "
                        + (view.coreProtectReady() ? ChatColor.DARK_GREEN + "connected"
                        : ChatColor.DARK_RED + "not connected"));
                sender.sendMessage(ChatColor.GREEN
                        + facade.msg("protected_count", "Blocks protected in last operation: ")
                        + ChatColor.YELLOW + view.lastProtectedCount());
                sender.sendMessage(ChatColor.GREEN
                        + "Plugin active: "
                        + (view.pluginActive() ? ChatColor.DARK_GREEN + "yes" : ChatColor.DARK_RED + "no"));
                sender.sendMessage(ChatColor.GREEN
                        + "Local tracker: "
                        + (view.localTrackerEnabled() ? ChatColor.DARK_GREEN + "on" : ChatColor.DARK_RED + "off")
                        + ChatColor.GREEN + " (" + ChatColor.YELLOW + view.trackedSize()
                        + ChatColor.GREEN + "/" + ChatColor.YELLOW + view.maxTrackedBlocks() + ChatColor.GREEN + ")");
                sender.sendMessage(ChatColor.GREEN
                        + "Stats: explosions=" + ChatColor.YELLOW + view.totalExplosions()
                        + ChatColor.GREEN + ", protected=" + ChatColor.YELLOW + view.totalProtectedBlocks());
                sender.sendMessage(ChatColor.GREEN
                        + "Lookup: checks=" + ChatColor.YELLOW + view.totalPlacementChecks()
                        + ChatColor.GREEN + ", local-hit=" + ChatColor.YELLOW + view.totalLocalHits()
                        + ChatColor.GREEN + ", cache-hit=" + ChatColor.YELLOW + view.totalCacheHits()
                        + ChatColor.GREEN + ", cp-lookups=" + ChatColor.YELLOW + view.totalCoreProtectLookups()
                        + ChatColor.GREEN + ", cp-queued=" + ChatColor.YELLOW + view.totalQueuedLookups()
                        + ChatColor.GREEN + ", cp-processed=" + ChatColor.YELLOW + view.totalProcessedLookups()
                        + ChatColor.GREEN + ", cp-failed=" + ChatColor.YELLOW + view.totalFailedLookups()
                        + ChatColor.GREEN + ", cp-dropped=" + ChatColor.YELLOW + view.totalDroppedLookups()
                        + ChatColor.GREEN + ", hit-rate=" + ChatColor.YELLOW + view.hitRate() + "%");
                sender.sendMessage(ChatColor.GREEN
                        + "Queue health: circuit=" + (view.coreProtectCircuitOpen()
                        ? ChatColor.DARK_RED + "open(" + view.coreProtectCircuitRemainingMs() + "ms)"
                        : ChatColor.DARK_GREEN + "closed")
                        + ChatColor.GREEN + ", short-circuit=" + ChatColor.YELLOW + view.totalShortCircuitedLookups()
                        + ChatColor.GREEN + ", circuit-opens=" + ChatColor.YELLOW + view.totalCircuitOpenEvents()
                        + ChatColor.GREEN + ", effective-batch=" + ChatColor.YELLOW + view.coreProtectEffectiveBatchSize()
                        + ChatColor.GREEN + ", drain-interval-ticks=" + ChatColor.YELLOW + view.coreProtectDrainIntervalTicks());
                break;

            case "language":
                if (!sender.hasPermission("explosionprotector.info")) {
                    sender.sendMessage(ChatColor.RED
                            + facade.msg("no_permission", "You do not have permission to use this command."));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /ep language <code>");
                } else if (!facade.setLanguage(args[1])) {
                    sender.sendMessage(ChatColor.RED + facade.msg("invalid_language",
                            "Unsupported language. Valid: en, ru, es, zh, hi, ar, fr, de, ja, pt"));
                } else {
                    sender.sendMessage(ChatColor.GREEN + "Language set to '"
                            + args[1].trim().toLowerCase(Locale.ROOT) + "'.");
                }
                break;

            case "reload":
                if (!sender.hasPermission("explosionprotector.reload")) {
                    sender.sendMessage(ChatColor.RED
                            + facade.msg("no_permission", "You do not have permission to use this command."));
                    return true;
                }
                facade.reload();
                sender.sendMessage(ChatColor.GREEN + facade.msg("reloaded", "Configuration reloaded."));
                break;

            case "toggle":
                if (!sender.hasPermission("explosionprotector.toggle")) {
                    sender.sendMessage(ChatColor.RED
                            + facade.msg("no_permission", "You do not have permission to use this command."));
                    return true;
                }
                boolean active = facade.toggle();
                sender.sendMessage(ChatColor.GREEN + "Explosion protection is now "
                        + (active ? ChatColor.DARK_GREEN + "enabled"
                        : ChatColor.DARK_RED + "disabled") + ChatColor.GREEN + ".");
                break;

            case "save":
                if (!sender.hasPermission("explosionprotector.reload")) {
                    sender.sendMessage(ChatColor.RED
                            + facade.msg("no_permission", "You do not have permission to use this command."));
                    return true;
                }
                facade.saveNow();
                sender.sendMessage(ChatColor.GREEN + "Tracked blocks saved.");
                break;

            case "cacheclear":
                if (!sender.hasPermission("explosionprotector.reload")) {
                    sender.sendMessage(ChatColor.RED
                            + facade.msg("no_permission", "You do not have permission to use this command."));
                    return true;
                }
                facade.clearCache();
                sender.sendMessage(ChatColor.GREEN + "Placement cache cleared.");
                break;

            default:
                sender.sendMessage(ChatColor.RED + facade.msg("unknown_subcommand",
                        "Unknown subcommand. Use status, info, language, reload, toggle, save or cacheclear."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("ep")) {
            return null;
        }
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            for (String sub : List.of("status", "info", "language", "reload", "toggle", "save", "cacheclear")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    completions.add(sub);
                }
            }
            Collections.sort(completions);
            return completions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("language")) {
            List<String> completions = new ArrayList<>();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String lang : SettingsService.SUPPORTED_LANGUAGES) {
                if (lang.startsWith(prefix)) {
                    completions.add(lang);
                }
            }
            return completions;
        }
        return Collections.emptyList();
    }

    public interface CommandFacade {
        String msg(String key, String def);

        StatusView status();

        boolean setLanguage(String languageCode);

        void reload();

        boolean toggle();

        void saveNow();

        void clearCache();
    }

    public record StatusView(
            boolean enabled,
            boolean coreProtectReady,
            int lastProtectedCount,
            boolean pluginActive,
            boolean localTrackerEnabled,
            int trackedSize,
            int maxTrackedBlocks,
            long totalExplosions,
            long totalProtectedBlocks,
            long totalPlacementChecks,
            long totalLocalHits,
            long totalCacheHits,
            long totalCoreProtectLookups,
            long totalQueuedLookups,
            long totalProcessedLookups,
            long totalFailedLookups,
            long totalDroppedLookups,
            long totalShortCircuitedLookups,
            long totalCircuitOpenEvents,
            boolean coreProtectCircuitOpen,
            long coreProtectCircuitRemainingMs,
            int coreProtectEffectiveBatchSize,
            int coreProtectDrainIntervalTicks,
            String hitRate
    ) {
    }
}
