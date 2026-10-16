package kr.co.slip.execution;

import kr.co.slip.api.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Java-only execution policy. Native invocation occupies its worker until it actually returns. */
public final class BoundedSlipImageProcessor implements ManagedSlipImageProcessor {
    private final SlipImageProcessor delegate;
    private final ExecutionSettings settings;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejected = new AtomicLong();
    private final ThreadLocal<Boolean> worker = ThreadLocal.withInitial(() -> false);

    public BoundedSlipImageProcessor(SlipImageProcessor delegate, ExecutionSettings settings) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.settings = Objects.requireNonNull(settings, "settings");
        AtomicInteger sequence = new AtomicInteger();
        BlockingQueue<Runnable> queue = settings.queueCapacity() == 0
                ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(settings.queueCapacity());
        executor = new ThreadPoolExecutor(settings.workers(), settings.workers(), 0, TimeUnit.MILLISECONDS,
                queue, task -> new Thread(task, "slip-image-" + sequence.incrementAndGet()), new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public ProcessingResult process(byte[] input, ProcessingOptions options) {
        if (worker.get()) throw new IllegalStateException("Recursive processing on the same worker pool is not supported");
        CompletableFuture<ProcessingResult> future = processAsync(input, options);
        try { return future.get(); }
        catch (InterruptedException interrupted) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new ProcessingInterruptedException(interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new CompletionException(cause);
        }
    }

    @Override public CompletableFuture<ProcessingResult> processAsync(byte[] input, ProcessingOptions options) {
        Objects.requireNonNull(input, "input");
        if (input.length == 0 || input.length > 20 * 1024 * 1024)
            throw new IllegalArgumentException("Image must be between 1 byte and 20 MiB");
        return processAsync(() -> input, options);
    }

    @Override public CompletableFuture<ProcessingResult> processAsync(Callable<byte[]> loader, ProcessingOptions options) {
        Objects.requireNonNull(loader, "loader"); Objects.requireNonNull(options, "options");
        Task task = new Task(loader, options);
        try { executor.execute(task); }
        catch (RejectedExecutionException full) {
            rejected.incrementAndGet();
            throw new ProcessingRejectedException(executor.isShutdown() ? "Image processor is closed" : "Image processing queue is full");
        }
        return task;
    }

    private final class Task extends CompletableFuture<ProcessingResult> implements Runnable {
        private volatile Callable<byte[]> loader;
        private final ProcessingOptions options;
        Task(Callable<byte[]> loader, ProcessingOptions options) { this.loader = loader; this.options = options; }
        @Override public void run() {
            if (isCancelled()) { loader = null; return; }
            Callable<byte[]> source = loader;
            loader = null;
            worker.set(true);
            try { complete(delegate.process(source.call(), options)); }
            catch (Throwable failure) { completeExceptionally(failure); }
            finally { worker.remove(); }
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            // Do not treat an interrupted JNI call as finished or free capacity prematurely.
            boolean cancelled = super.cancel(false);
            if (cancelled && executor.remove(this)) loader = null;
            return cancelled;
        }
    }

    @Override public ExecutionStatus status() {
        return new ExecutionStatus(settings.workers(), executor.getActiveCount(), executor.getQueue().size(),
                executor.getCompletedTaskCount(), rejected.get(), executor.isShutdown());
    }

    @Override public void close() {
        if (worker.get()) throw new IllegalStateException("Close must be called outside the processing worker");
        executor.shutdown();
        boolean terminated;
        try { terminated = executor.awaitTermination(settings.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) {
            cancelQueued(); Thread.currentThread().interrupt();
            throw new ProcessingInterruptedException(interrupted);
        }
        if (!terminated) {
            cancelQueued();
            throw new IllegalStateException("Shutdown timed out; native work may still be running");
        }
    }

    private void cancelQueued() {
        for (Runnable pending : executor.shutdownNow())
            if (pending instanceof BoundedSlipImageProcessor.Task task) {
                task.loader = null;
                task.completeExceptionally(new ProcessingRejectedException("Processor closed before task started"));
            }
    }
}
