package kr.co.slip.api;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** Share one instance and close it at host shutdown. Never call it recursively from its input loader. */
public interface ManagedSlipImageProcessor extends SlipImageProcessor, AutoCloseable {
    CompletableFuture<ProcessingResult> processAsync(byte[] input, ProcessingOptions options);
    /** Loader executes only after a worker is available; prefer this for queued files. */
    CompletableFuture<ProcessingResult> processAsync(Callable<byte[]> loader, ProcessingOptions options);
    ExecutionStatus status();
    @Override void close();
    record ExecutionStatus(int workers, int active, int queued, long completed, long rejected, boolean closed) {}
}
