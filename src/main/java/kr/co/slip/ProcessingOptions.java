package kr.co.slip;

/** Pixel thresholds refer to the resized working image; no OCR or learned model is used. */
public record ProcessingOptions(int maxDimension, int protectedTopRows, boolean ignoreRepeatedPrint,
                                double minimumInkRatio, int minimumComponentArea) {
    public ProcessingOptions {
        if (maxDimension < 800 || maxDimension > 3000 || protectedTopRows < 1 || protectedTopRows > 10
                || !Double.isFinite(minimumInkRatio) || minimumInkRatio < 0 || minimumInkRatio > .1
                || minimumComponentArea < 2 || minimumComponentArea > 1000)
            throw new IllegalArgumentException("Invalid processing options");
    }
    public static ProcessingOptions defaults() { return new ProcessingOptions(2200, 2, true, .00035, 8); }
}
