package kr.co.slip.execution;

import kr.co.slip.api.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BoundedSlipImageProcessorTest {
    private static final ProcessingOptions OPTIONS = ProcessingOptions.defaults();
    private static final byte[] INPUT = {1};
    private static final ProcessingResult RESULT = new ProcessingResult(new ProcessingResult.Report("TEST", 1, 1, 1, 1,
            List.of(), List.of(), List.of(), new ProcessingResult.Rotation("MANUAL", 0, 0, false, "TEST"), List.of()), Map.of());
    private static ExecutionSettings settings(int workers, int queue) { return new ExecutionSettings(workers, queue, Duration.ofSeconds(3)); }

    @Test void boundsConcurrencyQueueAndDeferredLoading() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), finish = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), loaded = new AtomicInteger();
        try (var processor = new BoundedSlipImageProcessor((input, options) -> {
            int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max); entered.countDown();
            try { assertTrue(finish.await(3, TimeUnit.SECONDS)); return RESULT; }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            finally { active.decrementAndGet(); }
        }, settings(2, 1))) {
            try {
                var first = processor.processAsync(INPUT, OPTIONS);
                var second = processor.processAsync(INPUT, OPTIONS);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var queued = processor.processAsync(() -> { loaded.incrementAndGet(); return INPUT; }, OPTIONS);
                assertEquals(0, loaded.get());
                assertEquals(1, processor.status().queued());
                assertThrows(ProcessingRejectedException.class, () -> processor.processAsync(INPUT, OPTIONS));
                assertTrue(queued.cancel(true));
                assertEquals(0, processor.status().queued());
                var replacement = processor.processAsync(INPUT, OPTIONS);
                finish.countDown();
                assertSame(RESULT, first.get(3, TimeUnit.SECONDS));
                assertSame(RESULT, second.get(3, TimeUnit.SECONDS));
                assertSame(RESULT, replacement.get(3, TimeUnit.SECONDS));
                assertEquals(2, peak.get());
                assertEquals(0, loaded.get());
                assertEquals(1, processor.status().rejected());
            } finally { finish.countDown(); }
        }
    }

    @Test void cancellingRunningWorkDoesNotFreeItsWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (var processor = new BoundedSlipImageProcessor((input, options) -> {
            entered.countDown();
            try { assertTrue(finish.await(3, TimeUnit.SECONDS)); return RESULT; }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        }, settings(1, 0))) {
            try {
                var future = processor.processAsync(INPUT, OPTIONS);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(future.cancel(true));
                assertThrows(ProcessingRejectedException.class, () -> processor.processAsync(INPUT, OPTIONS));
            } finally { finish.countDown(); }
        }
    }

    @Test void loaderAndEngineFailuresDoNotPoisonThePool() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var processor = new BoundedSlipImageProcessor((input, options) -> {
            if (calls.getAndIncrement() == 0) throw new IllegalArgumentException("bad image");
            return RESULT;
        }, settings(1, 2))) {
            var loaderFailure = processor.processAsync(() -> { throw new IOException("read failed"); }, OPTIONS);
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> loaderFailure.get(3, TimeUnit.SECONDS)).getCause());
            assertThrows(IllegalArgumentException.class, () -> processor.process(INPUT, OPTIONS));
            assertSame(RESULT, processor.process(INPUT, OPTIONS));
        }
    }

    @Test void gracefulCloseDrainsAcceptedWorkAndRejectsNewWork() throws Exception {
        var processor = new BoundedSlipImageProcessor((input, options) -> RESULT, settings(1, 2));
        var first = processor.processAsync(INPUT, OPTIONS);
        var second = processor.processAsync(INPUT, OPTIONS);
        processor.close();
        assertSame(RESULT, first.get()); assertSame(RESULT, second.get());
        assertTrue(processor.status().closed());
        assertThrows(ProcessingRejectedException.class, () -> processor.process(INPUT, OPTIONS));
        processor.close();
    }

    @Test void shutdownTimeoutFailsQueuedFuturesWithoutClaimingNativeWorkStopped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        var processor = new BoundedSlipImageProcessor((input, options) -> {
            entered.countDown();
            boolean done = false;
            while (!done) try { finish.await(); done = true; } catch (InterruptedException ignored) { /* simulate JNI */ }
            return RESULT;
        }, new ExecutionSettings(1, 1, Duration.ofMillis(30)));
        try {
            var running = processor.processAsync(INPUT, OPTIONS);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = processor.processAsync(INPUT, OPTIONS);
            assertThrows(IllegalStateException.class, processor::close);
            assertInstanceOf(ProcessingRejectedException.class, assertThrows(ExecutionException.class,
                    () -> queued.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(running.isDone());
            finish.countDown();
            assertSame(RESULT, running.get(3, TimeUnit.SECONDS));
        } finally { finish.countDown(); }
    }

    @Test void interruptionOfSynchronousCallerDoesNotReleaseRunningNativeCapacity() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var processor = new BoundedSlipImageProcessor((input, options) -> {
            entered.countDown();
            try { finish.await(); return RESULT; } catch (InterruptedException e) { throw new RuntimeException(e); }
        }, settings(1, 0))) {
            Thread caller = new Thread(() -> {
                try { processor.process(INPUT, OPTIONS); } catch (Throwable error) { failure.set(error); }
            });
            try {
                caller.start(); assertTrue(entered.await(3, TimeUnit.SECONDS));
                caller.interrupt(); caller.join(3000);
                assertFalse(caller.isAlive());
                assertInstanceOf(ProcessingInterruptedException.class, failure.get());
                assertThrows(ProcessingRejectedException.class, () -> processor.process(INPUT, OPTIONS));
            } finally { finish.countDown(); }
        }
    }

    @Test void recursiveSynchronousSubmissionFailsInsteadOfDeadlocking() {
        try (var processor = new BoundedSlipImageProcessor((input, options) -> RESULT, settings(1, 1))) {
            var future = processor.processAsync(() -> { processor.process(INPUT, OPTIONS); return INPUT; }, OPTIONS);
            assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, future::join).getCause());
        }
    }

    @Test void validatesLimitsAndInputsBeforeSubmission() {
        assertThrows(IllegalArgumentException.class, () -> settings(0, 1));
        assertThrows(IllegalArgumentException.class, () -> settings(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionSettings(1, 1, Duration.ZERO));
        try (var processor = new BoundedSlipImageProcessor((input, options) -> RESULT, settings(1, 1))) {
            assertThrows(IllegalArgumentException.class, () -> processor.process(new byte[0], OPTIONS));
            assertThrows(NullPointerException.class, () -> processor.processAsync(INPUT, null));
            assertEquals(0, processor.status().completed());
        }
    }
}
