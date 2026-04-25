package org.SlidrusForeal.explosionProtector.service;

import net.coreprotect.CoreProtectAPI;
import net.coreprotect.CoreProtectAPI.ParseResult;
import org.SlidrusForeal.explosionProtector.model.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class CoreProtectLookupQueueService {
    private final JavaPlugin plugin;
    private final ConcurrentLinkedQueue<LookupRequest> queue = new ConcurrentLinkedQueue<>();
    private final Set<BlockKey> queuedKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, World> worldProxyCache = new ConcurrentHashMap<>();
    private final AtomicLong totalQueued = new AtomicLong(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalShortCircuited = new AtomicLong(0);
    private final AtomicLong totalCircuitOpenEvents = new AtomicLong(0);
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicBoolean drainRunning = new AtomicBoolean(false);
    private final AtomicInteger workerIndex = new AtomicInteger(0);
    private final AtomicInteger effectiveBatchSize = new AtomicInteger(64);
    private final AtomicInteger drainIntervalTicks = new AtomicInteger(1);
    private final AtomicInteger drainTickCounter = new AtomicInteger(0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    private final Object executorLock = new Object();
    private final BiConsumer<BlockKey, Boolean> onResolved;
    private final Consumer<String> debug;

    private volatile CoreProtectAPI coreProtect;
    private volatile int lookupHistoryLimit = 10;
    private volatile int baseBatchSize = 64;
    private volatile int queueMaxSize = 10_000;
    private volatile int lookupWorkerThreads = 2;
    private volatile long lookupTimeoutMs = 2000L;
    private volatile int healthMinBatchSize = 8;
    private volatile int maxDrainIntervalTicks = 4;
    private volatile int healthRecoverySuccesses = 20;
    private volatile int circuitFailureThreshold = 8;
    private volatile long circuitOpenMs = 45_000L;
    private volatile long circuitOpenUntilMs = 0L;
    private volatile ThreadPoolExecutor lookupExecutor;
    private int asyncTaskId = -1;

    public CoreProtectLookupQueueService(JavaPlugin plugin,
                                         BiConsumer<BlockKey, Boolean> onResolved,
                                         Consumer<String> debug) {
        this.plugin = plugin;
        this.onResolved = onResolved;
        this.debug = debug;
    }

    public void applySettings(PluginSettings settings, CoreProtectAPI api) {
        this.lookupHistoryLimit = settings.lookupHistoryLimit();
        this.baseBatchSize = settings.coreProtectAsyncBatchSize();
        this.queueMaxSize = settings.coreProtectAsyncQueueMaxSize();
        this.lookupWorkerThreads = settings.coreProtectAsyncWorkerThreads();
        this.lookupTimeoutMs = settings.coreProtectLookupTimeoutMs();
        this.healthMinBatchSize = Math.max(1, Math.min(settings.coreProtectHealthMinBatchSize(), baseBatchSize));
        this.maxDrainIntervalTicks = Math.max(1, settings.coreProtectHealthMaxDrainIntervalTicks());
        this.healthRecoverySuccesses = Math.max(1, settings.coreProtectHealthRecoverySuccesses());
        this.circuitFailureThreshold = Math.max(1, settings.coreProtectCircuitFailureThreshold());
        this.circuitOpenMs = Math.max(1000L, settings.coreProtectCircuitOpenMs());
        this.coreProtect = api;

        effectiveBatchSize.updateAndGet(current -> {
            if (current <= 0) {
                return baseBatchSize;
            }
            if (current > baseBatchSize) {
                return baseBatchSize;
            }
            return Math.max(current, healthMinBatchSize);
        });
        drainIntervalTicks.updateAndGet(current -> clamp(current, 1, maxDrainIntervalTicks));

        resizeLookupExecutorIfNeeded();
    }

    public boolean isAvailable() {
        return coreProtect != null;
    }

    public boolean enqueue(BlockKey key, int x, int y, int z) {
        if (coreProtect == null) {
            return false;
        }
        if (isCircuitOpenNow()) {
            totalShortCircuited.incrementAndGet();
            totalDropped.incrementAndGet();
            return false;
        }
        if (!queuedKeys.add(key)) {
            return false;
        }

        int newQueueSize = queueSize.incrementAndGet();
        if (newQueueSize > queueMaxSize) {
            queueSize.decrementAndGet();
            queuedKeys.remove(key);
            totalDropped.incrementAndGet();
            return false;
        }

        queue.add(new LookupRequest(key, x, y, z));
        totalQueued.incrementAndGet();
        return true;
    }

    public void start() {
        stop();
        resetHealthState();
        ensureLookupExecutor();
        asyncTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::drainQueue, 1L, 1L).getTaskId();
    }

    public void stop() {
        if (asyncTaskId != -1) {
            Bukkit.getScheduler().cancelTask(asyncTaskId);
            asyncTaskId = -1;
        }
        queue.clear();
        queuedKeys.clear();
        worldProxyCache.clear();
        queueSize.set(0);
        drainRunning.set(false);
        resetHealthState();
        shutdownLookupExecutor();
    }

    public long totalQueued() {
        return totalQueued.get();
    }

    public long totalProcessed() {
        return totalProcessed.get();
    }

    public long totalDropped() {
        return totalDropped.get();
    }

    public long totalFailed() {
        return totalFailed.get();
    }

    public long totalShortCircuited() {
        return totalShortCircuited.get();
    }

    public long totalCircuitOpenEvents() {
        return totalCircuitOpenEvents.get();
    }

    public int effectiveBatchSize() {
        return effectiveBatchSize.get();
    }

    public int drainIntervalTicks() {
        return drainIntervalTicks.get();
    }

    public boolean isCircuitOpen() {
        return isCircuitOpenNow();
    }

    public long circuitOpenRemainingMs() {
        long until = circuitOpenUntilMs;
        if (until <= 0L) {
            return 0L;
        }
        long remaining = until - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    private void drainQueue() {
        if (!drainRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!shouldDrainThisTick() || isCircuitOpenNow()) {
                return;
            }

            CoreProtectAPI api = this.coreProtect;
            if (api == null) {
                return;
            }

            ensureLookupExecutor();
            ThreadPoolExecutor executor = this.lookupExecutor;
            if (executor == null || executor.isShutdown()) {
                return;
            }

            int currentBatchSize = Math.max(1, effectiveBatchSize.get());
            List<LookupRequest> requests = pollBatch(currentBatchSize);
            if (requests.isEmpty()) {
                return;
            }

            ExecutorCompletionService<LookupResult> completion = new ExecutorCompletionService<>(executor);
            Map<Future<LookupResult>, PendingLookup> pending = new HashMap<>(requests.size());
            for (LookupRequest req : requests) {
                submitLookupTask(api, req, completion, pending);
            }
            if (pending.isEmpty()) {
                return;
            }

            long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(lookupTimeoutMs);
            while (!pending.isEmpty()) {
                Future<LookupResult> completed;
                try {
                    completed = completion.poll(25L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    cancelPendingLookups(pending, "interrupted");
                    Thread.currentThread().interrupt();
                    return;
                }
                if (completed != null) {
                    handleCompletedFuture(completed, pending);
                }
                expireTimedOutLookups(pending, timeoutNanos);
                if (isCircuitOpenNow() && !pending.isEmpty()) {
                    cancelPendingLookups(pending, "circuit-open");
                    return;
                }
            }
        } finally {
            drainRunning.set(false);
        }
    }

    private List<LookupRequest> pollBatch(int limit) {
        List<LookupRequest> batch = new ArrayList<>(limit);
        while (batch.size() < limit) {
            LookupRequest req = queue.poll();
            if (req == null) {
                break;
            }
            queuedKeys.remove(req.key());
            queueSize.updateAndGet(value -> Math.max(0, value - 1));
            batch.add(req);
        }
        return batch;
    }

    private void submitLookupTask(CoreProtectAPI api,
                                  LookupRequest req,
                                  ExecutorCompletionService<LookupResult> completion,
                                  Map<Future<LookupResult>, PendingLookup> pending) {
        try {
            Future<LookupResult> future = completion.submit(() -> new LookupResult(req, performLookup(api, req)));
            pending.put(future, new PendingLookup(req, System.nanoTime()));
        } catch (Exception e) {
            registerFailure(req, "rejected: " + e.getMessage());
        }
    }

    private void handleCompletedFuture(Future<LookupResult> future, Map<Future<LookupResult>, PendingLookup> pending) {
        PendingLookup pendingLookup = pending.remove(future);
        if (pendingLookup == null) {
            return;
        }
        try {
            LookupResult result = future.get();
            registerSuccess(result.request(), result.placed());
        } catch (CancellationException e) {
            registerFailure(pendingLookup.request(), "cancelled");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause != null && cause.getMessage() != null ? cause.getMessage() : e.getMessage();
            registerFailure(pendingLookup.request(), "failed: " + message);
        } catch (InterruptedException e) {
            future.cancel(true);
            registerFailure(pendingLookup.request(), "interrupted");
            Thread.currentThread().interrupt();
        }
    }

    private void expireTimedOutLookups(Map<Future<LookupResult>, PendingLookup> pending, long timeoutNanos) {
        long now = System.nanoTime();
        Iterator<Map.Entry<Future<LookupResult>, PendingLookup>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Future<LookupResult>, PendingLookup> entry = it.next();
            if (now - entry.getValue().startedAtNanos() < timeoutNanos) {
                continue;
            }
            entry.getKey().cancel(true);
            LookupRequest req = entry.getValue().request();
            it.remove();
            registerFailure(req, "timeout after " + lookupTimeoutMs + "ms");
        }
    }

    private void cancelPendingLookups(Map<Future<LookupResult>, PendingLookup> pending, String reason) {
        for (Map.Entry<Future<LookupResult>, PendingLookup> entry : pending.entrySet()) {
            entry.getKey().cancel(true);
            registerFailure(entry.getValue().request(), reason);
        }
        pending.clear();
    }

    private void registerSuccess(LookupRequest req, boolean placed) {
        onResolved.accept(req.key(), placed);
        totalProcessed.incrementAndGet();

        consecutiveFailures.set(0);
        int successes = consecutiveSuccesses.incrementAndGet();
        if (successes >= healthRecoverySuccesses && successes % healthRecoverySuccesses == 0) {
            effectiveBatchSize.updateAndGet(current -> Math.min(baseBatchSize, current + 1));
            drainIntervalTicks.updateAndGet(current -> Math.max(1, current - 1));
        }
    }

    private void registerFailure(LookupRequest req, String reason) {
        totalFailed.incrementAndGet();
        consecutiveSuccesses.set(0);

        int failures = consecutiveFailures.incrementAndGet();
        effectiveBatchSize.updateAndGet(current -> Math.max(healthMinBatchSize, Math.max(1, current / 2)));
        drainIntervalTicks.updateAndGet(current -> Math.min(maxDrainIntervalTicks, current + 1));

        if (failures >= circuitFailureThreshold) {
            openCircuit();
        }
        debug.accept("coreprotect lookup " + reason + " for " + req.key());
    }

    private void openCircuit() {
        long openDuration = Math.max(1000L, circuitOpenMs);
        long until = System.currentTimeMillis() + openDuration;
        if (until > circuitOpenUntilMs) {
            circuitOpenUntilMs = until;
            totalCircuitOpenEvents.incrementAndGet();
            debug.accept("coreprotect circuit opened for " + openDuration + "ms");
        }
        int droppedByCircuit = queueSize.getAndSet(0);
        if (droppedByCircuit > 0) {
            queue.clear();
            queuedKeys.clear();
            totalDropped.addAndGet(droppedByCircuit);
        }
        consecutiveFailures.set(0);
    }

    private boolean isCircuitOpenNow() {
        long until = circuitOpenUntilMs;
        if (until <= 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < until) {
            return true;
        }
        circuitOpenUntilMs = 0L;
        consecutiveFailures.set(0);
        return false;
    }

    private boolean shouldDrainThisTick() {
        int interval = Math.max(1, drainIntervalTicks.get());
        if (interval == 1) {
            return true;
        }
        int tick = drainTickCounter.incrementAndGet();
        return tick % interval == 0;
    }

    private boolean performLookup(CoreProtectAPI api, LookupRequest req) {
        Block block = createLookupBlock(req);
        List<String[]> lookup = api.blockLookup(block, lookupHistoryLimit);
        if (lookup == null) {
            return false;
        }

        for (int i = lookup.size() - 1; i >= 0; i--) {
            ParseResult result = api.parseResult(lookup.get(i));
            if (result == null) {
                continue;
            }
            if (result.getActionId() == 1) {
                String player = result.getPlayer();
                if (player != null && !player.startsWith("#")) {
                    return true;
                }
            }
        }
        return false;
    }

    private Block createLookupBlock(LookupRequest req) {
        String worldName = req.key().world();
        World worldProxy = worldProxyCache.computeIfAbsent(worldName, this::createWorldProxy);
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getX" -> req.x();
                    case "getY" -> req.y();
                    case "getZ" -> req.z();
                    case "getWorld" -> worldProxy;
                    case "getLocation" -> buildLocation(worldProxy, req, args);
                    case "toString" -> "CoreProtectLookupBlock{world="
                            + worldName + ",x=" + req.x() + ",y=" + req.y() + ",z=" + req.z() + "}";
                    case "hashCode" -> 31 * (31 * (31 * worldName.hashCode() + req.x()) + req.y()) + req.z();
                    case "equals" -> proxy == (args != null && args.length == 1 ? args[0] : null);
                    default -> throw unsupportedMethod("Block", method.getName());
                }
        );
    }

    private Object buildLocation(World worldProxy, LookupRequest req, Object[] args) {
        if (args == null || args.length == 0) {
            return new Location(worldProxy, req.x(), req.y(), req.z());
        }
        if (args.length == 1 && args[0] instanceof Location location) {
            location.setWorld(worldProxy);
            location.setX(req.x());
            location.setY(req.y());
            location.setZ(req.z());
            return location;
        }
        throw unsupportedMethod("Block", "getLocation");
    }

    private World createWorldProxy(String worldName) {
        UUID worldUuid = UUID.nameUUIDFromBytes(("explosionprotector:" + worldName).getBytes(StandardCharsets.UTF_8));
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> worldName;
                    case "getUID" -> worldUuid;
                    case "toString" -> "CoreProtectLookupWorld{name=" + worldName + "}";
                    case "hashCode" -> worldName.hashCode();
                    case "equals" -> proxy == (args != null && args.length == 1 ? args[0] : null);
                    default -> throw unsupportedMethod("World", method.getName());
                }
        );
    }

    private UnsupportedOperationException unsupportedMethod(String type, String method) {
        return new UnsupportedOperationException(type + " proxy method is not supported: " + method);
    }

    private void ensureLookupExecutor() {
        synchronized (executorLock) {
            if (lookupExecutor != null && !lookupExecutor.isShutdown()) {
                return;
            }
            lookupExecutor = createLookupExecutor();
        }
    }

    private void resizeLookupExecutorIfNeeded() {
        synchronized (executorLock) {
            if (lookupExecutor == null || lookupExecutor.isShutdown()) {
                lookupExecutor = createLookupExecutor();
                return;
            }
            if (lookupExecutor.getMaximumPoolSize() == lookupWorkerThreads
                    && lookupExecutor.getCorePoolSize() == lookupWorkerThreads) {
                return;
            }
            lookupExecutor.shutdownNow();
            lookupExecutor = createLookupExecutor();
        }
    }

    private void shutdownLookupExecutor() {
        synchronized (executorLock) {
            if (lookupExecutor == null) {
                return;
            }
            lookupExecutor.shutdownNow();
            lookupExecutor = null;
        }
    }

    private ThreadPoolExecutor createLookupExecutor() {
        int workers = Math.max(1, lookupWorkerThreads);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers,
                workers,
                30L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "ExplosionProtector-CoreProtectLookup-" + workerIndex.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private void resetHealthState() {
        effectiveBatchSize.set(Math.max(1, baseBatchSize));
        drainIntervalTicks.set(1);
        drainTickCounter.set(0);
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);
        circuitOpenUntilMs = 0L;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private record LookupRequest(BlockKey key, int x, int y, int z) {
    }

    private record PendingLookup(LookupRequest request, long startedAtNanos) {
    }

    private record LookupResult(LookupRequest request, boolean placed) {
    }
}
