package kr.co.slip.api;

import java.time.Duration;
import java.util.Objects;

/** Limits apply per managed processor instance. Native OpenCV threads are configured separately. */
public record ExecutionSettings(int workers, int queueCapacity, Duration shutdownTimeout) {
    public ExecutionSettings {
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (workers < 1 || queueCapacity < 0 || shutdownTimeout.isNegative() || shutdownTimeout.isZero()
                || shutdownTimeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Invalid execution settings");
    }
    public static ExecutionSettings defaults() { return new ExecutionSettings(2, 4, Duration.ofSeconds(30)); }
}
