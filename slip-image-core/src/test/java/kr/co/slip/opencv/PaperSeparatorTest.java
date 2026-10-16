package kr.co.slip.opencv;

import kr.co.slip.api.ProcessingResult;

import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.List;
import static kr.co.slip.api.ProcessingResult.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperSeparatorTest {
    PaperSeparatorTest() { NativeOpenCv.load(); }
    private static final Scalar DESK = new Scalar(70, 110, 150), PAPER = new Scalar(230, 225, 215);

    @Test void knownDocumentQuadIsExportedWithOpaqueAlpha() {
        Mat image = new Mat(600, 400, CvType.CV_8UC3, PAPER);
        try {
            var result = PaperSeparator.separate(image, List.of(), true);
            assertEquals(1, result.papers().size());
            assertFalse(result.papers().getFirst().reviewRequired());
            MatOfByte encoded = new MatOfByte(result.images().get("paper-01.png"));
            Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_UNCHANGED);
            try { assertEquals(4, decoded.channels()); assertEquals(255, decoded.get(10, 10)[3]); }
            finally { encoded.release(); decoded.release(); }
        } finally { image.release(); }
    }

    @Test void trapezoidalPaperIsSeparatedFromDeskWithTransparentCorners() {
        Mat image = new Mat(1000, 800, CvType.CV_8UC3, DESK);
        MatOfPoint polygon = new MatOfPoint(new Point(140, 100), new Point(700, 130), new Point(680, 900), new Point(90, 860));
        try {
            Imgproc.fillConvexPoly(image, polygon, PAPER);
            var result = PaperSeparator.separate(image, List.of(table(1, 200, 280, 400, 450)), false);
            assertEquals(1, result.papers().size());
            var box = result.papers().getFirst().bounds();
            assertEquals(90, box.x(), 20); assertEquals(100, box.y(), 20);
            MatOfByte encoded = new MatOfByte(result.images().get("paper-01.png"));
            Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_UNCHANGED);
            try {
                assertEquals(4, decoded.channels());
                assertEquals(0, decoded.get(0, 0)[3]);
                assertEquals(255, decoded.get(500 - box.y(), 400 - box.x())[3]);
            } finally { encoded.release(); decoded.release(); }
        } finally { image.release(); polygon.release(); }
    }

    @Test void overlappingPapersAreSplitAtObservedEdge() {
        Mat image = new Mat(1000, 800, CvType.CV_8UC3, DESK);
        try {
            Imgproc.rectangle(image, new Point(100, 70), new Point(700, 830), PAPER, -1);
            Imgproc.rectangle(image, new Point(120, 430), new Point(720, 930), PAPER, -1);
            Imgproc.line(image, new Point(120, 430), new Point(720, 430), new Scalar(150, 155, 150), 3);
            var result = PaperSeparator.separate(image, List.of(table(1, 180, 260, 430, 140), table(2, 200, 630, 430, 240)), false);
            assertEquals(2, result.papers().size());
            assertEquals("VISIBLE_EDGE_SPLIT", result.papers().getFirst().method());
            assertEquals(List.of(1), result.papers().getFirst().tableIndices());
            assertEquals(List.of(2), result.papers().getLast().tableIndices());
            var first = result.papers().getFirst().bounds();
            assertEquals(430, first.y() + first.height(), 12);
        } finally { image.release(); }
    }

    @Test void twoTablesWithoutObservedSeamAreNotArbitrarilySplit() {
        Mat image = new Mat(1000, 800, CvType.CV_8UC3, DESK);
        try {
            Imgproc.rectangle(image, new Point(100, 70), new Point(700, 930), PAPER, -1);
            var result = PaperSeparator.separate(image, List.of(table(1, 180, 260, 430, 140), table(2, 200, 630, 430, 240)), false);
            assertEquals(1, result.papers().size());
            assertEquals("CONNECTED_PAPER_REGION", result.papers().getFirst().method());
            assertTrue(result.warnings().stream().anyMatch(w -> w.contains("하나로 유지")));
        } finally { image.release(); }
    }

    @Test void paperEdgeCanBeVisibleInColorWithAlmostEqualGrayValues() {
        Mat image = new Mat(1000, 800, CvType.CV_8UC3, DESK);
        try {
            Imgproc.rectangle(image, new Point(100, 70), new Point(700, 830), new Scalar(235, 215, 200), -1);
            Imgproc.rectangle(image, new Point(120, 430), new Point(720, 930), new Scalar(211, 211, 211), -1);
            var result = PaperSeparator.separate(image, List.of(table(1, 180, 260, 430, 140), table(2, 200, 630, 430, 240)), false);
            assertEquals(2, result.papers().size());
            assertEquals("VISIBLE_EDGE_SPLIT", result.papers().getFirst().method());
        } finally { image.release(); }
    }

    @Test void disconnectedPapersAreExportedIndividually() {
        Mat image = new Mat(900, 1200, CvType.CV_8UC3, DESK);
        try {
            Imgproc.rectangle(image, new Point(80, 70), new Point(530, 830), PAPER, -1);
            Imgproc.rectangle(image, new Point(670, 90), new Point(1120, 830), PAPER, -1);
            var result = PaperSeparator.separate(image, List.of(table(1, 140, 300, 300, 400), table(2, 730, 300, 300, 400)), false);
            assertEquals(2, result.papers().size());
        } finally { image.release(); }
    }

    @Test void uniformFrameDoesNotInventPaperBoundary() {
        Mat image = new Mat(800, 600, CvType.CV_8UC3, Scalar.all(255));
        try { assertTrue(PaperSeparator.separate(image, List.of(), false).papers().isEmpty()); }
        finally { image.release(); }
    }

    @Test void differentSheetWidthsDoNotCreateADeskTriangleInTheUpperCutout() {
        Mat image = new Mat(1000, 850, CvType.CV_8UC3, DESK);
        try {
            Imgproc.rectangle(image, new Point(150, 60), new Point(600, 650), PAPER, -1);
            Imgproc.rectangle(image, new Point(100, 430), new Point(750, 950), PAPER, -1);
            Imgproc.line(image, new Point(100, 430), new Point(750, 430), new Scalar(150, 155, 150), 3);
            var result = PaperSeparator.separate(image, List.of(table(1, 200, 180, 350, 220), table(2, 200, 630, 450, 240)), false);
            assertEquals(2, result.papers().size());
            var upper = result.papers().getFirst().bounds();
            assertTrue(upper.x() >= 135, "Upper sheet should not expand to the lower sheet's left edge");
            assertTrue(upper.x() + upper.width() <= 615, "Upper sheet should not expand to the lower sheet's right edge");
        } finally { image.release(); }
    }

    private static Table table(int index, int x, int y, int width, int height) {
        return new Table(index, new Box(x, y, width, height), List.of(), List.of(), List.of(), 0);
    }
}
