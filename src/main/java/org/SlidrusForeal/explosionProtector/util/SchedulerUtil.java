package org.SlidrusForeal.explosionProtector.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Scheduler facade that prefers Paper/Folia region schedulers when present,
 * and falls back to BukkitScheduler. All Folia/Paper calls are reflective so
 * the same sources compile against 1.19+ APIs.
 */
public final class SchedulerUtil {
    private static final boolean PAPER_SCHEDULERS;
    private static final Method GET_GLOBAL_REGION_SCHEDULER;
    private static final Method GET_ASYNC_SCHEDULER;
    private static final Method GLOBAL_RUN_AT_FIXED_RATE;
    private static final Method GLOBAL_EXECUTE;
    private static final Method ASYNC_RUN_AT_FIXED_RATE;
    private static final Method ASYNC_RUN_NOW;
    private static final Method SCHEDULED_CANCEL;

    static {
        Method getGlobal = null;
        Method getAsync = null;
        Method globalFixed = null;
        Method globalExecute = null;
        Method asyncFixed = null;
        Method asyncNow = null;
        Method cancel = null;
        boolean available = false;
        try {
            Class<?> globalScheduler = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> asyncScheduler = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            Class<?> scheduledTask = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            Class<?> consumerClass = Consumer.class;

            getGlobal = Bukkit.class.getMethod("getGlobalRegionScheduler");
            getAsync = Bukkit.class.getMethod("getAsyncScheduler");
            globalFixed = globalScheduler.getMethod("runAtFixedRate", Plugin.class, consumerClass, long.class, long.class);
            globalExecute = globalScheduler.getMethod("execute", Plugin.class, Runnable.class);
            asyncFixed = asyncScheduler.getMethod(
                    "runAtFixedRate", Plugin.class, consumerClass, long.class, long.class, TimeUnit.class);
            asyncNow = asyncScheduler.getMethod("runNow", Plugin.class, consumerClass);
            cancel = scheduledTask.getMethod("cancel");
            available = true;
        } catch (ReflectiveOperationException ignored) {
            // Paper/Folia schedulers are unavailable on this runtime/API.
        }
        PAPER_SCHEDULERS = available;
        GET_GLOBAL_REGION_SCHEDULER = getGlobal;
        GET_ASYNC_SCHEDULER = getAsync;
        GLOBAL_RUN_AT_FIXED_RATE = globalFixed;
        GLOBAL_EXECUTE = globalExecute;
        ASYNC_RUN_AT_FIXED_RATE = asyncFixed;
        ASYNC_RUN_NOW = asyncNow;
        SCHEDULED_CANCEL = cancel;
    }

    private SchedulerUtil() {
    }

    public static int runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (PAPER_SCHEDULERS) {
            try {
                Object scheduler = GET_GLOBAL_REGION_SCHEDULER.invoke(null);
                Object scheduled = GLOBAL_RUN_AT_FIXED_RATE.invoke(
                        scheduler,
                        plugin,
                        (Consumer<Object>) ignored -> task.run(),
                        Math.max(1L, delayTicks),
                        Math.max(1L, periodTicks));
                int taskId = System.identityHashCode(scheduled);
                ScheduledTaskRegistry.register(taskId, () -> cancelScheduled(scheduled));
                return taskId;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule global timer", e);
            }
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks).getTaskId();
    }

    public static int runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (PAPER_SCHEDULERS) {
            try {
                long delayMs = Math.max(1L, delayTicks) * 50L;
                long periodMs = Math.max(1L, periodTicks) * 50L;
                Object scheduler = GET_ASYNC_SCHEDULER.invoke(null);
                Object scheduled = ASYNC_RUN_AT_FIXED_RATE.invoke(
                        scheduler,
                        plugin,
                        (Consumer<Object>) ignored -> task.run(),
                        delayMs,
                        periodMs,
                        TimeUnit.MILLISECONDS);
                int taskId = System.identityHashCode(scheduled);
                ScheduledTaskRegistry.register(taskId, () -> cancelScheduled(scheduled));
                return taskId;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule async timer", e);
            }
        }
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks).getTaskId();
    }

    public static void cancel(Plugin plugin, int taskId) {
        if (taskId == -1) {
            return;
        }
        if (PAPER_SCHEDULERS && ScheduledTaskRegistry.cancel(taskId)) {
            return;
        }
        Bukkit.getScheduler().cancelTask(taskId);
    }

    public static void runAsync(Plugin plugin, Runnable task) {
        if (PAPER_SCHEDULERS) {
            try {
                Object scheduler = GET_ASYNC_SCHEDULER.invoke(null);
                ASYNC_RUN_NOW.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run());
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to run async task", e);
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public static void runGlobal(Plugin plugin, Runnable task) {
        if (PAPER_SCHEDULERS) {
            try {
                Object scheduler = GET_GLOBAL_REGION_SCHEDULER.invoke(null);
                GLOBAL_EXECUTE.invoke(scheduler, plugin, task);
                return;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to run global task", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private static void cancelScheduled(Object scheduledTask) {
        try {
            SCHEDULED_CANCEL.invoke(scheduledTask);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to cancel scheduled task", e);
        }
    }

    private static final class ScheduledTaskRegistry {
        private static final java.util.concurrent.ConcurrentHashMap<Integer, Runnable> TASKS =
                new java.util.concurrent.ConcurrentHashMap<>();

        private ScheduledTaskRegistry() {
        }

        private static void register(int id, Runnable cancel) {
            TASKS.put(id, cancel);
        }

        private static boolean cancel(int id) {
            Runnable cancel = TASKS.remove(id);
            if (cancel == null) {
                return false;
            }
            cancel.run();
            return true;
        }
    }
}
