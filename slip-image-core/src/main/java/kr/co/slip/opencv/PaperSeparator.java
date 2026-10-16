package kr.co.slip.opencv;

import kr.co.slip.api.ProcessingResult;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;
import static kr.co.slip.api.ProcessingResult.*;

/** Separates observed paper surfaces, never reconstructing an occluded part of a sheet. */
final class PaperSeparator {
    record Result(List<Paper> papers, Map<String, byte[]> images, List<String> warnings) {}
    private record ColorModel(double[] center, double[] sigma) {
        double distance(double[] pixel) {
            double result = 0;
            for (int i = 0; i < 3; i++) result += Math.pow((pixel[i] - center[i]) / sigma[i], 2);
            return Math.sqrt(result);
        }
    }
    private record Seam(double slope, double intercept, double support) {
        double y(double x) { return slope * x + intercept; }
    }
    private record Segment(double slope, double intercept, double left, double right) {}
    private static final class Scope implements AutoCloseable {
        private final List<Mat> mats = new ArrayList<>();
        <T extends Mat> T own(T value) { mats.add(value); return value; }
        Mat mat() { return own(new Mat()); }
        Mat kernel(int n) { return own(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(n, n))); }
        @Override public void close() { for (int i = mats.size() - 1; i >= 0; i--) mats.get(i).release(); }
    }

    static Result separate(Mat image, List<Table> tables, boolean knownDocumentQuad) {
        return separate(image, tables, knownDocumentQuad, PixelScale.IDENTITY);
    }

    static Result separate(Mat image, List<Table> tables, boolean knownDocumentQuad, PixelScale pixels) {
        NativeOpenCv.load();
        try (Scope m = new Scope()) {
            Mat mask = knownDocumentQuad ? m.own(new Mat(image.size(), CvType.CV_8UC1, Scalar.all(255)))
                    : foreground(image, tables, pixels, m);
            Mat overlay = m.own(image.clone());
            Map<String, byte[]> images = new LinkedHashMap<>();
            List<Paper> papers = new ArrayList<>();
            Mat usedMask = m.own(Mat.zeros(image.size(), CvType.CV_8UC1));
            List<String> warnings = new ArrayList<>();
            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(m.own(mask.clone()), contours, m.mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            List<MatOfPoint> candidates = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                m.own(contour);
                Rect bounds = Imgproc.boundingRect(contour);
                if (Imgproc.contourArea(contour) >= image.total() * .04 && bounds.width >= image.cols() * .18
                        && bounds.height >= image.rows() * .08) candidates.add(contour);
            }
            candidates.sort(Comparator.comparingInt(c -> Imgproc.boundingRect(c).y));
            int unresolved = 0;
            for (MatOfPoint contour : candidates) {
                Mat component = m.own(Mat.zeros(image.size(), CvType.CV_8UC1));
                Imgproc.drawContours(component, List.of(contour), 0, Scalar.all(255), -1);
                Rect bounds = Imgproc.boundingRect(contour);
                List<Table> supported = tables.stream().filter(t -> inside(component, t.bounds())).sorted(
                        Comparator.comparingInt(t -> t.bounds().y())).toList();
                List<Seam> seams = knownDocumentQuad ? List.of() : seams(image, component, bounds, supported, pixels, m);
                if (supported.size() >= 2 && seams.isEmpty()) unresolved++;
                for (int layer = 0; layer <= seams.size(); layer++) {
                    Mat regionMask = band(component, seams, layer, m);
                    if (Core.countNonZero(regionMask) < image.total() * .025) continue;
                    // Convexify each sheet after splitting. Convexifying the whole stack first
                    // would introduce desk triangles between staggered sheets of different widths.
                    convexify(regionMask, m);
                    Core.bitwise_or(usedMask, regionMask, usedMask);
                    Mat points = m.mat(); Core.findNonZero(regionMask, points);
                    Rect crop = Imgproc.boundingRect(points);
                    int index = papers.size() + 1;
                    Mat cutout = m.mat();
                    Imgproc.cvtColor(m.own(image.submat(crop)), cutout, Imgproc.COLOR_BGR2BGRA);
                    Core.insertChannel(m.own(regionMask.submat(crop)), cutout, 3);
                    images.put(String.format("paper-%02d.png", index), png(cutout, m));
                    List<XY> outline = outline(regionMask, m);
                    MatOfPoint polygon = m.own(new MatOfPoint(outline.stream().map(p -> new Point(p.x(), p.y())).toArray(Point[]::new)));
                    Imgproc.polylines(overlay, List.of(polygon), true, new Scalar(220, 40, 200), 3);
                    Imgproc.putText(overlay, "PAPER " + index, new Point(crop.x + 12, crop.y + 35),
                            Imgproc.FONT_HERSHEY_SIMPLEX, .9, new Scalar(220, 40, 200), 2);
                    papers.add(new Paper(index, new Box(crop.x, crop.y, crop.width, crop.height), outline,
                            knownDocumentQuad ? "DOCUMENT_QUAD" : seams.isEmpty() ? "CONNECTED_PAPER_REGION" : "VISIBLE_EDGE_SPLIT",
                            !knownDocumentQuad, supported.stream().filter(t -> inside(regionMask, t.bounds())).map(Table::index).toList()));
                }
                for (Seam seam : seams) Imgproc.line(overlay, new Point(bounds.x, seam.y(bounds.x)),
                        new Point(bounds.x + bounds.width - 1, seam.y(bounds.x + bounds.width - 1)), new Scalar(255, 180, 0), 3);
            }
            images.put("06-paper-contours.png", png(overlay, m));
            images.put("07-paper-mask.png", png(usedMask, m));
            if (papers.isEmpty()) warnings.add("종이와 배경을 안정적으로 분리하지 못했습니다. 종이 이미지를 임의로 잘라내지 않았습니다.");
            else if (!knownDocumentQuad) warnings.add("종이 분리는 색상·외곽의 볼록 포락선·겹침 경계에 대한 추정입니다. 가려진 내용을 복원하지 않으며 작은 가림 물체나 배경이 포함될 수 있어 paper PNG를 검토하세요.");
            if (unresolved > 0) warnings.add("여러 표가 있지만 종이 사이 경계가 불확실한 영역 " + unresolved + "개는 하나로 유지했습니다. 표 개수는 종이 개수와 같지 않을 수 있습니다.");
            return new Result(List.copyOf(papers), Collections.unmodifiableMap(images), List.copyOf(warnings));
        }
    }

    private static boolean inside(Mat mask, Box box) {
        int x = Math.min(mask.cols() - 1, Math.max(0, box.x() + box.width() / 2));
        int y = Math.min(mask.rows() - 1, Math.max(0, box.y() + box.height() / 2));
        return mask.get(y, x)[0] != 0;
    }

    private static Mat foreground(Mat image, List<Table> tables, PixelScale pixels, Scope m) {
        PixelScale analysis = pixels.capped(Math.max(image.rows(), image.cols()), 900);
        double scale = Math.min(1, 900. / Math.max(image.rows(), image.cols()));
        Mat small = m.mat(), smooth = m.mat(), lab = m.mat();
        Imgproc.resize(image, small, new Size(Math.round(image.cols() * scale), Math.round(image.rows() * scale)), 0, 0, Imgproc.INTER_AREA);
        Imgproc.GaussianBlur(small, smooth, new Size(analysis.odd(9), analysis.odd(9)), 0);
        Imgproc.cvtColor(smooth, lab, Imgproc.COLOR_BGR2Lab);
        List<Rect> seeds = new ArrayList<>();
        for (Table table : tables) {
            Box b = table.bounds();
            int x = Math.max(0, (int) ((b.x() + b.width() * .12) * scale));
            int y = Math.max(0, (int) ((b.y() + b.height() * .12) * scale));
            int right = Math.min(lab.cols(), (int) ((b.x() + b.width() * .88) * scale));
            int bottom = Math.min(lab.rows(), (int) ((b.y() + b.height() * .88) * scale));
            if (right > x + analysis.length(5) && bottom > y + analysis.length(5)) seeds.add(new Rect(x, y, right - x, bottom - y));
        }
        if (seeds.isEmpty()) seeds.add(new Rect(lab.cols() / 3, lab.rows() / 3, lab.cols() / 3, lab.rows() / 3));
        List<ColorModel> models = seeds.stream().map(r -> model(lab, r)).toList();
        Mat labels = m.own(new Mat(lab.size(), CvType.CV_8UC1, Scalar.all(Imgproc.GC_PR_BGD)));
        Mat sureSeed = m.own(Mat.zeros(lab.size(), CvType.CV_8UC1));
        for (Rect seed : seeds) Imgproc.rectangle(sureSeed, seed.tl(), seed.br(), Scalar.all(255), -1);
        int foreground = 0, background = 0, borderBackground = 0;
        for (int y = 0; y < lab.rows(); y++) for (int x = 0; x < lab.cols(); x++) {
            double[] pixel = lab.get(y, x);
            double distance = 100;
            for (ColorModel model : models) distance = Math.min(distance, model.distance(pixel));
            int label = distance < 2.3 ? Imgproc.GC_PR_FGD : Imgproc.GC_PR_BGD;
            if (distance > 4 || ((x < analysis.length(3) || y < analysis.length(3)
                    || x >= lab.cols() - analysis.length(3) || y >= lab.rows() - analysis.length(3)) && distance > 1.8)) {
                label = Imgproc.GC_BGD; background++;
                if (x < analysis.length(8) || y < analysis.length(8)
                        || x >= lab.cols() - analysis.length(8) || y >= lab.rows() - analysis.length(8)) borderBackground++;
            } else if (distance < 1.7 && sureSeed.get(y, x)[0] != 0) {
                label = Imgproc.GC_FGD; foreground++;
            }
            labels.put(y, x, label);
        }
        Mat result = m.own(Mat.zeros(image.size(), CvType.CV_8UC1));
        if (foreground < analysis.area(50) || background < analysis.area(50) || borderBackground < analysis.area(30)) return result;
        Imgproc.grabCut(small, labels, new Rect(), m.mat(), m.mat(), 3, Imgproc.GC_INIT_WITH_MASK);
        Mat sure = m.mat(), probable = m.mat();
        Core.compare(labels, Scalar.all(Imgproc.GC_FGD), sure, Core.CMP_EQ);
        Core.compare(labels, Scalar.all(Imgproc.GC_PR_FGD), probable, Core.CMP_EQ);
        Core.bitwise_or(sure, probable, sure);
        Imgproc.morphologyEx(sure, sure, Imgproc.MORPH_CLOSE, m.kernel(analysis.odd(7)));
        Imgproc.resize(sure, result, image.size(), 0, 0, Imgproc.INTER_NEAREST);
        return result;
    }

    private static ColorModel model(Mat lab, Rect region) {
        List<double[]> samples = new ArrayList<>();
        int step = Math.max(1, Math.min(region.width, region.height) / 80);
        for (int y = region.y; y < region.y + region.height; y += step)
            for (int x = region.x; x < region.x + region.width; x += step) {
                double[] p = lab.get(y, x); if (p[0] > 60) samples.add(p);
            }
        if (samples.isEmpty()) samples.add(lab.get(region.y + region.height / 2, region.x + region.width / 2));
        double[] center = new double[3], sigma = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            int c = channel;
            double[] values = samples.stream().mapToDouble(p -> p[c]).sorted().toArray();
            center[c] = values[values.length / 2];
            double spread = (values[values.length * 3 / 4] - values[values.length / 4]) / 1.349;
            sigma[c] = Math.max(c == 0 ? 12 : 3, Math.min(c == 0 ? 35 : 8, spread));
        }
        return new ColorModel(center, sigma);
    }

    private static List<Seam> seams(Mat image, Mat mask, Rect bounds, List<Table> tables, PixelScale pixels, Scope m) {
        if (tables.size() < 2) return List.of();
        Mat gray = m.mat(), blur = m.mat(), edges = m.mat(), lines = m.mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.GaussianBlur(gray, blur, new Size(pixels.odd(5), pixels.odd(5)), 0);
        Imgproc.Canny(blur, edges, 8, 28);
        Mat smoothColor = m.mat(), lab = m.mat();
        Imgproc.GaussianBlur(image, smoothColor, new Size(pixels.odd(5), pixels.odd(5)), 0);
        Imgproc.cvtColor(smoothColor, lab, Imgproc.COLOR_BGR2Lab);
        for (int c = 1; c <= 2; c++) {
            Mat channel = m.mat(), colorEdges = m.mat();
            Core.extractChannel(lab, channel, c);
            Imgproc.Canny(channel, colorEdges, 3, 10);
            Core.bitwise_or(edges, colorEdges, edges);
        }
        Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 1800, pixels.length(60), bounds.width * .30, pixels.length(35));
        List<Segment> segments = new ArrayList<>();
        for (int j = 0; j < lines.rows(); j++) {
            double[] p = lines.get(j, 0);
            double dx = p[2] - p[0], dy = p[3] - p[1];
            if (Math.abs(dx) < 1 || Math.abs(dy / dx) > .10) continue;
            segments.add(new Segment(dy / dx, p[1] - dy / dx * p[0], Math.min(p[0], p[2]), Math.max(p[0], p[2])));
        }
        List<Seam> result = new ArrayList<>();
        for (int i = 0; i < tables.size() - 1; i++) {
            Box above = tables.get(i).bounds(), below = tables.get(i + 1).bounds();
            double minY = above.y() + above.height() - pixels.length(12), maxY = below.y() - pixels.length(15);
            if (maxY <= minY || below.y() - above.y() < image.rows() * .12) continue;
            Seam best = null;
            double midX = bounds.x + bounds.width / 2.;
            for (Segment segment : segments) {
                double a = segment.slope(), b = segment.intercept(), midY = a * midX + b;
                if (midY < minY || midY > maxY) continue;
                double segmentLeft = segment.left(), segmentRight = segment.right();
                // Weak/curved paper edges often arrive as several collinear Hough fragments.
                for (Segment other : segments) if (Math.abs(other.slope() - a) < .015
                        && Math.abs(other.slope() * midX + other.intercept() - midY) < pixels.length(7)) {
                    segmentLeft = Math.min(segmentLeft, other.left()); segmentRight = Math.max(segmentRight, other.right());
                }
                double dx = segmentRight - segmentLeft;
                if (dx < bounds.width * .72) continue;
                int row = (int) Math.round(midY);
                int left = -1, right = -1;
                if (row < 0 || row >= mask.rows()) continue;
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) if (mask.get(row, x)[0] != 0) {
                    if (left < 0) left = x; right = x;
                }
                if (left < 0) continue;
                double span = right - left + 1;
                if (segmentLeft > left + span * .13 || segmentRight < right - span * .13) continue;
                double support = Math.abs(dx) / span;
                if (best == null || support > best.support()) best = new Seam(a, b, support);
            }
            if (best == null) best = colorSeam(image, mask, bounds, (int) minY, (int) maxY, pixels, m);
            if (best != null && (result.isEmpty() || best.y(bounds.x + bounds.width / 2.) - result.getLast().y(bounds.x + bounds.width / 2.) > pixels.length(40)))
                result.add(best);
        }
        return List.copyOf(result);
    }

    private static Seam colorSeam(Mat image, Mat mask, Rect bounds, int start, int end, PixelScale pixels, Scope m) {
        Mat blurred = m.mat(), lab = m.mat();
        Imgproc.GaussianBlur(image, blurred, new Size(pixels.odd(5), pixels.odd(5)), 0);
        Imgproc.cvtColor(blurred, lab, Imgproc.COLOR_BGR2Lab);
        double best = 2.5;
        int bestY = -1;
        for (int y = Math.max(pixels.length(12), start); y <= Math.min(end, image.rows() - pixels.length(13)); y++) {
            double[] left = colorJump(lab, mask, bounds, y, .03, .09, pixels);
            double[] right = colorJump(lab, mask, bounds, y, .91, .97, pixels);
            double ln = norm(left), rn = norm(right);
            if (ln < 2 || rn < 2) continue;
            double cosine = (left[0] * right[0] + left[1] * right[1] + left[2] * right[2]) / (ln * rn);
            if (cosine < .65) continue;
            double score = Math.min(ln, rn) * cosine;
            if (score > best) { best = score; bestY = y; }
        }
        return bestY < 0 ? null : new Seam(0, bestY, best);
    }

    private static double norm(double[] vector) { return Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]); }

    private static double[] colorJump(Mat lab, Mat mask, Rect bounds, int y, double from, double to, PixelScale pixels) {
        List<double[]> samples = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int x = Math.min(lab.cols() - 1, bounds.x + (int) (bounds.width * (from + (to - from) * i / 8)));
            if (mask.get(y - pixels.length(10), x)[0] == 0 || mask.get(y + pixels.length(10), x)[0] == 0) continue;
            double[] delta = new double[3];
            for (int offset : new int[]{pixels.length(4), pixels.length(7), pixels.length(10)}) {
                double[] above = lab.get(y - offset, x), below = lab.get(y + offset, x);
                for (int c = 0; c < 3; c++) delta[c] += (below[c] - above[c]) / 3.;
            }
            samples.add(delta);
        }
        if (samples.size() < 5) return new double[3];
        double[] result = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            int c = channel;
            double[] values = samples.stream().mapToDouble(p -> p[c]).sorted().toArray();
            result[c] = values[values.length / 2];
        }
        return result;
    }

    private static Mat band(Mat component, List<Seam> seams, int index, Scope m) {
        Mat result = m.own(component.clone());
        byte[] row = new byte[component.cols()];
        for (int y = 0; y < component.rows(); y++) {
            component.get(y, 0, row);
            for (int x = 0; x < row.length; x++) if ((index > 0 && y < seams.get(index - 1).y(x))
                    || (index < seams.size() && y >= seams.get(index).y(x))) row[x] = 0;
            result.put(y, 0, row);
        }
        return result;
    }

    private static void convexify(Mat mask, Scope m) {
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(m.own(mask.clone()), contours, m.mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        List<Point> all = new ArrayList<>();
        for (MatOfPoint contour : contours) { m.own(contour); all.addAll(Arrays.asList(contour.toArray())); }
        if (all.size() < 3) return;
        MatOfPoint points = m.own(new MatOfPoint(all.toArray(Point[]::new)));
        MatOfInt indices = m.own(new MatOfInt());
        Imgproc.convexHull(points, indices);
        Point[] array = points.toArray();
        MatOfPoint hull = m.own(new MatOfPoint(Arrays.stream(indices.toArray()).mapToObj(i -> array[i]).toArray(Point[]::new)));
        mask.setTo(Scalar.all(0));
        Imgproc.fillConvexPoly(mask, hull, Scalar.all(255));
    }

    private static List<XY> outline(Mat mask, Scope m) {
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(m.own(mask.clone()), contours, m.mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        MatOfPoint largest = null;
        for (MatOfPoint c : contours) { m.own(c); if (largest == null || Imgproc.contourArea(c) > Imgproc.contourArea(largest)) largest = c; }
        if (largest == null) return List.of();
        MatOfPoint2f curve = m.own(new MatOfPoint2f(largest.toArray())), approx = m.own(new MatOfPoint2f());
        Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * .001, true);
        return Arrays.stream(approx.toArray()).map(p -> new XY(p.x, p.y)).toList();
    }
    private static byte[] png(Mat image, Scope m) {
        MatOfByte bytes = m.own(new MatOfByte());
        if (!Imgcodecs.imencode(".png", image, bytes)) throw new IllegalStateException("PNG encode failed");
        return bytes.toArray();
    }
}
