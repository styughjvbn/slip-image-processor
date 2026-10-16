package kr.co.slip.opencv;

import kr.co.slip.api.ProcessingResult;

import kr.co.slip.api.ProcessingOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static kr.co.slip.api.ProcessingResult.*;

class ResolutionTest {
    ResolutionTest() { NativeOpenCv.load(); }

    @Test void scaleUsesActualResizeAndDoesNotUpscaleSmallSources() {
        assertEquals(1, PixelScale.forSource(600, 3000).factor());
        assertEquals(1, PixelScale.forSource(1400, 2200).factor());
        PixelScale half = PixelScale.forSource(4000, 1100);
        assertEquals(.5, half.factor());
        assertEquals(10, half.length(20));
        assertEquals(2, half.area(8));
        assertEquals(1, half.kernel(1));
        assertEquals(1, half.capped(1100, 900).factor());
        assertEquals(800. / 900, PixelScale.forSource(4000, 800).capped(800, 900).factor(), 1e-9);
    }

    @ParameterizedTest @ValueSource(ints = {800, 1100, 1600, 2200, 3000})
    void keepsWrittenRowsAndRemovesBlankRowsAtDifferentResolutions(int dimension) {
        Mat reference = new Mat(2200, 1600, CvType.CV_8UC3, Scalar.all(255));
        Mat source = new Mat();
        MatOfByte encoded = new MatOfByte();
        try {
            for (int y = 300; y <= 1860; y += 120)
                Imgproc.line(reference, new Point(160, y), new Point(1440, y), Scalar.all(0), 3);
            for (int x = 160; x <= 1440; x += 320)
                Imgproc.line(reference, new Point(x, 300), new Point(x, 1860), Scalar.all(0), 3);
            for (int i = 0; i < 13; i++) Imgproc.putText(reference, "FIXED", new Point(500, 375 + i * 120),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 1, Scalar.all(0), 3);
            Imgproc.putText(reference, "BLUE", new Point(850, 735), Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, new Scalar(180, 60, 20), 3);
            Imgproc.putText(reference, "BLACK", new Point(850, 1095), Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, Scalar.all(0), 3);
            Imgproc.resize(reference, source, new Size(2400, 3300), 0, 0, Imgproc.INTER_CUBIC);
            Imgcodecs.imencode(".png", source, encoded);
            var result = new SlipProcessor().process(encoded.toArray(),
                    ProcessingOptions.defaults().withMaxDimension(dimension).withRotation(0));
            assertEquals(dimension, result.report().workingHeight());
            assertEquals(1, result.report().tables().size());
            List<Row> rows = result.report().tables().getFirst().rows();
            assertEquals(13, rows.size());
            assertFalse(rows.get(3).removed(), "Blue writing must survive");
            assertFalse(rows.get(6).removed(), "Black writing must survive");
            assertEquals(9, rows.stream().filter(Row::removed).count(), () -> "Only two headers and two written rows remain: " + rows);
        } finally { reference.release(); source.release(); encoded.release(); }
    }

    @ParameterizedTest @ValueSource(ints = {800, 1100, 2200, 3000})
    void overlappingPaperBoundariesScaleWithResolution(int dimension) {
        double factor = dimension / 2200.;
        Mat reference = new Mat(2200, 1760, CvType.CV_8UC3, new Scalar(70, 110, 150));
        Mat image = new Mat();
        try {
            Imgproc.rectangle(reference, new Point(220, 154), new Point(1540, 1826), new Scalar(230, 225, 215), -1);
            Imgproc.rectangle(reference, new Point(264, 946), new Point(1584, 2046), new Scalar(230, 225, 215), -1);
            Imgproc.line(reference, new Point(264, 946), new Point(1584, 946), new Scalar(150, 155, 150), 6);
            Imgproc.resize(reference, image, new Size(Math.round(1760 * factor), dimension), 0, 0, Imgproc.INTER_AREA);
            List<Table> tables = List.of(table(1, 396, 572, 946, 308, factor), table(2, 440, 1386, 946, 528, factor));
            var result = PaperSeparator.separate(image, tables, false, new PixelScale(factor));
            assertEquals(2, result.papers().size());
            Box upper = result.papers().getFirst().bounds();
            assertEquals(946 * factor, upper.y() + upper.height(), 15 * factor + 2);
        } finally { reference.release(); image.release(); }
    }

    private static Table table(int index, int x, int y, int width, int height, double factor) {
        return new Table(index, new Box((int) (x * factor), (int) (y * factor),
                (int) (width * factor), (int) (height * factor)), List.of(), List.of(), List.of(), 0);
    }
}
