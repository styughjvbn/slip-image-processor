package kr.co.slip.opencv;

import kr.co.slip.api.ProcessingResult;

import kr.co.slip.api.ProcessingOptions;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import kr.co.slip.api.SlipImageProcessor;

import java.util.*;
import static kr.co.slip.api.ProcessingResult.*;

/** Low-level synchronous engine. Use SlipProcessors.bounded() for shared execution limits. */
public final class SlipProcessor implements SlipImageProcessor {
    public SlipProcessor() { NativeOpenCv.load(); }

    private static final class Mats extends MatScope {
        private PixelScale scale = PixelScale.IDENTITY;
        Mats() {}
        Mats(PixelScale scale) { this.scale = scale; }
        int px(double value) { return scale.length(value); }
        Size window(int value) { int side = scale.odd(value); return new Size(side, side); }
        Mat zeros(int h, int w, int type) { return own(Mat.zeros(h, w, type)); }
        Mat kernel(int w, int h) { return own(Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(scale.kernel(w), scale.kernel(h)))); }
        Mat lineKernel(int w, int h) { return own(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(w, h))); }
    }

    @Override public ProcessingResult process(byte[] input, ProcessingOptions options) {
        if (input == null || input.length == 0 || input.length > 20 * 1024 * 1024)
            throw new IllegalArgumentException("Image must be between 1 byte and 20 MiB");
        Objects.requireNonNull(options, "options");
        try (Mats m = new Mats()) {
            Mat source;
            MatOfByte encoded = new MatOfByte(input);
            try { source = m.own(Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)); }
            finally { encoded.release(); }
            if (source.empty()) throw new IllegalArgumentException("Cannot decode image");
            if ((long) source.rows() * source.cols() > 60_000_000L)
                throw new IllegalArgumentException("Image exceeds 60 megapixels");
            int sw = source.cols(), sh = source.rows();
            m.scale = PixelScale.forSource(Math.max(sw, sh), options.maxDimension());
            double scale = Math.min(1, (double) options.maxDimension() / Math.max(sw, sh));
            Mat work = m.mat();
            Imgproc.resize(source, work, new Size(Math.round(sw * scale), Math.round(sh * scale)), 0, 0, Imgproc.INTER_AREA);
            m.release(source);
            List<String> warnings = new ArrayList<>();
            int preOrientationWidth = work.cols(), preOrientationHeight = work.rows();
            Orientation orientation = orient(work, options.rotationDegrees(), warnings, m);
            if (work != orientation.image()) m.release(work);
            work = orientation.image();
            Point[] quad = documentQuad(work, m);
            String correction;
            List<XY> corners = new ArrayList<>();
            if (quad != null) {
                for (Point p : quad) corners.add(switch (orientation.quarterTurns()) {
                    case 1 -> new XY(p.y / scale, (preOrientationHeight - 1 - p.x) / scale);
                    case 2 -> new XY((preOrientationWidth - 1 - p.x) / scale, (preOrientationHeight - 1 - p.y) / scale);
                    case 3 -> new XY((preOrientationWidth - 1 - p.y) / scale, p.x / scale);
                    default -> new XY(p.x / scale, p.y / scale);
                });
                Mat warped = warp(work, quad, m);
                m.release(work);
                work = warped;
                correction = "DOCUMENT_PERSPECTIVE";
            } else {
                correction = "FRAME_DESKEW";
                warnings.add("문서 네 모서리를 신뢰할 수 없어 전체 사진을 유지합니다. 겹침·잘림 및 표 기반 보정 여부를 검토하세요.");
            }
            double skewAngle = skewAngle(work, m);
            Mat straightened = rotateBound(work, skewAngle, m);
            if (straightened != work) m.release(work);
            work = straightened;
            if (quad == null) {
                Mat corrected = correctGridPerspective(work, m);
                if (corrected != work) {
                    m.release(work);
                    work = corrected;
                    correction = "GRID_PERSPECTIVE";
                    warnings.add("문서 외곽 대신 표의 선 방향에서 원근 변환을 추정했습니다. 사진 전체에 동일한 평면을 가정합니다.");
                }
            }
            Mat gray = m.mat(), binary = m.mat();
            Imgproc.cvtColor(work, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.adaptiveThreshold(gray, binary, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY_INV, m.scale.odd(35), 12);
            Mat horizontal = m.mat(), vertical = m.mat();
            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, m.lineKernel(Math.max(m.px(30), work.cols() / 22), 1));
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, m.lineKernel(1, Math.max(m.px(25), work.rows() / 45)));
            m.release(gray);
            clearBorder(horizontal, m.px(12));
            clearBorder(vertical, m.px(12));
            Mat grid = m.mat();
            Core.bitwise_or(horizontal, vertical, grid);
            List<Rect> regions = tableRegions(horizontal, work.size(), m);
            Mat rowOverlay = options.outputMode().review() ? m.own(work.clone()) : null;
            Map<String, byte[]> images = new LinkedHashMap<>();
            images.put("01-corrected.png", png(work, m));
            if (options.outputMode().diagnostics()) {
                try (Mats drawing = new Mats(m.scale)) {
                    Mat overlay = drawing.own(work.clone());
                    drawing.own(overlay.setTo(new Scalar(0, 0, 255), horizontal));
                    drawing.own(overlay.setTo(new Scalar(0, 180, 0), vertical));
                    images.put("02-lines.png", png(overlay, drawing));
                }
                images.put("03-horizontal.png", png(horizontal, m));
                images.put("04-vertical.png", png(vertical, m));
            }
            List<Table> tables = new ArrayList<>();
            int tableIndex = 0;
            for (Rect region : regions) {
                try (Mats tableScope = new Mats(m.scale)) {
                    Mat hm = tableScope.own(horizontal.submat(region)), vm = tableScope.own(vertical.submat(region));
                    Mat tolerantHorizontal = tableScope.mat(), tolerantVertical = tableScope.mat();
                    Imgproc.dilate(hm, tolerantHorizontal, tableScope.kernel(1, 9));
                    Imgproc.dilate(vm, tolerantVertical, tableScope.kernel(7, 1));
                    List<Integer> ys = projections(tolerantHorizontal, true, .50);
                    List<Integer> xs = projections(tolerantVertical, false, .30);
                    if (ys.size() < 3 || xs.size() < 2) continue;
                    tableIndex++;
                    List<Row> rows = classifyRows(work, binary, grid, region, ys, options, tableScope);
                    List<Mat> kept = new ArrayList<>();
                    int previous = 0;
                    for (Row row : rows) {
                        int a = row.top() - region.y, b = row.bottom() - region.y;
                        if (row.removed()) {
                            if (a > previous) kept.add(tableScope.own(work.submat(new Rect(region.x, region.y + previous, region.width, a - previous))));
                            previous = b;
                            if (rowOverlay != null) Imgproc.rectangle(rowOverlay, new Point(region.x, row.top()),
                                    new Point(region.x + region.width - 1, row.bottom()), new Scalar(0, 0, 255), 2);
                        }
                    }
                    if (previous < region.height) kept.add(tableScope.own(work.submat(new Rect(region.x, region.y + previous, region.width, region.height - previous))));
                    Mat compact = tableScope.mat();
                    Core.vconcat(kept, compact);
                    images.put(String.format("table-%02d-compacted.png", tableIndex), png(compact, tableScope));
                    tables.add(new Table(tableIndex, new Box(region.x, region.y, region.width, region.height),
                            ys.stream().map(y -> y + region.y).toList(), xs.stream().map(x -> x + region.x).toList(),
                            rows, (int) rows.stream().filter(Row::removed).count()));

                }
            }
            if (tables.isEmpty()) warnings.add("안정적인 표 격자를 찾지 못해 빈 행을 제거하지 않았습니다.");
            if (options.ignoreRepeatedPrint()) warnings.add("고정 인쇄 패턴은 5개 이상의 유사 높이 행에서 추정합니다. 흑백 필기·동일 내용 반복·도장·취소선은 검토가 필요합니다.");
            if (rowOverlay != null) {
                images.put("05-row-decisions.png", png(rowOverlay, m));
                m.release(rowOverlay);
            }
            m.release(binary); m.release(horizontal); m.release(vertical); m.release(grid);
            PaperSeparator.Result separated = PaperSeparator.separate(work, tables, quad != null, m.scale, options.outputMode());
            images.putAll(separated.images());
            warnings.addAll(separated.warnings());
            // Keep document layout outside each table; only table crops are physically shortened.
            return new ProcessingResult(new Report(correction, sw, sh, work.cols(), work.rows(),
                    List.copyOf(corners), List.copyOf(warnings), List.copyOf(tables),
                    new Rotation(options.rotationDegrees() == -1 ? "AUTO" : "MANUAL", orientation.quarterTurns() * 90,
                            skewAngle, options.rotationDegrees() == -1, orientation.method()), separated.papers()), Collections.unmodifiableMap(images));
        }
    }

    private static void clearBorder(Mat mask, int border) {
        Imgproc.rectangle(mask, new Point(0, 0), new Point(mask.cols() - 1, mask.rows() - 1), Scalar.all(0), border * 2);
    }

    /** Infer the two families of a planar grid, including when the sheet is clipped. */
    private static Mat correctGridPerspective(Mat image, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            Mat gray = m.mat(), edges = m.mat(), lines = m.mat();
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.adaptiveThreshold(gray, edges, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, m.scale.odd(35), 12);
            Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 3600, m.px(90), image.cols() * .22, m.px(25));
            List<double[]> horizontal = new ArrayList<>(), vertical = new ArrayList<>();
            double cx = image.cols() / 2., cy = image.rows() / 2.;
            for (int i = 0; i < lines.rows(); i++) {
                double[] p = lines.get(i, 0);
                double dx = p[2] - p[0], dy = p[3] - p[1];
                if (Math.abs(dx) > image.cols() * .45 && Math.abs(dy / dx) < .10) {
                    double slope = dy / dx;
                    double y = p[1] + slope * (cx - p[0]);
                    if (y > image.rows() * .1 && y < image.rows() * .95) horizontal.add(new double[]{y, slope});
                }
                if (Math.abs(dy) > image.rows() * .18 && Math.abs(dx / dy) < .10) {
                    double slope = dx / dy;
                    double x = p[0] + slope * (cy - p[1]);
                    if (x > image.cols() * .08 && x < image.cols() * .92) vertical.add(new double[]{x, slope});
                }
            }
            double[] h = fitSlope(horizontal, image.rows() * .25, 6);
            double[] v = fitSlope(vertical, image.cols() * .30, 4);
            if (h == null || v == null) return image;
            double ht = h[1], hb = h[0] * (image.rows() - 1) + h[1];
            double vl = v[1], vr = v[0] * (image.cols() - 1) + v[1];
            if (Math.max(Math.max(Math.abs(ht), Math.abs(hb)), Math.max(Math.abs(vl), Math.abs(vr))) > .12) return image;
            if (Math.abs(ht - hb) + Math.abs(vl - vr) + Math.abs(vl + vr) < .002) return image;
            Point[] q = new Point[]{intersection(cx, 0, ht, 0, cy, vl),
                    intersection(cx, 0, ht, image.cols() - 1, cy, vr),
                    intersection(cx, image.rows() - 1, hb, image.cols() - 1, cy, vr),
                    intersection(cx, image.rows() - 1, hb, 0, cy, vl)};
            Mat transform = m.own(Imgproc.getPerspectiveTransform(m.own(new MatOfPoint2f(q)), m.own(new MatOfPoint2f(
                    new Point(0, 0), new Point(image.cols() - 1, 0), new Point(image.cols() - 1, image.rows() - 1), new Point(0, image.rows() - 1)))));
            MatOfPoint2f frame = m.own(new MatOfPoint2f(new Point(0, 0), new Point(image.cols() - 1, 0),
                    new Point(image.cols() - 1, image.rows() - 1), new Point(0, image.rows() - 1)));
            MatOfPoint2f mapped = m.own(new MatOfPoint2f());
            Core.perspectiveTransform(frame, mapped, transform);
            Point[] points = mapped.toArray();
            double minX = Arrays.stream(points).mapToDouble(p -> p.x).min().orElse(0);
            double minY = Arrays.stream(points).mapToDouble(p -> p.y).min().orElse(0);
            double maxX = Arrays.stream(points).mapToDouble(p -> p.x).max().orElse(image.cols());
            double maxY = Arrays.stream(points).mapToDouble(p -> p.y).max().orElse(image.rows());
            if (!Double.isFinite(maxX + maxY + minX + minY) || maxX - minX > image.cols() * 1.5 || maxY - minY > image.rows() * 1.5) return image;
            Mat translation = m.own(Mat.eye(3, 3, CvType.CV_64F)), shifted = m.mat();
            translation.put(0, 2, -minX); translation.put(1, 2, -minY);
            Core.gemm(translation, transform, 1, m.mat(), 0, shifted);
            Mat result = parent.mat();
            Imgproc.warpPerspective(image, result, shifted, new Size(Math.ceil(maxX - minX + 1), Math.ceil(maxY - minY + 1)),
                    Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar.all(255));
            return result;

        }
    }

    private static Point intersection(double hx, double hy, double hs, double vx, double vy, double vs) {
        double y = (hy + hs * (vx - vs * vy - hx)) / (1 - hs * vs);
        return new Point(vx + vs * (y - vy), y);
    }

    private static double[] fitSlope(List<double[]> data, double minimumSpan, int minimumCount) {
        if (data.size() < minimumCount) return null;
        // Theil-Sen fit resists text strokes and duplicate Hough segments.
        List<Double> slopes = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) for (int j = i + 1; j < data.size(); j++) {
            double dx = data.get(j)[0] - data.get(i)[0];
            if (Math.abs(dx) >= minimumSpan) slopes.add((data.get(j)[1] - data.get(i)[1]) / dx);
        }
        if (slopes.isEmpty()) return null;
        Collections.sort(slopes);
        double slope = slopes.get(slopes.size() / 2);
        List<Double> intercepts = data.stream().map(p -> p[1] - slope * p[0]).sorted().toList();
        double intercept = intercepts.get(intercepts.size() / 2);
        long inliers = data.stream().filter(p -> Math.abs(p[1] - (slope * p[0] + intercept)) < .008).count();
        return inliers >= minimumCount && inliers >= data.size() * .65 ? new double[]{slope, intercept} : null;
    }

    private record Orientation(Mat image, int quarterTurns, String method) {}

    private static Orientation orient(Mat image, int requestedDegrees, List<String> warnings, Mats parent) {
        if (requestedDegrees != -1) return new Orientation(quarterTurn(image, requestedDegrees / 90, parent),
                requestedDegrees / 90, "USER_SPECIFIED");
        try (Mats m = new Mats(parent.scale)) {
            // Use a straightened probe for direction analysis. Source coordinates remain unaffected.
            Mat probe = rotateBound(image, skewAngle(image, m), m);
            Mat gray = m.mat(), edges = m.mat(), lines = m.mat();
            Imgproc.cvtColor(probe, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.adaptiveThreshold(gray, edges, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, m.scale.odd(35), 12);
            Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180, m.px(80), Math.min(probe.rows(), probe.cols()) * .22, m.px(20));
            double horizontal = 0, vertical = 0;
            for (int i = 0; i < lines.rows(); i++) {
                double[] p = lines.get(i, 0);
                double dx = Math.abs(p[2] - p[0]), dy = Math.abs(p[3] - p[1]);
                if (dx > dy * 10) horizontal += dx;
                if (dy > dx * 10) vertical += dy;
            }
            int turns = vertical > horizontal * 1.05 ? 3 : 0;
            Mat aligned = quarterTurn(probe, turns, m);
            double[] scores = headerInkScores(aligned, m);
            boolean flip = scores[1] > scores[0] * 1.4 && scores[1] - scores[0] > .005;
            if (flip) turns = (turns + 2) % 4;
            warnings.add("표 선과 헤더의 인쇄 분포로 시계 방향 " + turns * 90
                    + "도 회전을 추정했습니다. OCR 없이 상하 방향은 확정할 수 없어 검토가 필요합니다. rotationDegrees로 직접 지정할 수 있습니다.");
            return new Orientation(quarterTurn(image, turns, parent), turns,
                    scores[0] + scores[1] > .005 ? "GRID_AND_HEADER_INK" : "GRID_ONLY");
        }
    }

    private static Mat quarterTurn(Mat image, int turns, Mats m) {
        if (turns == 0) return image;
        Mat result = m.mat();
        Core.rotate(image, result, switch (turns) {
            case 1 -> Core.ROTATE_90_CLOCKWISE;
            case 2 -> Core.ROTATE_180;
            case 3 -> Core.ROTATE_90_COUNTERCLOCKWISE;
            default -> throw new IllegalArgumentException("Invalid quarter turn");
        });
        return result;
    }

    private static double[] headerInkScores(Mat image, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            Mat gray = m.mat(), binary = m.mat(), horizontal = m.mat(), vertical = m.mat(), grid = m.mat(), ink = m.mat();
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.adaptiveThreshold(gray, binary, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, m.scale.odd(35), 12);
            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, m.lineKernel(Math.max(m.px(30), image.cols() / 22), 1));
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, m.lineKernel(1, Math.max(m.px(25), image.rows() / 45)));
            clearBorder(horizontal, m.px(12)); clearBorder(vertical, m.px(12));
            Core.bitwise_or(horizontal, vertical, grid);
            Imgproc.dilate(grid, grid, m.kernel(5, 5));
            Imgproc.threshold(gray, ink, 110, 255, Imgproc.THRESH_BINARY_INV);
            Core.subtract(ink, grid, ink);
            double top = 0, bottom = 0;
            int count = 0;
            for (Rect region : tableRegions(horizontal, image.size(), m)) {
                Mat tolerant = m.mat();
                Imgproc.dilate(m.own(horizontal.submat(region)), tolerant, m.kernel(1, 9));
                List<Integer> ys = projections(tolerant, true, .50);
                if (ys.size() < 5) continue;
                int width = Math.max(1, region.width - 2 * m.px(6));
                int topA = region.y + ys.get(0) + m.px(5), topB = region.y + ys.get(2) - m.px(5);
                int bottomA = region.y + ys.get(ys.size() - 3) + m.px(5), bottomB = region.y + ys.getLast() - m.px(5);
                if (topB <= topA || bottomB <= bottomA) continue;
                Mat topInk = m.own(ink.submat(new Rect(region.x + m.px(6), topA, width, topB - topA)));
                Mat bottomInk = m.own(ink.submat(new Rect(region.x + m.px(6), bottomA, width, bottomB - bottomA)));
                top += Core.countNonZero(topInk) / (double) topInk.total();
                bottom += Core.countNonZero(bottomInk) / (double) bottomInk.total();
                count++;
            }
            return count == 0 ? new double[]{0, 0} : new double[]{top / count, bottom / count};

        }
    }

    private static Point[] documentQuad(Mat image, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            Mat gray = m.mat(), blur = m.mat(), edges = m.mat();
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, blur, m.window(7), 0);
            Imgproc.Canny(blur, edges, 35, 100);
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, m.kernel(7, 7));
            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(edges, contours, m.mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            Point[] best = null;
            double bestArea = image.total() * .45;
            for (MatOfPoint contour : contours) {
                m.own(contour);
                double area = Math.abs(Imgproc.contourArea(contour));
                if (area < bestArea) continue;
                MatOfPoint2f curve = m.own(new MatOfPoint2f(contour.toArray())), approx = m.own(new MatOfPoint2f());
                Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * .02, true);
                if (approx.total() != 4) continue;
                Point[] points = approx.toArray();
                MatOfPoint polygon = m.own(new MatOfPoint(points));
                if (!Imgproc.isContourConvex(polygon)) continue;
                // A contour touching the frame may be the photo boundary or a clipped table.
                boolean touches = Arrays.stream(points).anyMatch(p -> p.x < m.px(5) || p.y < m.px(5) || p.x > image.cols() - m.px(6) || p.y > image.rows() - m.px(6));
                if (touches) continue;
                if (!hasPaperContrast(image, points, m)) continue;
                Point[] ordered = order(points, m.px(80));
                if (ordered == null) continue;
                best = ordered;
                bestArea = area;
            }
            return best;

        }
    }

    private static boolean hasPaperContrast(Mat image, Point[] points, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            Mat mask = m.zeros(image.rows(), image.cols(), CvType.CV_8UC1);
            Imgproc.fillConvexPoly(mask, m.own(new MatOfPoint(points)), Scalar.all(255));
            Mat innerNear = m.mat(), innerFar = m.mat(), outerNear = m.mat(), outerFar = m.mat();
            Imgproc.erode(mask, innerNear, m.kernel(17, 17));
            Imgproc.erode(mask, innerFar, m.kernel(35, 35));
            Imgproc.dilate(mask, outerNear, m.kernel(17, 17));
            Imgproc.dilate(mask, outerFar, m.kernel(35, 35));
            Core.subtract(innerNear, innerFar, innerNear);
            Core.subtract(outerFar, outerNear, outerFar);
            // Thin table strokes can dominate a small ring after downsampling. Median color
            // prevents mistaking a printed table border on white paper for the paper outline.
            double[] inside = medianColor(image, innerNear), outside = medianColor(image, outerFar);
            double contrast = 0;
            for (int i = 0; i < 3; i++) contrast += Math.pow(inside[i] - outside[i], 2);
            return Math.sqrt(contrast) > 20;

        }
    }

    private static double[] medianColor(Mat image, Mat mask) {
        int[][] histogram = new int[3][256];
        byte[] pixels = new byte[image.cols() * 3], selected = new byte[image.cols()];
        int count = 0;
        for (int y = 0; y < image.rows(); y++) {
            image.get(y, 0, pixels); mask.get(y, 0, selected);
            for (int x = 0; x < image.cols(); x++) if (selected[x] != 0) {
                count++;
                for (int c = 0; c < 3; c++) histogram[c][pixels[x * 3 + c] & 255]++;
            }
        }
        double[] result = new double[3];
        for (int c = 0; c < 3; c++) {
            int cumulative = 0;
            for (int value = 0; value < 256; value++) {
                cumulative += histogram[c][value];
                if (cumulative > count / 2) { result[c] = value; break; }
            }
        }
        return result;
    }

    private static Point[] order(Point[] points, int minimumSide) {
        Point center = new Point(Arrays.stream(points).mapToDouble(p -> p.x).average().orElseThrow(),
                Arrays.stream(points).mapToDouble(p -> p.y).average().orElseThrow());
        Point[] sorted = points.clone();
        Arrays.sort(sorted, Comparator.comparingDouble(p -> Math.atan2(p.y - center.y, p.x - center.x)));
        int start = 0;
        for (int i = 1; i < 4; i++) if (sorted[i].x + sorted[i].y < sorted[start].x + sorted[start].y) start = i;
        Point[] result = new Point[4];
        for (int i = 0; i < 4; i++) result[i] = sorted[(start + i) % 4];
        for (int i = 0; i < 4; i++) if (distance(result[i], result[(i + 1) % 4]) < minimumSide) return null;
        return result;
    }

    private static double distance(Point a, Point b) { return Math.hypot(a.x - b.x, a.y - b.y); }

    private static Mat warp(Mat src, Point[] q, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            int width = (int) Math.round(Math.max(distance(q[0], q[1]), distance(q[3], q[2])));
            int height = (int) Math.round(Math.max(distance(q[0], q[3]), distance(q[1], q[2])));
            Mat transform = m.own(Imgproc.getPerspectiveTransform(m.own(new MatOfPoint2f(q)),
                    m.own(new MatOfPoint2f(new Point(0, 0), new Point(width - 1, 0), new Point(width - 1, height - 1), new Point(0, height - 1)))));
            Mat dst = parent.mat();
            Imgproc.warpPerspective(src, dst, transform, new Size(width, height), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
            return dst;

        }
    }

    private static double skewAngle(Mat image, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            Mat gray = m.mat(), edges = m.mat(), lines = m.mat();
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.adaptiveThreshold(gray, edges, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, m.scale.odd(35), 12);
            Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 1800, m.px(80), Math.min(image.cols(), image.rows()) * .22, m.px(25));
            List<double[]> angles = new ArrayList<>();
            double[] histogram = new double[90];
            double total = 0;
            for (int i = 0; i < lines.rows(); i++) {
                double[] p = lines.get(i, 0);
                double angle = Math.toDegrees(Math.atan2(p[3] - p[1], p[2] - p[0]));
                angle = ((angle + 225) % 90) - 45;
                double weight = Math.hypot(p[2] - p[0], p[3] - p[1]);
                angles.add(new double[]{angle, weight});
                histogram[Math.min(89, (int) Math.floor(angle + 45))] += weight;
                total += weight;
            }
            if (angles.size() < 3) return 0;
            int peak = 0;
            double best = 0;
            for (int i = 0; i < 90; i++) {
                double weight = 0;
                for (int j = -2; j <= 2; j++) weight += histogram[(i + j + 90) % 90];
                if (weight > best) { best = weight; peak = i; }
            }
            if (best < total * .4) return 0;
            double center = peak - 44.5;
            List<double[]> inliers = new ArrayList<>();
            for (double[] sample : angles) {
                double delta = sample[0] - center;
                if (delta > 45) delta -= 90;
                if (delta < -45) delta += 90;
                if (Math.abs(delta) <= 2.5) inliers.add(new double[]{center + delta, sample[1]});
            }
            if (inliers.size() < 3) return 0;
            inliers.sort(Comparator.comparingDouble(p -> p[0]));
            double half = inliers.stream().mapToDouble(p -> p[1]).sum() / 2, sum = 0;
            for (double[] sample : inliers) {
                sum += sample[1];
                if (sum >= half) return Math.abs(sample[0]) < .08 ? 0 : sample[0];
            }
            return 0;

        }
    }

    private static Mat rotateBound(Mat image, double angle, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            if (angle == 0) return image;
            Mat rotation = m.own(Imgproc.getRotationMatrix2D(new Point(image.cols() / 2., image.rows() / 2.), angle, 1));
            double c = Math.abs(rotation.get(0, 0)[0]), s = Math.abs(rotation.get(0, 1)[0]);
            int width = (int) Math.ceil(image.rows() * s + image.cols() * c);
            int height = (int) Math.ceil(image.rows() * c + image.cols() * s);
            rotation.put(0, 2, rotation.get(0, 2)[0] + (width - image.cols()) / 2.);
            rotation.put(1, 2, rotation.get(1, 2)[0] + (height - image.rows()) / 2.);
            Mat dst = parent.mat();
            Imgproc.warpAffine(image, dst, rotation, new Size(width, height), Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar.all(255));
            return dst;

        }
    }

    private static List<Rect> tableRegions(Mat mask, Size size, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(m.own(mask.clone()), contours, m.mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            List<Rect> segments = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                m.own(contour);
                Rect r = Imgproc.boundingRect(contour);
                if (r.width > size.width * .30 && r.height < size.height * .04
                        && r.y > size.height * .035 && r.y + r.height < size.height * .985) segments.add(r);
            }
            segments.sort(Comparator.comparingInt(r -> r.y));
            List<List<Rect>> groups = new ArrayList<>();
            for (Rect r : segments) {
                List<Rect> match = null;
                for (List<Rect> group : groups) {
                    Rect last = group.getLast();
                    if (r.y - last.y < size.height * .085 && Math.abs(r.x - last.x) < size.width * .055
                            && Math.abs(r.x + r.width - last.x - last.width) < size.width * .055) {
                        match = group; break;
                    }
                }
                if (match == null) { match = new ArrayList<>(); groups.add(match); }
                match.add(r);
            }
            List<Rect> result = new ArrayList<>();
            for (List<Rect> group : groups) {
                if (group.size() < 3) continue;
                int left = group.stream().mapToInt(r -> r.x).min().orElseThrow();
                int right = group.stream().mapToInt(r -> r.x + r.width).max().orElseThrow();
                int top = group.getFirst().y, bottom = group.getLast().y + group.getLast().height;
                if (bottom - top < size.height * .045) continue;
                result.add(new Rect(left, top, right - left, bottom - top));
            }
            result.sort(Comparator.comparingInt(r -> r.y));
            return result;

        }
    }

    private static List<Integer> projections(Mat mask, boolean horizontal, double minimumCoverage) {
        int length = horizontal ? mask.rows() : mask.cols(), cross = horizontal ? mask.cols() : mask.rows();
        byte[] pixels = new byte[(int) mask.total()];
        mask.get(0, 0, pixels);
        List<Integer> peaks = new ArrayList<>();
        int start = -1, weight = 0, weighted = 0;
        for (int i = 0; i <= length; i++) {
            int count = 0;
            if (i < length) for (int j = 0; j < cross; j++) {
                int index = horizontal ? i * mask.cols() + j : j * mask.cols() + i;
                if (pixels[index] != 0) count++;
            }
            if (count >= cross * minimumCoverage) {
                if (start < 0) start = i;
                weight += count; weighted += i * count;
            } else if (start >= 0) {
                peaks.add((int) Math.round((double) weighted / weight));
                start = -1; weight = 0; weighted = 0;
            }
        }
        return peaks;
    }

    private static List<Row> classifyRows(Mat image, Mat binary, Mat grid, Rect region,
                                           List<Integer> ys, ProcessingOptions options, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            Mat expandedGrid = m.mat(), content = m.mat(), hsv = m.mat(), blue = m.mat();
            Imgproc.dilate(grid, expandedGrid, m.kernel(5, 5));
            Core.subtract(binary, expandedGrid, content);
            Imgproc.cvtColor(image, hsv, Imgproc.COLOR_BGR2HSV);
            Core.inRange(hsv, new Scalar(90, 48, 30), new Scalar(140, 255, 245), blue);
            // Blue handwriting is evidence even when its shape coincides with a repeated printed label.
            Core.subtract(blue, expandedGrid, blue);
            int normalizedHeight = m.px(64);
            List<Mat> normalized = new ArrayList<>(), colored = new ArrayList<>();
            List<Integer> heights = new ArrayList<>();
            for (int i = 0; i < ys.size() - 1; i++) heights.add(ys.get(i + 1) - ys.get(i));
            List<Integer> sortedHeights = new ArrayList<>(heights);
            Collections.sort(sortedHeights);
            int typical = sortedHeights.get(sortedHeights.size() / 2);
            for (int i = 0; i < heights.size(); i++) {
                int inset = Math.min(m.px(5), Math.max(m.px(2), heights.get(i) / 8));
                Rect r = new Rect(region.x + m.px(4), region.y + ys.get(i) + inset,
                        Math.max(1, region.width - 2 * m.px(4)), Math.max(1, heights.get(i) - 2 * inset));
                Mat n = m.mat(), c = m.mat();
                Imgproc.resize(m.own(content.submat(r)), n, new Size(region.width - 2 * m.px(4), normalizedHeight), 0, 0, Imgproc.INTER_NEAREST);
                Imgproc.resize(m.own(blue.submat(r)), c, n.size(), 0, 0, Imgproc.INTER_NEAREST);
                normalized.add(n); colored.add(c);
            }
            Mat template = m.zeros(normalizedHeight, region.width - 2 * m.px(4), CvType.CV_8UC1);
            List<Integer> eligible = new ArrayList<>();
            for (int i = options.protectedTopRows(); i < heights.size(); i++)
                if (Math.abs(heights.get(i) - typical) <= typical * .20) eligible.add(i);
            if (options.ignoreRepeatedPrint() && eligible.size() >= 5) {
                Mat sum = m.zeros(template.rows(), template.cols(), CvType.CV_32FC1);
                for (int index : eligible) {
                    Mat padded = m.mat(), value = m.mat();
                    // Retain a one-pixel matching radius when downsampling introduces rounding jitter.
                    Imgproc.dilate(normalized.get(index), padded, m.lineKernel(m.scale.odd(3), m.scale.odd(5)));
                    padded.convertTo(value, CvType.CV_32FC1, 1. / 255);
                    Core.add(sum, value, sum);
                }
                Core.compare(sum, Scalar.all(Math.ceil(eligible.size() * .70)), template, Core.CMP_GE);
            }
            List<Row> result = new ArrayList<>();
            for (int i = 0; i < normalized.size(); i++) {
                Mat residual = m.mat();
                Core.subtract(normalized.get(i), template, residual);
                Core.bitwise_or(residual, colored.get(i), residual);
                int ink = significantPixels(residual, m.scale.area(options.minimumComponentArea()), m);
                double ratio = ink / (double) residual.total();
                boolean protectedRow = i < options.protectedTopRows() || heights.get(i) < typical * .65 || heights.get(i) > typical * 1.35;
                boolean empty = !protectedRow && ratio < options.minimumInkRatio();
                result.add(new Row(i, region.y + ys.get(i), region.y + ys.get(i + 1), ratio, ink, empty,
                        protectedRow ? "PROTECTED_HEADER_OR_MERGED_ROW" : empty ? "NO_SIGNIFICANT_INK" : "CONTENT_PRESENT"));
            }
            return List.copyOf(result);

        }
    }

    private static int significantPixels(Mat binary, int minimumArea, Mats parent) {
        try (Mats m = new Mats(parent.scale)) {
            Mat labels = m.mat(), stats = m.mat(), centroids = m.mat();
            int count = Imgproc.connectedComponentsWithStats(binary, labels, stats, centroids);
            int pixels = 0;
            for (int i = 1; i < count; i++) {
                int area = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
                int width = (int) stats.get(i, Imgproc.CC_STAT_WIDTH)[0];
                int height = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
                // Narrow residual grid fragments must not make every row nonempty.
                if (area >= minimumArea && width >= m.px(2) && height >= m.px(3)) pixels += area;
            }
            return pixels;

        }
    }

    private static byte[] png(Mat image, Mats m) {
        MatOfByte bytes = new MatOfByte();
        try {
            if (!Imgcodecs.imencode(".png", image, bytes)) throw new IllegalStateException("PNG encode failed");
            return bytes.toArray();
        } finally { bytes.release(); }
    }
}
