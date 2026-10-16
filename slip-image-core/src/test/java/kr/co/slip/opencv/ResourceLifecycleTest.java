package kr.co.slip.opencv;

import kr.co.slip.api.*;
import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceLifecycleTest {
    ResourceLifecycleTest() { NativeOpenCv.load(); }

    @Test void scopeReleasesSetToAliasesAndRoisEvenOnFailure() {
        Mat original = new Mat(100, 100, CvType.CV_8UC3, Scalar.all(255));
        Mat alias, roi;
        try (MatScope scope = new MatScope()) {
            scope.own(original);
            alias = scope.own(original.setTo(Scalar.all(0)));
            roi = scope.own(original.submat(new Rect(10, 10, 20, 20)));
        }
        assertTrue(original.empty()); assertTrue(alias.empty()); assertTrue(roi.empty());
        Mat failed = new Mat(100, 100, CvType.CV_8UC1);
        assertThrows(IllegalArgumentException.class, () -> {
            try (MatScope scope = new MatScope()) { scope.own(failed); throw new IllegalArgumentException("failure"); }
        });
        assertTrue(failed.empty());
    }

    @Test void releasedParentDoesNotInvalidateOwnedRoiAndCloseIsIdempotent() {
        MatScope scope = new MatScope();
        Mat original = scope.own(new Mat(50, 50, CvType.CV_8UC1, Scalar.all(42)));
        Mat roi = scope.own(original.submat(new Rect(5, 5, 10, 10)));
        scope.release(original);
        assertTrue(original.empty()); assertEquals(42, roi.get(0, 0)[0]);
        scope.close(); scope.close(); assertTrue(roi.empty());
    }

    @Test void modesSkipDiagnosticsWithoutChangingReportsOrProductionImages() {
        byte[] image = fixture();
        SlipProcessor engine = new SlipProcessor();
        ProcessingOptions base = ProcessingOptions.defaults().withRotation(0);
        var all = engine.process(image, base);
        var production = engine.process(image, base.withOutputMode(OutputMode.PRODUCTION));
        var review = engine.process(image, base.withOutputMode(OutputMode.REVIEW));
        assertEquals(all.report(), production.report()); assertEquals(all.report(), review.report());
        assertTrue(production.images().containsKey("paper-01.png"));
        assertTrue(production.images().containsKey("table-01-compacted.png"));
        for (String key : production.images().keySet()) assertArrayEquals(all.images().get(key), production.images().get(key));
        assertFalse(production.images().containsKey("05-row-decisions.png"));
        assertTrue(review.images().containsKey("05-row-decisions.png"));
        assertTrue(review.images().containsKey("06-paper-contours.png"));
        assertFalse(review.images().containsKey("07-paper-mask.png"));
        assertFalse(review.images().containsKey("02-lines.png"));
        assertTrue(all.images().containsKey("07-paper-mask.png"));
    }

    @Test void sameNativeEngineSurvivesRepeatedConcurrentSuccessAndFailure() throws Exception {
        byte[] input = fixture();
        var options = ProcessingOptions.defaults().withRotation(0).withOutputMode(OutputMode.PRODUCTION);
        try (var processor = SlipProcessors.bounded(new ExecutionSettings(2, 2, java.time.Duration.ofSeconds(10)))) {
            for (int round = 0; round < 4; round++) {
                var first = processor.processAsync(input, options);
                var second = processor.processAsync(input, options);
                assertEquals(first.get(10, TimeUnit.SECONDS).report(), second.get(10, TimeUnit.SECONDS).report());
                assertThrows(IllegalArgumentException.class, () -> processor.process(new byte[]{1, 2}, options));
            }
            assertFalse(processor.process(input, options).report().tables().isEmpty());
        }
    }

    private static byte[] fixture() {
        Mat image = new Mat(1000, 800, CvType.CV_8UC3, Scalar.all(70));
        MatOfByte encoded = new MatOfByte();
        try {
            Imgproc.rectangle(image, new Point(60, 50), new Point(740, 950), Scalar.all(245), -1);
            for (int y = 200; y <= 800; y += 60) Imgproc.line(image, new Point(120, y), new Point(680, y), Scalar.all(0), 2);
            for (int x = 120; x <= 680; x += 140) Imgproc.line(image, new Point(x, 200), new Point(x, 800), Scalar.all(0), 2);
            Imgproc.putText(image, "INK", new Point(280, 490), Imgproc.FONT_HERSHEY_SIMPLEX, .7, Scalar.all(0), 2);
            Imgcodecs.imencode(".png", image, encoded); return encoded.toArray();
        } finally { image.release(); encoded.release(); }
    }
}
