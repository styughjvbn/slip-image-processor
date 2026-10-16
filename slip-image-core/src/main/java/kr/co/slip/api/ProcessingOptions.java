package kr.co.slip.api;

/** Spatial thresholds use the default-size reference and scale with the actual input resize. */
public record ProcessingOptions(int maxDimension, int protectedTopRows, boolean ignoreRepeatedPrint,
                                double minimumInkRatio, int minimumComponentArea, int rotationDegrees, OutputMode outputMode) {
    public ProcessingOptions {
        java.util.Objects.requireNonNull(outputMode, "outputMode");
        if (maxDimension < 800 || maxDimension > 3000 || protectedTopRows < 1 || protectedTopRows > 10
                || !Double.isFinite(minimumInkRatio) || minimumInkRatio < 0 || minimumInkRatio > .1
                || minimumComponentArea < 2 || minimumComponentArea > 1000
                || (rotationDegrees != -1 && rotationDegrees != 0 && rotationDegrees != 90
                    && rotationDegrees != 180 && rotationDegrees != 270))
            throw new IllegalArgumentException("Invalid processing options");
    }
    public ProcessingOptions(int maxDimension, int protectedTopRows, boolean ignoreRepeatedPrint,
                             double minimumInkRatio, int minimumComponentArea, int rotationDegrees) {
        this(maxDimension, protectedTopRows, ignoreRepeatedPrint, minimumInkRatio, minimumComponentArea,
                rotationDegrees, OutputMode.ALL);
    }
    public ProcessingOptions(int maxDimension, int protectedTopRows, boolean ignoreRepeatedPrint,
                             double minimumInkRatio, int minimumComponentArea) {
        this(maxDimension, protectedTopRows, ignoreRepeatedPrint, minimumInkRatio, minimumComponentArea, -1);
    }
    /** -1 = automatic; otherwise an exact clockwise rotation, after EXIF decoding. */
    public ProcessingOptions withRotation(int degrees) {
        return new ProcessingOptions(maxDimension, protectedTopRows, ignoreRepeatedPrint,
                minimumInkRatio, minimumComponentArea, degrees, outputMode);
    }
    public ProcessingOptions withMaxDimension(int dimension) {
        return new ProcessingOptions(dimension, protectedTopRows, ignoreRepeatedPrint,
                minimumInkRatio, minimumComponentArea, rotationDegrees, outputMode);
    }
    public ProcessingOptions withOutputMode(OutputMode mode) {
        return new ProcessingOptions(maxDimension, protectedTopRows, ignoreRepeatedPrint,
                minimumInkRatio, minimumComponentArea, rotationDegrees, mode);
    }
    public static ProcessingOptions defaults() { return new ProcessingOptions(2200, 2, true, .00035, 8); }
}
