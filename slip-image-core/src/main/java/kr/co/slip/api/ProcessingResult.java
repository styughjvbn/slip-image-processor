package kr.co.slip.api;

import java.util.List;
import java.util.Map;

public record ProcessingResult(Report report, Map<String, byte[]> images) {
    public record XY(double x, double y) {}
    public record Box(int x, int y, int width, int height) {}
    public record Row(int index, int top, int bottom, double inkRatio, int inkPixels,
                      boolean removed, String reason) {}
    public record Table(int index, Box bounds, List<Integer> horizontalLines,
                        List<Integer> verticalLines, List<Row> rows, int removedRows) {}
    public record Rotation(String mode, int clockwiseDegrees, double deskewCounterClockwiseDegrees,
                           boolean reviewRequired, String method) {}
    public record Paper(int index, Box bounds, List<XY> contour, String method,
                        boolean reviewRequired, List<Integer> tableIndices) {}
    public record Report(String correction, int sourceWidth, int sourceHeight,
                         int workingWidth, int workingHeight, List<XY> documentCorners,
                         List<String> warnings, List<Table> tables, Rotation rotation, List<Paper> papers) {}
}
