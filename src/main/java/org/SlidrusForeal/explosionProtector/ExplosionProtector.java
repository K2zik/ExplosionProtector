package org.SlidrusForeal.explosionProtector;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.SlidrusForeal.explosionProtector.model.BlockKey;
import org.SlidrusForeal.explosionProtector.model.PackedBlockUtil;
import org.SlidrusForeal.explosionProtector.service.CoreProtectLookupQueueService;
import org.SlidrusForeal.explosionProtector.service.ExplosionProtectorCommand;
import org.SlidrusForeal.explosionProtector.service.ExplosionProtectorCommand.StatusView;
import org.SlidrusForeal.explosionProtector.service.PlacementCacheService;
import org.SlidrusForeal.explosionProtector.service.PluginSettings;
import org.SlidrusForeal.explosionProtector.service.SettingsService;
import org.SlidrusForeal.explosionProtector.service.SqliteStorageService;
import org.SlidrusForeal.explosionProtector.service.TrackerService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class ExplosionProtector extends JavaPlugin
        implements Listener, ExplosionProtectorCommand.CommandFacade {
    private SettingsService settingsService;
    private PluginSettings settings;
    private volatile boolean pluginActive = true;

    private final PlacementCacheService placementCacheService = new PlacementCacheService();
    private final TrackerService trackerService = new TrackerService();
    private final SqliteStorageService storageService = new SqliteStorageService();
    private CoreProtectLookupQueueService coreProtectQueueService;
    private int resolvedApplyTaskId = -1;

    private CoreProtectAPI coreProtect;
    private int autosaveTaskId = -1;
    private volatile boolean storageAvailable = false;
    private final AtomicLong mutationVersion = new AtomicLong(0L);
    private final AtomicLong savedVersion = new AtomicLong(0L);
    private final Object dirtyLock = new Object();
    private final Map<String, Set<Long>> dirtyUpserts = new HashMap<>();
    private final Map<String, Set<Long>> dirtyDeletes = new HashMap<>();
    private final ConcurrentLinkedQueue<ResolvedLookup> resolvedLookupQueue = new ConcurrentLinkedQueue<>();

    private final Map<String, Long> debugRateLimitAt = new ConcurrentHashMap<>();
    private final Map<String, Long> warnRateLimitAt = new ConcurrentHashMap<>();
    private int lastProtectedCount = 0;
    private long totalExplosions = 0L;
    private long totalProtectedBlocks = 0L;
    private long totalPlacementChecks = 0L;
    private long totalLocalHits = 0L;
    private long totalCacheHits = 0L;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        settingsService = new SettingsService(this);
        settingsService.ensureLanguageResources();
        settings = settingsService.loadFromConfig();
        pluginActive = settings.pluginActive();

        placementCacheService.rebuild(settings);
        trackerService.setMaxTrackedBlocks(settings.maxTrackedBlocks());

        openStorageIfNeeded();
        if (settings.enableLocalTracker() && settings.persistPlayerPlacedBlocks()) {
            loadTrackedBlocksFromStorage();
        } else {
            trackerService.clear();
            resetDirtyState();
        }

        coreProtect = fetchCoreProtectAPI();
        if (coreProtect == null && settings.requireCoreProtect()) {
            closeStorage();
            getLogger().severe(msg("coreprotect_not_found",
                    "[ExplosionProtector] CoreProtect not found or API incompatible."));
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (coreProtect == null) {
            getLogger().warning(msg("coreprotect_optional_missing",
                    "[ExplosionProtector] CoreProtect not found. Plugin continues with local tracking only."));
        }

        coreProtectQueueService = new CoreProtectLookupQueueService(this, this::onQueuedLookupResolved, this::debug);
        coreProtectQueueService.applySettings(settings, coreProtect);
        coreProtectQueueService.start();
        startResolvedApplyTask();

        getServer().getPluginManager().registerEvents(this, this);
        registerCommandExecutor();

        if (settings.cleanupUnloadedWorldsOnStart()) {
            cleanupEntriesFromUnloadedWorlds();
        }
        startAutosaveTask();

        getLogger().info(msg("plugin_enabled",
                "[ExplosionProtector] Plugin enabled: protecting player-placed blocks from explosions."));
    }

    @Override
    public void onDisable() {
        stopAutosaveTask();
        if (coreProtectQueueService != null) {
            coreProtectQueueService.stop();
        }
        stopResolvedApplyTask();
        applyQueuedLookupResolutions(Integer.MAX_VALUE);
        saveTrackedBlocksIfNeeded();
        closeStorage();

        getLogger().info(msg("plugin_disabled", "[ExplosionProtector] Plugin disabled."));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!pluginActive) {
            return;
        }

        String worldName = event.getLocation().getWorld() != null ? event.getLocation().getWorld().getName() : null;
        if (!isWorldEnabled(worldName)) {
            return;
        }

        boolean debugEnabled = settings.debugEnabled();
        long started = debugEnabled ? System.nanoTime() : 0L;
        int before = event.blockList().size();
        totalExplosions++;

        int protectedCount = 0;
        if (settings.protectPlayerPlacedBlocks()) {
            protectedCount = filterProtectedBlocks(event.blockList(),
                    event.getEntity() instanceof TNTPrimed && settings.tntChainBreaksOnlyTnt());
        }

        lastProtectedCount = protectedCount;
        totalProtectedBlocks += protectedCount;
        int removed = removeDestroyedTrackedBlocks(event.blockList());

        if (debugEnabled) {
            debug("entity explosion " + event.getEntityType().name().toLowerCase(Locale.ROOT)
                    + " checked=" + before
                    + " protected=" + protectedCount
                    + " removed-tracked=" + removed
                    + " took=" + formatMillis(started) + "ms");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!pluginActive) {
            return;
        }
        if (event.getBlock().getWorld() == null) {
            return;
        }
        String worldName = event.getBlock().getWorld().getName();
        if (!isWorldEnabled(worldName)) {
            return;
        }

        boolean debugEnabled = settings.debugEnabled();
        long started = debugEnabled ? System.nanoTime() : 0L;
        int before = event.blockList().size();
        totalExplosions++;

        Material source = event.getBlock().getType();
        if (debugEnabled && (source == Material.RESPAWN_ANCHOR || Tag.BEDS.isTagged(source))) {
            debug("block explosion source=" + source.name().toLowerCase(Locale.ROOT)
                    + " uses standard protection checks");
        }

        int protectedCount = 0;
        if (settings.protectPlayerPlacedBlocks()) {
            protectedCount = filterProtectedBlocks(event.blockList(), false);
        }

        lastProtectedCount = protectedCount;
        totalProtectedBlocks += protectedCount;
        int removed = removeDestroyedTrackedBlocks(event.blockList());

        if (debugEnabled) {
            debug("block explosion source=" + source.name().toLowerCase(Locale.ROOT)
                    + " checked=" + before
                    + " protected=" + protectedCount
                    + " removed-tracked=" + removed
                    + " took=" + formatMillis(started) + "ms");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (pluginActive
                && settings.blockEndermanGrief()
                && event.getEntity() instanceof Enderman
                && isWorldEnabled(event.getBlock().getWorld().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!settings.enableLocalTracker() || !isWorldEnabled(event.getBlockPlaced().getWorld().getName())) {
            return;
        }

        Long packed = PackedBlockUtil.tryPack(event.getBlockPlaced());
        if (packed == null) {
            warnUnsupportedCoordinates(event.getBlockPlaced(), "block-place");
            return;
        }

        String world = event.getBlockPlaced().getWorld().getName();
        trackPlacedBlock(world, packed);
        placementCacheService.put(new BlockKey(world, packed), true);
        debugRateLimited("track-place", settings.debugCacheLogCooldownMs(), () -> "track place " + world + ":" + packed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!settings.enableLocalTracker() || !isWorldEnabled(event.getBlock().getWorld().getName())) {
            return;
        }

        Long packed = PackedBlockUtil.tryPack(event.getBlock());
        if (packed == null) {
            warnUnsupportedCoordinates(event.getBlock(), "block-break");
            return;
        }

        String world = event.getBlock().getWorld().getName();
        untrackPlacedBlock(world, packed);
        placementCacheService.invalidate(new BlockKey(world, packed));
        debugRateLimited("track-break", settings.debugCacheLogCooldownMs(), () -> "track break " + world + ":" + packed);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!settings.enableLocalTracker() || !settings.cleanupOnChunkUnload()) {
            return;
        }
        if (!isWorldEnabled(event.getWorld().getName())) {
            return;
        }

        int removed = removeTrackedBlocksForChunk(event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ());
        if (removed > 0) {
            debug("chunk unload cleanup world=" + event.getWorld().getName()
                    + " chunk=" + event.getChunk().getX() + "," + event.getChunk().getZ()
                    + " removed=" + removed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (!settings.enableLocalTracker()) {
            return;
        }
        int removed = removeTrackedBlocksForWorld(event.getWorld().getName());
        if (removed > 0) {
            debug("world unload cleanup world=" + event.getWorld().getName() + " removed=" + removed);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHangingBreak(HangingBreakEvent event) {
        if (pluginActive
                && settings.protectHangingFromExplosions()
                && isWorldEnabled(event.getEntity().getWorld().getName())
                && isProtectedHanging(event.getEntity())
                && event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (pluginActive
                && settings.protectHangingFromExplosions()
                && isWorldEnabled(event.getEntity().getWorld().getName())
                && isProtectedHanging(event.getEntity())
                && (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) {
            event.setCancelled(true);
        }
    }

    private boolean isPlayerPlaced(Block block) {
        if (!settings.protectPlayerPlacedBlocks() || !isWorldEnabled(block.getWorld().getName())) {
            return false;
        }
        totalPlacementChecks++;

        Long packed = PackedBlockUtil.tryPack(block);
        if (packed == null) {
            warnUnsupportedCoordinates(block, "lookup");
            return settings.protectFirstUnknown();
        }

        String world = block.getWorld().getName();
        BlockKey key = new BlockKey(world, packed);

        if (settings.enableLocalTracker() && trackerService.contains(world, packed)) {
            totalLocalHits++;
            debugRateLimited("cache-hit-local", settings.debugCacheLogCooldownMs(), () -> "cache-hit local " + key);
            return true;
        }

        Boolean cached = placementCacheService.get(key);
        if (cached != null) {
            totalCacheHits++;
            debugRateLimited("cache-hit", settings.debugCacheLogCooldownMs(), () -> "cache-hit " + key + " => " + cached);
            return cached;
        }

        debugRateLimited("cache-miss", settings.debugCacheLogCooldownMs(), () -> "cache-miss " + key);

        boolean fallback = settings.protectFirstUnknown();
        placementCacheService.put(key, fallback);
        if (fallback && settings.enableLocalTracker()) {
            trackPlacedBlock(world, packed);
        }
        if (coreProtectQueueService != null && coreProtectQueueService.isAvailable()) {
            coreProtectQueueService.enqueue(key, block.getX(), block.getY(), block.getZ());
        }
        return fallback;
    }

    /**
     * Mutates the incoming block list by removing blocks that must be protected from explosion.
     */
    private int filterProtectedBlocks(List<Block> blocks, boolean preserveTntInChainMode) {
        if (blocks.isEmpty()) {
            return 0;
        }

        List<Block> keep = null;
        int protectedCount = 0;
        int index = 0;
        for (Block block : blocks) {
            if (preserveTntInChainMode && block.getType() == Material.TNT) {
                if (keep != null) {
                    keep.add(block);
                }
                index++;
                continue;
            }
            if (isPlayerPlaced(block)) {
                protectedCount++;
                if (keep == null) {
                    keep = new ArrayList<>(blocks.size() - 1);
                    if (index > 0) {
                        keep.addAll(blocks.subList(0, index));
                    }
                }
                index++;
                continue;
            }
            if (keep != null) {
                keep.add(block);
            }
            index++;
        }

        if (keep != null) {
            blocks.clear();
            blocks.addAll(keep);
        }
        return protectedCount;
    }

    private int removeDestroyedTrackedBlocks(List<Block> destroyedBlocks) {
        if (!settings.enableLocalTracker()) {
            return 0;
        }

        int removed = 0;
        for (Block block : destroyedBlocks) {
            Long packed = PackedBlockUtil.tryPack(block);
            if (packed == null) {
                continue;
            }
            String world = block.getWorld().getName();
            if (untrackPlacedBlock(world, packed)) {
                removed++;
            }
            placementCacheService.invalidate(new BlockKey(world, packed));
        }
        return removed;
    }

    private void onQueuedLookupResolved(BlockKey key, boolean placed) {
        resolvedLookupQueue.offer(new ResolvedLookup(key, placed));
    }

    private void trackPlacedBlock(String world, long packed) {
        AtomicInteger evictedCount = new AtomicInteger(0);
        boolean added = trackerService.track(world, packed, evicted -> {
            placementCacheService.invalidate(evicted);
            markUntrackedDirty(evicted.world(), evicted.packed());
            evictedCount.incrementAndGet();
        });
        if (added) {
            markTrackedDirty(world, packed);
        }
        if (evictedCount.get() > 0) {
            warnRateLimited("tracker-lru", settings.outOfRangePackWarnCooldownMs(),
                    "[ExplosionProtector] LRU eviction removed " + evictedCount.get() + " old tracked blocks.");
        }
    }

    private boolean untrackPlacedBlock(String world, long packed) {
        boolean removed = trackerService.untrack(world, packed);
        if (removed) {
            markUntrackedDirty(world, packed);
        }
        return removed;
    }

    private int removeTrackedBlocksForChunk(String world, int chunkX, int chunkZ) {
        return trackerService.removeChunk(world, chunkX, chunkZ, key -> {
            placementCacheService.invalidate(key);
            markUntrackedDirty(key.world(), key.packed());
        });
    }

    private int removeTrackedBlocksForWorld(String world) {
        return trackerService.removeWorld(world, key -> {
            placementCacheService.invalidate(key);
            markUntrackedDirty(key.world(), key.packed());
        });
    }

    private void cleanupEntriesFromUnloadedWorlds() {
        Set<String> loadedWorlds = new HashSet<>();
        Bukkit.getWorlds().forEach(world -> loadedWorlds.add(world.getName()));

        int removed = trackerService.cleanupUnloadedWorlds(loadedWorlds, key -> {
            placementCacheService.invalidate(key);
            markUntrackedDirty(key.world(), key.packed());
        });
        if (removed > 0) {
            debug("removed " + removed + " tracker entries from unloaded worlds");
        }
    }

    private void startResolvedApplyTask() {
        stopResolvedApplyTask();
        resolvedApplyTaskId = Bukkit.getScheduler().runTaskTimer(
                this, () -> applyQueuedLookupResolutions(settings.coreProtectResolvedApplyBatchSize()), 1L, 1L)
                .getTaskId();
    }

    private void stopResolvedApplyTask() {
        if (resolvedApplyTaskId != -1) {
            Bukkit.getScheduler().cancelTask(resolvedApplyTaskId);
            resolvedApplyTaskId = -1;
        }
    }

    private void applyQueuedLookupResolutions(int limit) {
        int safeLimit = Math.max(1, limit);
        int processed = 0;
        while (processed < safeLimit) {
            ResolvedLookup resolved = resolvedLookupQueue.poll();
            if (resolved == null) {
                break;
            }
            applyLookupResolution(resolved.key(), resolved.placed());
            processed++;
        }
    }

    private void applyLookupResolution(BlockKey key, boolean placed) {
        placementCacheService.put(key, placed);
        if (!settings.enableLocalTracker()) {
            return;
        }
        if (placed) {
            trackPlacedBlock(key.world(), key.packed());
        } else {
            untrackPlacedBlock(key.world(), key.packed());
        }
    }

    private void openStorageIfNeeded() {
        if (!settings.persistPlayerPlacedBlocks()) {
            closeStorage();
            return;
        }
        try {
            storageService.open(getDataFolder());
            storageAvailable = true;
        } catch (SQLException e) {
            storageAvailable = false;
            getLogger().warning(msg("save_error",
                    "[ExplosionProtector] Failed to initialize SQLite storage: ") + e.getMessage());
        }
    }

    private void closeStorage() {
        try {
            storageService.close();
        } catch (SQLException e) {
            getLogger().warning("[ExplosionProtector] Failed to close SQLite connection: " + e.getMessage());
        } finally {
            storageAvailable = false;
        }
    }

    private void loadTrackedBlocksFromStorage() {
        trackerService.clear();
        if (!storageAvailable) {
            resetDirtyState();
            return;
        }
        try {
            Map<String, List<Long>> snapshot = storageService.loadAll();
            if (snapshot.isEmpty()) {
                Map<String, List<Long>> migrated = loadLegacyYamlIfPresent();
                if (!migrated.isEmpty()) {
                    trackerService.loadSnapshot(migrated);
                    markDirty();
                    saveTrackedBlocksIfNeeded();
                }
            } else {
                trackerService.loadSnapshot(snapshot);
            }
            resetDirtyState();
            debug("loaded player-placed blocks: " + trackerService.size());
        } catch (SQLException e) {
            getLogger().warning(msg("save_error",
                    "[ExplosionProtector] Failed to load SQLite tracked blocks: ") + e.getMessage());
            resetDirtyState();
        }
    }

    private Map<String, List<Long>> loadLegacyYamlIfPresent() {
        File legacy = new File(getDataFolder(), "player_placed.yml");
        if (!legacy.exists()) {
            return Map.of();
        }

        FileConfiguration placedData = YamlConfiguration.loadConfiguration(legacy);
        Map<String, Set<Long>> temp = new HashMap<>();

        ConfigurationSection worlds = placedData.getConfigurationSection("worlds");
        if (worlds != null) {
            for (String worldKey : worlds.getKeys(false)) {
                String world = decodeWorldKey(worldKey);
                if (world == null || world.isBlank()) {
                    continue;
                }
                for (Long packed : worlds.getLongList(worldKey)) {
                    temp.computeIfAbsent(world, ignored -> new HashSet<>()).add(packed);
                }
            }
        }

        List<String> legacyBlocks = placedData.getStringList("blocks");
        for (String entry : legacyBlocks) {
            String[] parsed = parseLegacyKey(entry);
            if (parsed == null) {
                continue;
            }
            try {
                int x = Integer.parseInt(parsed[1]);
                int y = Integer.parseInt(parsed[2]);
                int z = Integer.parseInt(parsed[3]);
                Long packed = PackedBlockUtil.tryPack(x, y, z);
                if (packed != null) {
                    temp.computeIfAbsent(parsed[0], ignored -> new HashSet<>()).add(packed);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (temp.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Long>> migrated = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : temp.entrySet()) {
            migrated.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        getLogger().info("[ExplosionProtector] Migrated legacy player_placed.yml data into SQLite.");
        return migrated;
    }

    private void startAutosaveTask() {
        stopAutosaveTask();
        if (!settings.autosaveEnabled() || !settings.persistPlayerPlacedBlocks() || !settings.enableLocalTracker()) {
            return;
        }

        long periodTicks = settings.autosaveIntervalSeconds() * 20L;
        autosaveTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, this::saveTrackedBlocksIfNeeded, periodTicks, periodTicks).getTaskId();
        debug("autosave task started, every " + settings.autosaveIntervalSeconds() + "s");
    }

    private void stopAutosaveTask() {
        if (autosaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autosaveTaskId);
            autosaveTaskId = -1;
        }
    }

    private synchronized void saveTrackedBlocksIfNeeded() {
        if (!settings.persistPlayerPlacedBlocks() || !settings.enableLocalTracker() || !storageAvailable) {
            return;
        }
        long targetVersion = mutationVersion.get();
        if (targetVersion <= savedVersion.get()) {
            return;
        }

        DirtyBatch dirtyBatch = drainDirtyBatch();
        try {
            if (dirtyBatch.isEmpty()) {
                Map<String, List<Long>> snapshot = trackerService.snapshot();
                storageService.saveSnapshot(snapshot);
            } else {
                storageService.applyChanges(dirtyBatch.upserts(), dirtyBatch.deletes());
            }
            savedVersion.accumulateAndGet(targetVersion, Math::max);
            debug("saved player-placed blocks: " + trackerService.size());
        } catch (SQLException e) {
            requeueDirtyBatch(dirtyBatch);
            getLogger().warning(msg("save_error",
                    "[ExplosionProtector] Failed to save SQLite tracked blocks: ") + e.getMessage());
        }
    }

    private void resetDirtyState() {
        clearDirtyChanges();
        long version = mutationVersion.get();
        savedVersion.set(version);
    }

    private void markDirty() {
        mutationVersion.incrementAndGet();
    }

    private void markTrackedDirty(String world, long packed) {
        synchronized (dirtyLock) {
            dirtyDeletes.computeIfPresent(world, (ignored, values) -> {
                values.remove(packed);
                return values.isEmpty() ? null : values;
            });
            dirtyUpserts.computeIfAbsent(world, ignored -> new HashSet<>()).add(packed);
        }
        markDirty();
    }

    private void markUntrackedDirty(String world, long packed) {
        synchronized (dirtyLock) {
            dirtyUpserts.computeIfPresent(world, (ignored, values) -> {
                values.remove(packed);
                return values.isEmpty() ? null : values;
            });
            dirtyDeletes.computeIfAbsent(world, ignored -> new HashSet<>()).add(packed);
        }
        markDirty();
    }

    private DirtyBatch drainDirtyBatch() {
        synchronized (dirtyLock) {
            if (dirtyUpserts.isEmpty() && dirtyDeletes.isEmpty()) {
                return DirtyBatch.empty();
            }
            Map<String, Set<Long>> upserts = copyDirtyMap(dirtyUpserts);
            Map<String, Set<Long>> deletes = copyDirtyMap(dirtyDeletes);
            dirtyUpserts.clear();
            dirtyDeletes.clear();
            return new DirtyBatch(upserts, deletes);
        }
    }

    private void requeueDirtyBatch(DirtyBatch batch) {
        if (batch.isEmpty()) {
            return;
        }
        synchronized (dirtyLock) {
            for (Map.Entry<String, Set<Long>> entry : batch.upserts().entrySet()) {
                String world = entry.getKey();
                for (Long packed : entry.getValue()) {
                    Set<Long> newerDeletes = dirtyDeletes.get(world);
                    if (newerDeletes != null && newerDeletes.contains(packed)) {
                        continue;
                    }
                    dirtyUpserts.computeIfAbsent(world, ignored -> new HashSet<>()).add(packed);
                }
            }
            for (Map.Entry<String, Set<Long>> entry : batch.deletes().entrySet()) {
                String world = entry.getKey();
                for (Long packed : entry.getValue()) {
                    Set<Long> newerUpserts = dirtyUpserts.get(world);
                    if (newerUpserts != null && newerUpserts.contains(packed)) {
                        continue;
                    }
                    dirtyDeletes.computeIfAbsent(world, ignored -> new HashSet<>()).add(packed);
                }
            }
        }
    }

    private void clearDirtyChanges() {
        synchronized (dirtyLock) {
            dirtyUpserts.clear();
            dirtyDeletes.clear();
        }
    }

    private Map<String, Set<Long>> copyDirtyMap(Map<String, Set<Long>> source) {
        Map<String, Set<Long>> copy = new HashMap<>(source.size());
        for (Map.Entry<String, Set<Long>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return copy;
    }

    private void registerCommandExecutor() {
        PluginCommand epCommand = getCommand("ep");
        if (epCommand == null) {
            getLogger().warning(msg("command_missing",
                    "[ExplosionProtector] Command 'ep' is not defined in plugin.yml."));
            return;
        }
        ExplosionProtectorCommand command = new ExplosionProtectorCommand(this);
        epCommand.setExecutor(command);
        epCommand.setTabCompleter(command);
    }

    private CoreProtectAPI fetchCoreProtectAPI() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (!(plugin instanceof CoreProtect cp)) {
            return null;
        }
        CoreProtectAPI api = cp.getAPI();
        if (api == null || api.APIVersion() < 7) {
            return null;
        }
        return api;
    }

    private boolean isWorldEnabled(String worldName) {
        return settingsService.isWorldEnabled(worldName, settings);
    }

    private boolean isProtectedHanging(Entity entity) {
        EntityType type = entity.getType();
        if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME) {
            return settings.protectItemFrames();
        }
        if (type == EntityType.PAINTING) {
            return settings.protectPaintings();
        }
        return false;
    }

    private void debug(String message) {
        if (settings != null && settings.debugEnabled()) {
            getLogger().info("[ExplosionProtector][Debug] " + message);
        }
    }

    private void debugRateLimited(String category, long cooldownMs, Supplier<String> messageSupplier) {
        if (settings == null || !settings.debugEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = debugRateLimitAt.get(category);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        debugRateLimitAt.put(category, now);
        debug(messageSupplier.get());
    }

    private void warnRateLimited(String category, long cooldownMs, String message) {
        long now = System.currentTimeMillis();
        Long last = warnRateLimitAt.get(category);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        warnRateLimitAt.put(category, now);
        getLogger().warning(message);
    }

    private void warnUnsupportedCoordinates(Block block, String reason) {
        warnRateLimited("pack-out-of-range", settings.outOfRangePackWarnCooldownMs(),
                "[ExplosionProtector] Skip tracking due to unsupported coordinates in world '"
                        + block.getWorld().getName() + "' at "
                        + block.getX() + "," + block.getY() + "," + block.getZ() + " (" + reason + ")");
    }

    private String percent(long numerator, long denominator) {
        if (denominator <= 0L) {
            return "0.00";
        }
        return String.format(Locale.ROOT, "%.2f", (numerator * 100.0) / denominator);
    }

    private String formatMillis(long startedNanos) {
        return String.format(Locale.ROOT, "%.2f", (System.nanoTime() - startedNanos) / 1_000_000.0);
    }

    private String[] parseLegacyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        int split = key.indexOf(':');
        if (split <= 0 || split >= key.length() - 1) {
            return null;
        }
        String world = key.substring(0, split);
        String[] coords = key.substring(split + 1).split(",", 3);
        if (coords.length != 3) {
            return null;
        }
        return new String[]{world, coords[0], coords[1], coords[2]};
    }

    private String decodeWorldKey(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return encoded;
        }
    }

    private synchronized void reloadRuntime() {
        applyQueuedLookupResolutions(Integer.MAX_VALUE);
        PluginSettings previous = settings;

        reloadConfig();
        settings = settingsService.loadFromConfig();
        pluginActive = settings.pluginActive();

        placementCacheService.rebuild(settings);
        placementCacheService.clear();

        trackerService.setMaxTrackedBlocks(settings.maxTrackedBlocks());

        openStorageIfNeeded();
        if (!settings.enableLocalTracker()) {
            trackerService.clear();
            resetDirtyState();
        } else if (!previous.enableLocalTracker() && settings.persistPlayerPlacedBlocks()) {
            loadTrackedBlocksFromStorage();
        }

        coreProtect = fetchCoreProtectAPI();
        if (coreProtect == null && settings.requireCoreProtect()) {
            getLogger().warning(msg("coreprotect_not_found",
                    "[ExplosionProtector] CoreProtect not found or API incompatible."));
        }

        coreProtectQueueService.applySettings(settings, coreProtect);
        coreProtectQueueService.start();
        startResolvedApplyTask();

        if (settings.cleanupUnloadedWorldsOnStart()) {
            cleanupEntriesFromUnloadedWorlds();
        }
        startAutosaveTask();
    }

    @Override
    public String msg(String key, String def) {
        return settingsService.msg(key, def);
    }

    @Override
    public StatusView status() {
        boolean coreProtectReady = coreProtect != null && coreProtect.APIVersion() >= 7;
        boolean enabled = settings.protectPlayerPlacedBlocks()
                && (settings.enableLocalTracker() || coreProtectReady || settings.protectFirstUnknown());
        long queueProcessed = coreProtectQueueService == null ? 0L : coreProtectQueueService.totalProcessed();
        long queueQueued = coreProtectQueueService == null ? 0L : coreProtectQueueService.totalQueued();
        long queueFailed = coreProtectQueueService == null ? 0L : coreProtectQueueService.totalFailed();
        long queueDropped = coreProtectQueueService == null ? 0L : coreProtectQueueService.totalDropped();
        long queueShortCircuited = coreProtectQueueService == null ? 0L : coreProtectQueueService.totalShortCircuited();
        long queueCircuitOpenEvents = coreProtectQueueService == null ? 0L : coreProtectQueueService.totalCircuitOpenEvents();
        boolean circuitOpen = coreProtectQueueService != null && coreProtectQueueService.isCircuitOpen();
        long circuitRemainingMs = coreProtectQueueService == null ? 0L : coreProtectQueueService.circuitOpenRemainingMs();
        int effectiveBatchSize = coreProtectQueueService == null ? 0 : coreProtectQueueService.effectiveBatchSize();
        int drainIntervalTicks = coreProtectQueueService == null ? 1 : coreProtectQueueService.drainIntervalTicks();
        long totalCoreProtectLookups = queueProcessed + queueFailed;
        return new StatusView(
                enabled,
                coreProtectReady,
                lastProtectedCount,
                pluginActive,
                settings.enableLocalTracker(),
                trackerService.size(),
                settings.maxTrackedBlocks(),
                totalExplosions,
                totalProtectedBlocks,
                totalPlacementChecks,
                totalLocalHits,
                totalCacheHits,
                totalCoreProtectLookups,
                queueQueued,
                queueProcessed,
                queueFailed,
                queueDropped,
                queueShortCircuited,
                queueCircuitOpenEvents,
                circuitOpen,
                circuitRemainingMs,
                effectiveBatchSize,
                drainIntervalTicks,
                percent(totalLocalHits + totalCacheHits, totalPlacementChecks)
        );
    }

    @Override
    public void setLanguage(String languageCode) {
        getConfig().set("language", languageCode);
        saveConfig();
        settingsService.loadLanguageMessages(languageCode);
    }

    @Override
    public void reload() {
        reloadRuntime();
    }

    @Override
    public boolean toggle() {
        pluginActive = !pluginActive;
        getConfig().set("plugin-active", pluginActive);
        saveConfig();
        return pluginActive;
    }

    @Override
    public void saveNow() {
        saveTrackedBlocksIfNeeded();
    }

    @Override
    public void clearCache() {
        placementCacheService.clear();
    }

    private record ResolvedLookup(BlockKey key, boolean placed) {
    }

    private record DirtyBatch(Map<String, Set<Long>> upserts, Map<String, Set<Long>> deletes) {
        private static DirtyBatch empty() {
            return new DirtyBatch(Map.of(), Map.of());
        }

        private boolean isEmpty() {
            return upserts.isEmpty() && deletes.isEmpty();
        }
    }
}
