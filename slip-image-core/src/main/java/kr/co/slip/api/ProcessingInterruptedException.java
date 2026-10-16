package kr.co.slip.api;

/** The caller stopped waiting; native work may continue until its current invocation finishes. */
public final class ProcessingInterruptedException extends RuntimeException {
    public ProcessingInterruptedException(InterruptedException cause) { super("Image processing wait interrupted", cause); }
}
