package org.SlidrusForeal.explosionProtector.service;

import org.bukkit.Material;

import java.util.Set;

public record PluginSettings(
        long cacheExpireSeconds,
        long cacheNegativeExpireSeconds,
        long cacheMaxSize,
        long cacheNegativeMaxSize,
        int lookupHistoryLimit,
        int coreProtectAsyncBatchSize,
        int coreProtectAsyncQueueMaxSize,
        int coreProtectAsyncWorkerThreads,
        long coreProtectLookupTimeoutMs,
        int coreProtectResolvedApplyBatchSize,
        int coreProtectCircuitFailureThreshold,
        long coreProtectCircuitOpenMs,
        int coreProtectHealthMinBatchSize,
        int coreProtectHealthMaxDrainIntervalTicks,
        int coreProtectHealthRecoverySuccesses,
        boolean protectFirstUnknown,
        boolean protectPlayerPlacedBlocks,
        boolean protectHangingFromExplosions,
        boolean protectItemFrames,
        boolean protectPaintings,
        boolean blockEndermanGrief,
        boolean tntChainBreaksOnlyTnt,
        boolean persistPlayerPlacedBlocks,
        boolean enableLocalTracker,
        boolean requireCoreProtect,
        boolean pluginActive,
        boolean autosaveEnabled,
        int autosaveIntervalSeconds,
        int maxTrackedBlocks,
        boolean cleanupOnChunkUnload,
        boolean cleanupUnloadedWorldsOnStart,
        Set<String> enabledWorlds,
        Set<String> disabledWorlds,
        Set<Material> alwaysExplodeBlocks,
        boolean debugEnabled,
        long debugCacheLogCooldownMs,
        long outOfRangePackWarnCooldownMs
) {
    public boolean isAlwaysExplode(Material material) {
        return material != null && !alwaysExplodeBlocks.isEmpty() && alwaysExplodeBlocks.contains(material);
    }
}
