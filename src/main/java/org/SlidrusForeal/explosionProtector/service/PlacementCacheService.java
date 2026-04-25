package org.SlidrusForeal.explosionProtector.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.SlidrusForeal.explosionProtector.model.BlockKey;

import java.util.concurrent.TimeUnit;

public class PlacementCacheService {
    private Cache<BlockKey, Boolean> positiveCache = CacheBuilder.newBuilder().build();
    private Cache<BlockKey, Boolean> negativeCache = CacheBuilder.newBuilder().build();

    public synchronized void rebuild(PluginSettings settings) {
        positiveCache = CacheBuilder.newBuilder()
                .expireAfterWrite(settings.cacheExpireSeconds(), TimeUnit.SECONDS)
                .maximumSize(settings.cacheMaxSize())
                .build();
        negativeCache = CacheBuilder.newBuilder()
                .expireAfterWrite(settings.cacheNegativeExpireSeconds(), TimeUnit.SECONDS)
                .maximumSize(settings.cacheNegativeMaxSize())
                .build();
    }

    public Boolean get(BlockKey key) {
        if (positiveCache.getIfPresent(key) != null) {
            return Boolean.TRUE;
        }
        if (negativeCache.getIfPresent(key) != null) {
            return Boolean.FALSE;
        }
        return null;
    }

    public void put(BlockKey key, boolean placed) {
        if (placed) {
            positiveCache.put(key, Boolean.TRUE);
            negativeCache.invalidate(key);
            return;
        }
        negativeCache.put(key, Boolean.FALSE);
        positiveCache.invalidate(key);
    }

    public void invalidate(BlockKey key) {
        positiveCache.invalidate(key);
        negativeCache.invalidate(key);
    }

    public void clear() {
        positiveCache.invalidateAll();
        negativeCache.invalidateAll();
    }
}
