package kr.co.slip.opencv;

import kr.co.slip.api.ProcessingOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.jupiter.api.Assertions.*;

class RotationTest {
    private final SlipProcessor processor = new SlipProcessor();

    @ParameterizedTest @ValueSource(ints = {0, 90, 180, 270})
    void manualRotationPreservesMarkerAndDimensions(int degrees) {
        Mat image = new Mat(1000, 800, CvType.CV_8UC3, Scalar.all(255));
        try {
            Imgproc.circle(image, new Point(80, 100), 18, new Scalar(0, 0, 255), -1);
            var result = processor.process(encode(image), ProcessingOptions.defaults().withRotation(degrees));
            MatOfByte encoded = new MatOfByte(result.images().get("01-corrected.png"));
            Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
            try {
                Point expected = switch (degrees) {
                    case 90 -> new Point(899, 80);
                    case 180 -> new Point(719, 899);
                    case 270 -> new Point(100, 719);
                    default -> new Point(80, 100);
                };
                assertArrayEquals(new double[]{0, 0, 255}, decoded.get((int) expected.y, (int) expected.x), 1);
                assertEquals(degrees % 180 == 0 ? 800 : 1000, decoded.cols());
                assertEquals(degrees % 180 == 0 ? 1000 : 800, decoded.rows());
                assertEquals(degrees, result.report().rotation().clockwiseDegrees());
                assertEquals("MANUAL", result.report().rotation().mode());
                assertFalse(result.report().rotation().reviewRequired());
            } finally { decoded.release(); encoded.release(); }
        } finally { image.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {0, 180})
    void automaticOrientationUsesHeaderInsteadOfBlankRows(int inputDegrees) {
        Mat image = table();
        try {
            if (inputDegrees == 180) Core.rotate(image, image, Core.ROTATE_180);
            var result = processor.process(encode(image), ProcessingOptions.defaults());
            assertEquals(inputDegrees, result.report().rotation().clockwiseDegrees());
            assertEquals("GRID_AND_HEADER_INK", result.report().rotation().method());
            assertFalse(result.report().tables().isEmpty());
            assertTrue(result.report().rotation().reviewRequired());
        } finally { image.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {-27, 23, 44})
    void largerSlantsAreAlignedWithoutClipping(int angle) {
        Mat image = table(), slanted = new Mat();
        Mat transform = Imgproc.getRotationMatrix2D(new Point(900, 900), angle, 1);
        try {
            Imgproc.warpAffine(image, slanted, transform, image.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(255));
            var result = processor.process(encode(slanted), ProcessingOptions.defaults().withRotation(0));
            assertEquals(-angle, result.report().rotation().deskewCounterClockwiseDegrees(), 1.0);
            assertTrue(result.report().workingWidth() > 1800);
            assertFalse(result.report().tables().isEmpty());
        } finally { image.release(); slanted.release(); transform.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {90, 180, 270})
    void documentCornersStayInTheOriginalCoordinateSystem(int rotation) {
        Point[] original = {new Point(160, 130), new Point(860, 180), new Point(900, 1250), new Point(80, 1200)};
        Mat image = new Mat(1400, 1000, CvType.CV_8UC3, Scalar.all(65));
        MatOfPoint polygon = new MatOfPoint(original);
        try {
            Imgproc.fillConvexPoly(image, polygon, Scalar.all(245));
            var result = processor.process(encode(image), ProcessingOptions.defaults().withRotation(rotation));
            assertEquals(4, result.report().documentCorners().size());
            for (var point : result.report().documentCorners()) assertTrue(java.util.Arrays.stream(original)
                    .anyMatch(p -> Math.hypot(p.x - point.x(), p.y - point.y()) < 8));
        } finally { image.release(); polygon.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {0, 90, 180, 270})
    void automaticQuarterTurnsAndSlantWorkTogether(int inputRotation) {
        Mat image = table(), slanted = new Mat();
        Mat transform = Imgproc.getRotationMatrix2D(new Point(900, 900), 17, 1);
        try {
            if (inputRotation != 0) Core.rotate(image, image, switch (inputRotation) {
                case 90 -> Core.ROTATE_90_CLOCKWISE;
                case 180 -> Core.ROTATE_180;
                default -> Core.ROTATE_90_COUNTERCLOCKWISE;
            });
            Imgproc.warpAffine(image, slanted, transform, image.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(255));
            var result = processor.process(encode(slanted), ProcessingOptions.defaults());
            assertEquals((360 - inputRotation) % 360, result.report().rotation().clockwiseDegrees());
            assertEquals(-17, result.report().rotation().deskewCounterClockwiseDegrees(), 1.0);
            assertFalse(result.report().tables().isEmpty());
        } finally { image.release(); slanted.release(); transform.release(); }
    }

    @Test void unsupportedRotationIsRejected() {
        for (int degrees : new int[]{-90, 45, 360})
            assertThrows(IllegalArgumentException.class, () -> ProcessingOptions.defaults().withRotation(degrees));
    }

    private static Mat table() {
        Mat image = new Mat(1800, 1800, CvType.CV_8UC3, Scalar.all(255));
        for (int y = 300; y <= 1500; y += 80) Imgproc.line(image, new Point(300, y), new Point(1500, y), Scalar.all(0), 2);
        for (int x = 300; x <= 1500; x += 300) Imgproc.line(image, new Point(x, 300), new Point(x, 1500), Scalar.all(0), 2);
        for (int x = 325; x < 1500; x += 300)
            Imgproc.putText(image, "HEADER", new Point(x, 350), Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, Scalar.all(0), 3);
        return image;
    }
    private static byte[] encode(Mat image) {
        MatOfByte bytes = new MatOfByte();
        try { Imgcodecs.imencode(".png", image, bytes); return bytes.toArray(); }
        finally { bytes.release(); }
    }
}
