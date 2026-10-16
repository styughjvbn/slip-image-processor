package kr.co.slip.opencv;

import kr.co.slip.api.*;
import kr.co.slip.execution.BoundedSlipImageProcessor;
import org.opencv.core.Core;

/** Composition entry point for portable, bounded execution. Configure native threads once per JVM. */
public final class SlipProcessors {
    private static Integer configuredThreads;
    private SlipProcessors() {}

    public static ManagedSlipImageProcessor bounded(ExecutionSettings settings) { return bounded(settings, 1); }

    public static ManagedSlipImageProcessor bounded(ExecutionSettings settings, int openCvThreads) {
        java.util.Objects.requireNonNull(settings, "settings");
        configureThreads(openCvThreads);
        return new BoundedSlipImageProcessor(new SlipProcessor(), settings);
    }

    private static synchronized void configureThreads(int threads) {
        if (threads < 1) throw new IllegalArgumentException("openCvThreads must be positive");
        NativeOpenCv.load();
        if (configuredThreads != null && configuredThreads != threads)
            throw new IllegalStateException("OpenCV thread setting is JVM-wide and has already been configured");
        if (configuredThreads == null) { Core.setNumThreads(threads); configuredThreads = threads; }
    }
}
