package kr.co.slip.api;

/** Public Java-only contract. No Spring, HTTP, persistence, or OpenCV types cross this boundary. */
public interface SlipImageProcessor {
    ProcessingResult process(byte[] input, ProcessingOptions options);
}
