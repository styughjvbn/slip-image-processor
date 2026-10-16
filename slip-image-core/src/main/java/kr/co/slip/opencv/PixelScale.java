package kr.co.slip.opencv;

/** Spatial thresholds relative to processing the same source with the default size limit. */
record PixelScale(double factor) {
    static final int REFERENCE_DIMENSION = 2200;
    static final PixelScale IDENTITY = new PixelScale(1);

    static PixelScale forSource(int longestSide, int maxDimension) {
        return new PixelScale((double) Math.min(longestSide, maxDimension)
                / Math.min(longestSide, REFERENCE_DIMENSION));
    }

    int length(double reference) { return Math.max(1, (int) Math.round(reference * factor)); }
    int area(double reference) { return Math.max(1, (int) Math.round(reference * factor * factor)); }
    // Keep anchor axes of line kernels one pixel wide.
    int kernel(int reference) { return reference == 1 ? 1 : length(reference); }
    int odd(int reference) {
        int value = Math.max(3, length(reference));
        return value % 2 == 0 ? value + 1 : value;
    }

    /** A capped analysis image has its own pixel scale, independent of the output resolution. */
    PixelScale capped(int currentLongestSide, int cap) {
        return new PixelScale(Math.min(currentLongestSide, cap)
                / Math.min(currentLongestSide / factor, cap));
    }
}
