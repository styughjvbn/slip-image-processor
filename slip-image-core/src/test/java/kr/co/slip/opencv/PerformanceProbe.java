package kr.co.slip.opencv;

import kr.co.slip.api.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Manual resource probe: input maxDimension outputMode workers rounds; not a timing assertion. */
public final class PerformanceProbe {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        int dimension = Integer.parseInt(args[1]), workers = Integer.parseInt(args[3]), rounds = Integer.parseInt(args[4]);
        OutputMode mode = OutputMode.valueOf(args[2]);
        ProcessingOptions options = ProcessingOptions.defaults().withMaxDimension(dimension).withOutputMode(mode);
        long start = System.nanoTime();
        try (var processor = SlipProcessors.bounded(new ExecutionSettings(workers, workers, Duration.ofSeconds(30)))) {
            for (int round = 0; round < rounds; round++) {
                List<java.util.concurrent.CompletableFuture<ProcessingResult>> futures = new ArrayList<>();
                for (int i = 0; i < workers; i++) futures.add(processor.processAsync(() -> Files.readAllBytes(input), options));
                long bytes = 0;
                for (var future : futures) {
                    var result = future.get();
                    for (byte[] png : result.images().values()) bytes += png.length;
                    if (result.report().tables().isEmpty()) throw new AssertionError("Expected table");
                }
                long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                System.out.printf(java.util.Locale.ROOT, "round=%d elapsedSeconds=%.3f outputMiB=%.2f heapUsedMiB=%.1f%n",
                        round + 1, (System.nanoTime() - start) / 1e9, bytes / 1048576., heap / 1048576.);
            }
            System.out.println("completed=" + processor.status().completed());
        }
    }
}
