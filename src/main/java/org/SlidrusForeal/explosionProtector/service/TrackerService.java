package org.SlidrusForeal.explosionProtector.service;

import org.SlidrusForeal.explosionProtector.model.BlockKey;
import org.SlidrusForeal.explosionProtector.model.PackedBlockUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class TrackerService {
    private final Map<String, Set<Long>> trackedBlocksByWorld = new HashMap<>();
    private final Map<String, Map<Long, Set<Long>>> trackedChunksByWorld = new HashMap<>();
    private final LinkedHashMap<BlockKey, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);
    private int maxTrackedBlocks = 500_000;

    public synchronized void setMaxTrackedBlocks(int maxTrackedBlocks) {
        this.maxTrackedBlocks = Math.max(1_000, maxTrackedBlocks);
        evictOverflow(null);
    }

    public synchronized int size() {
        return lru.size();
    }

    public synchronized void clear() {
        trackedBlocksByWorld.clear();
        trackedChunksByWorld.clear();
        lru.clear();
    }

    public synchronized boolean contains(String world, long packed) {
        Set<Long> worldSet = trackedBlocksByWorld.get(world);
        if (worldSet == null || !worldSet.contains(packed)) {
            return false;
        }
        BlockKey key = new BlockKey(world, packed);
        if (lru.containsKey(key)) {
            lru.get(key);
        } else {
            lru.put(key, Boolean.TRUE);
        }
        return true;
    }

    public synchronized boolean track(String world, long packed, Consumer<BlockKey> onEvicted) {
        if (world == null || world.isBlank()) {
            return false;
        }
        Set<Long> worldSet = trackedBlocksByWorld.computeIfAbsent(world, ignored -> new HashSet<>());
        BlockKey key = new BlockKey(world, packed);
        if (!worldSet.add(packed)) {
            lru.get(key);
            if (!lru.containsKey(key)) {
                lru.put(key, Boolean.TRUE);
            }
            return false;
        }

        long chunkKey = PackedBlockUtil.packChunkFromPacked(packed);
        trackedChunksByWorld
                .computeIfAbsent(world, ignored -> new HashMap<>())
                .computeIfAbsent(chunkKey, ignored -> new HashSet<>())
                .add(packed);
        lru.put(key, Boolean.TRUE);
        evictOverflow(onEvicted);
        return true;
    }

    public synchronized boolean untrack(String world, long packed) {
        return removeKeyInternal(new BlockKey(world, packed), true);
    }

    /**
     * Removes chunk entries from the in-memory tracker only.
     * Does not imply persistence deletes — caller decides whether to mark dirty.
     */
    public synchronized int removeChunk(String world, int chunkX, int chunkZ, Consumer<BlockKey> onRemoved) {
        Map<Long, Set<Long>> worldChunks = trackedChunksByWorld.get(world);
        if (worldChunks == null || worldChunks.isEmpty()) {
            return 0;
        }
        Set<Long> worldSet = trackedBlocksByWorld.get(world);
        if (worldSet == null || worldSet.isEmpty()) {
            trackedChunksByWorld.remove(world);
            return 0;
        }

        long chunkKey = PackedBlockUtil.packChunk(chunkX, chunkZ);
        Set<Long> chunkEntries = worldChunks.remove(chunkKey);
        if (chunkEntries == null || chunkEntries.isEmpty()) {
            return 0;
        }

        int removed = 0;
        for (Long packed : chunkEntries) {
            BlockKey key = new BlockKey(world, packed);
            if (removeKeyInternal(key, true)) {
                removed++;
                if (onRemoved != null) {
                    onRemoved.accept(key);
                }
            }
        }
        return removed;
    }

    public synchronized int loadChunk(String world, List<Long> packedBlocks) {
        if (world == null || world.isBlank() || packedBlocks == null || packedBlocks.isEmpty()) {
            return 0;
        }
        int loaded = 0;
        for (Long packed : packedBlocks) {
            if (packed == null) {
                continue;
            }
            if (track(world, packed, null)) {
                loaded++;
            }
        }
        return loaded;
    }

    public synchronized int removeWorld(String world, Consumer<BlockKey> onRemoved) {
        Set<Long> worldSet = trackedBlocksByWorld.remove(world);
        trackedChunksByWorld.remove(world);
        if (worldSet == null || worldSet.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (Long packed : worldSet) {
            BlockKey key = new BlockKey(world, packed);
            lru.remove(key);
            removed++;
            if (onRemoved != null) {
                onRemoved.accept(key);
            }
        }
        return removed;
    }

    public synchronized int cleanupUnloadedWorlds(Set<String> loadedWorlds, Consumer<BlockKey> onRemoved) {
        int removed = 0;
        for (String world : new ArrayList<>(trackedBlocksByWorld.keySet())) {
            if (loadedWorlds.contains(world)) {
                continue;
            }
            removed += removeWorld(world, onRemoved);
        }
        return removed;
    }

    public synchronized Map<String, List<Long>> snapshot() {
        Map<String, List<Long>> snapshot = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : trackedBlocksByWorld.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return snapshot;
    }

    public synchronized void loadSnapshot(Map<String, List<Long>> snapshot) {
        clear();
        for (Map.Entry<String, List<Long>> entry : snapshot.entrySet()) {
            for (Long packed : entry.getValue()) {
                track(entry.getKey(), packed, null);
            }
        }
    }

    private boolean removeKeyInternal(BlockKey key, boolean removeFromLru) {
        Set<Long> worldSet = trackedBlocksByWorld.get(key.world());
        if (worldSet == null || !worldSet.remove(key.packed())) {
            return false;
        }
        if (worldSet.isEmpty()) {
            trackedBlocksByWorld.remove(key.world());
        }

        Map<Long, Set<Long>> worldChunks = trackedChunksByWorld.get(key.world());
        if (worldChunks != null) {
            long chunkKey = PackedBlockUtil.packChunkFromPacked(key.packed());
            Set<Long> chunkEntries = worldChunks.get(chunkKey);
            if (chunkEntries != null) {
                chunkEntries.remove(key.packed());
                if (chunkEntries.isEmpty()) {
                    worldChunks.remove(chunkKey);
                }
            }
            if (worldChunks.isEmpty()) {
                trackedChunksByWorld.remove(key.world());
            }
        }

        if (removeFromLru) {
            lru.remove(key);
        }
        return true;
    }

    private void evictOverflow(Consumer<BlockKey> onEvicted) {
        if (lru.size() <= maxTrackedBlocks) {
            return;
        }

        Iterator<BlockKey> it = lru.keySet().iterator();
        while (lru.size() > maxTrackedBlocks && it.hasNext()) {
            BlockKey evicted = it.next();
            it.remove();
            removeKeyInternal(evicted, false);
            if (onEvicted != null) {
                onEvicted.accept(evicted);
            }
        }
    }
}
