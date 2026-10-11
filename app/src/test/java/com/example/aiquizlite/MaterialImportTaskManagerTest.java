package com.example.aiquizlite;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MaterialImportTaskManagerTest {
    private ExecutorService worker;
    private MaterialImportTaskManager manager;

    @Before
    public void setUp() {
        worker = Executors.newSingleThreadExecutor();
        manager = new MaterialImportTaskManager(worker, Runnable::run);
    }

    @After
    public void tearDown() {
        manager.shutdownForTests();
    }

    @Test
    public void detachingUiDoesNotCancelBackgroundTaskAndReattachGetsResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<MaterialImportTaskManager.Status> firstScreenEvents = new ArrayList<>();
        MaterialImportTaskManager.Listener firstScreen = snapshot ->
                firstScreenEvents.add(snapshot.getStatus());
        manager.setListener(firstScreen);

        manager.start(
                MaterialImportTaskManager.TaskKind.QUESTION_BANK_CONVERSION,
                "转换",
                "处理中",
                1,
                progress -> {
                    started.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return MaterialImportTaskManager.TaskResult.emptyForTests();
                }
        );
        assertTrue(started.await(2, TimeUnit.SECONDS));
        manager.clearListener(firstScreen);
        int eventCountWhenScreenLeft = firstScreenEvents.size();
        release.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (manager.getSnapshot().getStatus() == MaterialImportTaskManager.Status.RUNNING
                && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }

        assertEquals(
                MaterialImportTaskManager.Status.SUCCEEDED,
                manager.getSnapshot().getStatus()
        );
        assertEquals(eventCountWhenScreenLeft, firstScreenEvents.size());

        AtomicReference<MaterialImportTaskManager.Snapshot> resumed = new AtomicReference<>();
        manager.setListener(resumed::set);
        assertNotNull(resumed.get());
        assertEquals(MaterialImportTaskManager.Status.SUCCEEDED, resumed.get().getStatus());
        assertNotNull(manager.consumeResult(resumed.get().getTaskId()));
    }
}
