package com.dogetennant.dworldmanager.database;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs database work one task after another on a single background thread, in the order it was
 * submitted, so the main thread never waits for the database and a later write (an unfreeze) can
 * never overtake an earlier one (the freeze scan's last batch).
 *
 * {@link #query} hands the result back on the main thread. {@link #close} runs everything still
 * queued before the thread stops; work submitted after it runs right away on the calling thread.
 */
public final class DatabaseQueue {

    private final Executor worker;
    private final ExecutorService ownWorker;
    private final Executor mainThread;
    private final Logger logger;

    /** @param mainThread runs results on the server thread */
    public DatabaseQueue(Logger logger, Executor mainThread) {
        this(logger, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "dWorldManager-Database");
            thread.setDaemon(true);
            return thread;
        }), mainThread);
    }

    /** With {@code worker} as the database thread (tests: {@code Runnable::run}). */
    DatabaseQueue(Logger logger, Executor worker, Executor mainThread) {
        this.logger = logger;
        this.worker = worker;
        this.ownWorker = worker instanceof ExecutorService service ? service : null;
        this.mainThread = mainThread;
    }

    /** For tests in other packages: everything runs at once on the calling thread. */
    public static DatabaseQueue immediate(Logger logger) {
        return new DatabaseQueue(logger, Runnable::run, Runnable::run);
    }

    /** Queues a write. A failing write is logged and does not stop the ones after it. */
    public void submit(Runnable write) {
        execute(() -> {
            try {
                write.run();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "A database write failed.", e);
            }
        });
    }

    /**
     * Runs {@code work} after everything queued before it and gives its result to {@code then} on
     * the main thread. A failing query is logged and {@code then} is not called.
     */
    public <T> void query(Supplier<T> work, Consumer<T> then) {
        execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "A database query failed.", e);
                return;
            }
            mainThread.execute(() -> then.accept(result));
        });
    }

    private void execute(Runnable task) {
        if (ownWorker == null || !ownWorker.isShutdown()) {
            try {
                worker.execute(task);
                return;
            } catch (RejectedExecutionException ignored) {
                // closed in the meantime - run it here
            }
        }
        task.run();
    }

    /**
     * Takes no new work and waits until the queued work is done.
     *
     * @return {@code false} if it did not finish within the timeout
     */
    public boolean close(long timeout, TimeUnit unit) {
        if (ownWorker == null) return true;
        ownWorker.shutdown();
        try {
            return ownWorker.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
