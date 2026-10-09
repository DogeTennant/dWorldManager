package com.dogetennant.dworldmanager;

import com.dogetennant.dworldmanager.config.ConfigManager;
import com.dogetennant.dworldmanager.database.DatabaseManager;
import com.dogetennant.dworldmanager.database.DatabaseQueue;
import org.bukkit.Server;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test doubles: a mocked plugin whose scheduler runs everything at once (a repeating task until it
 * cancels itself), and firing an event at a listener the way the server does.
 */
public final class Fakes {

    private Fakes() {
    }

    /**
     * A plugin with a mocked config (messages fall back to their defaults) and database, and a
     * database queue that runs everything at once.
     */
    public static DWorldManager plugin() {
        DWorldManager plugin = mock(DWorldManager.class);
        ConfigManager config = mock(ConfigManager.class);
        when(config.getMessage(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(config.getScanTickBudgetMillis()).thenReturn(1_000L);
        when(config.getMaxScanBorderSize()).thenReturn(20_000.0);
        when(config.getMaxRegionVolume()).thenReturn(2_000_000L);
        when(plugin.getConfigManager()).thenReturn(config);
        when(plugin.getDatabaseManager()).thenReturn(mock(DatabaseManager.class));
        when(plugin.getDatabaseQueue()).thenReturn(DatabaseQueue.immediate(Logger.getLogger("dWorldManager-test")));
        when(plugin.getLogger()).thenReturn(Logger.getLogger("dWorldManager-test"));
        when(plugin.getName()).thenReturn("dWorldManager");

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(call -> {
            Consumer<BukkitTask> body = call.getArgument(1);
            RepeatingTask task = new RepeatingTask();
            for (int tick = 0; !task.cancelled; tick++) {
                if (tick > 100_000) throw new IllegalStateException("repeating task never finished");
                body.accept(task);
            }
            return null;
        }).when(scheduler).runTaskTimer(any(Plugin.class), any(Consumer.class), anyLong(), anyLong());
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        });
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        });
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);
        return plugin;
    }

    /**
     * Calls every {@link EventHandler} of {@code listener} that takes this kind of event, skipping
     * {@code ignoreCancelled} handlers once the event is cancelled - like the server does.
     */
    public static void fire(Listener listener, Event event) {
        for (Method method : listener.getClass().getMethods()) {
            EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler == null || method.getParameterCount() != 1) continue;
            if (!method.getParameterTypes()[0].isAssignableFrom(event.getClass())) continue;
            if (handler.ignoreCancelled() && event instanceof Cancellable c && c.isCancelled()) continue;
            try {
                method.invoke(listener, event);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class RepeatingTask implements BukkitTask {
        boolean cancelled;

        @Override public int getTaskId() { return 1; }
        @Override public Plugin getOwner() { return null; }
        @Override public boolean isSync() { return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void cancel() { cancelled = true; }
    }
}
