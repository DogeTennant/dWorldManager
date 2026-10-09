package com.dogetennant.dworldmanager.database;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/** Database work off the main thread, in order, results back on the "main thread". */
class DatabaseQueueTest {

    private final Logger logger = Logger.getLogger("dWorldManager-test");
    private final ExecutorService main = Executors.newSingleThreadExecutor(task -> new Thread(task, "main"));

    @Test
    void writesRunInTheOrderTheyWereQueuedOffTheCallingThread() {
        DatabaseQueue queue = new DatabaseQueue(logger, main);
        List<String> done = Collections.synchronizedList(new ArrayList<>());
        List<String> threads = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 50; i++) {
            int n = i;
            queue.submit(() -> {
                done.add("write " + n);
                threads.add(Thread.currentThread().getName());
            });
        }
        assertThat(queue.close(10, TimeUnit.SECONDS)).isTrue();

        assertThat(done).hasSize(50).first().isEqualTo("write 0");
        assertThat(done).last().isEqualTo("write 49");
        assertThat(threads).containsOnly("dWorldManager-Database");
    }

    @Test
    void aQuerySeesEarlierWritesAndAnswersOnTheMainThread() throws Exception {
        DatabaseQueue queue = new DatabaseQueue(logger, main);
        List<String> table = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> answeredOn = new AtomicReference<>();
        AtomicReference<Integer> rows = new AtomicReference<>();
        CountDownLatch answered = new CountDownLatch(1);

        queue.submit(() -> table.add("frozen block"));
        queue.query(table::size, count -> {
            rows.set(count);
            answeredOn.set(Thread.currentThread().getName());
            answered.countDown();
        });

        assertThat(answered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(rows.get()).isEqualTo(1);
        assertThat(answeredOn.get()).isEqualTo("main");
        queue.close(10, TimeUnit.SECONDS);
    }

    @Test
    void aFailingWriteDoesNotStopTheNextOne() {
        DatabaseQueue queue = new DatabaseQueue(logger, main);
        List<String> done = Collections.synchronizedList(new ArrayList<>());

        queue.submit(() -> {
            throw new IllegalStateException("broken write");
        });
        queue.submit(() -> done.add("next"));
        queue.close(10, TimeUnit.SECONDS);

        assertThat(done).containsExactly("next");
    }

    @Test
    void workAfterCloseRunsRightAway() {
        DatabaseQueue queue = new DatabaseQueue(logger, Runnable::run);
        queue.close(10, TimeUnit.SECONDS);
        List<String> done = new ArrayList<>();

        queue.submit(() -> done.add("late write"));
        queue.query(() -> "late read", done::add);

        assertThat(done).containsExactly("late write", "late read");
    }
}
