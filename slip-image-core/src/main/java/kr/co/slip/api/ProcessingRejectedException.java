package kr.co.slip.api;

/** Capacity exhausted or processor shutting down; map at the host's HTTP boundary. */
public final class ProcessingRejectedException extends RuntimeException {
    public ProcessingRejectedException(String message) { super(message); }
}
