package kr.co.slip.opencv;

import kr.co.slip.api.ProcessingResult;

import kr.co.slip.api.ProcessingOptions;

import org.junit.jupiter.api.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.jupiter.api.Assertions.*;

class SlipProcessorTest {
    private final SlipProcessor processor = new SlipProcessor();

    @Test void rejectsInvalidBytes() {
        assertThrows(IllegalArgumentException.class, () -> processor.process(new byte[]{1, 2}, ProcessingOptions.defaults()));
    }

    @Test void noTableIsPreserved() {
        Mat image = new Mat(1000, 800, CvType.CV_8UC3, Scalar.all(255));
        try {
            var result = processor.process(encode(image), ProcessingOptions.defaults());
            assertTrue(result.report().tables().isEmpty());
            assertFalse(result.report().warnings().isEmpty());
        } finally { image.release(); }
    }

    @Test void repeatedPrintIsIgnoredButBlueAndBlackWritingSurvive() {
        Mat image = new Mat(1400, 1000, CvType.CV_8UC3, Scalar.all(255));
        try {
            for (int y = 200; y <= 1200; y += 80) Imgproc.line(image, new Point(100, y), new Point(900, y), Scalar.all(0), 2);
            for (int x = 100; x <= 900; x += 200) Imgproc.line(image, new Point(x, 200), new Point(x, 1160), Scalar.all(0), 2);
            for (int i = 0; i < 12; i++) Imgproc.putText(image, "FIXED", new Point(320, 250 + i * 80), Imgproc.FONT_HERSHEY_SIMPLEX, .65, Scalar.all(0), 2);
            Imgproc.putText(image, "BLUE", new Point(540, 490), Imgproc.FONT_HERSHEY_SIMPLEX, .8, new Scalar(180, 60, 20), 2);
            Imgproc.putText(image, "BLACK", new Point(540, 730), Imgproc.FONT_HERSHEY_SIMPLEX, .8, Scalar.all(0), 2);
            var result = processor.process(encode(image), ProcessingOptions.defaults());
            assertFalse(result.report().tables().isEmpty());
            var rows = result.report().tables().getFirst().rows();
            assertTrue(rows.stream().anyMatch(ProcessingResult.Row::removed), "Repeated labels should not keep empty rows");
            assertFalse(rows.get(3).removed(), "Blue writing must survive");
            assertFalse(rows.get(6).removed(), "Black writing must survive");
            assertFalse(rows.get(0).removed(), "Header must survive");
            var conservative = processor.process(encode(image), new ProcessingOptions(2200, 2, false, .00035, 8));
            assertEquals(0, conservative.report().tables().getFirst().removedRows());
        } finally { image.release(); }
    }

    @Test void perspectivePageHasFourSourceCorners() {
        Mat image = new Mat(1400, 1000, CvType.CV_8UC3, new Scalar(70, 65, 60));
        MatOfPoint polygon = new MatOfPoint(new Point(160, 130), new Point(860, 180), new Point(900, 1250), new Point(80, 1200));
        try {
            Imgproc.fillConvexPoly(image, polygon, Scalar.all(245));
            var result = processor.process(encode(image), ProcessingOptions.defaults());
            assertEquals("DOCUMENT_PERSPECTIVE", result.report().correction());
            assertEquals(4, result.report().documentCorners().size());
        } finally { polygon.release(); image.release(); }
    }

    private static byte[] encode(Mat image) {
        MatOfByte buffer = new MatOfByte();
        try { Imgcodecs.imencode(".png", image, buffer); return buffer.toArray(); }
        finally { buffer.release(); }
    }

    @Test void gridPerspectiveWorksWhenThePageBoundaryIsMissing() {
        Mat image = new Mat(1400, 1000, CvType.CV_8UC3, Scalar.all(255));
        Mat transformed = new Mat();
        MatOfPoint2f from = new MatOfPoint2f(new Point(0, 0), new Point(999, 0), new Point(999, 1399), new Point(0, 1399));
        MatOfPoint2f to = new MatOfPoint2f(new Point(30, 0), new Point(970, 20), new Point(999, 1399), new Point(0, 1370));
        Mat transform = Imgproc.getPerspectiveTransform(from, to);
        try {
            for (int y = 180; y <= 1220; y += 80) Imgproc.line(image, new Point(100, y), new Point(900, y), Scalar.all(0), 2);
            for (int x = 100; x <= 900; x += 160) Imgproc.line(image, new Point(x, 180), new Point(x, 1220), Scalar.all(0), 2);
            Imgproc.warpPerspective(image, transformed, transform, image.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(255));
            var result = processor.process(encode(transformed), ProcessingOptions.defaults());
            assertEquals("GRID_PERSPECTIVE", result.report().correction());
            assertFalse(result.report().tables().isEmpty());
            assertTrue(result.report().tables().getFirst().removedRows() > 5);
        } finally { image.release(); transformed.release(); from.release(); to.release(); transform.release(); }
    }

    @Test void sidewaysTablesAreRotatedBeforeRowDetection() {
        Mat upright = new Mat(1400, 1000, CvType.CV_8UC3, Scalar.all(255));
        Mat sideways = new Mat();
        try {
            Imgproc.putText(upright, "DOCUMENT TITLE", new Point(100, 130), Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar.all(0), 3);
            for (int y = 200; y <= 1200; y += 60) Imgproc.line(upright, new Point(100, y), new Point(900, y), Scalar.all(0), 2);
            for (int x = 100; x <= 900; x += 200) Imgproc.line(upright, new Point(x, 200), new Point(x, 1160), Scalar.all(0), 2);
            Core.rotate(upright, sideways, Core.ROTATE_90_CLOCKWISE);
            var result = processor.process(encode(sideways), ProcessingOptions.defaults());
            assertTrue(result.report().workingHeight() > result.report().workingWidth());
            assertFalse(result.report().tables().isEmpty());
            assertEquals(90, result.report().rotation().clockwiseDegrees() % 180);
        } finally { upright.release(); sideways.release(); }
    }
}
