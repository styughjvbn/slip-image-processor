package kr.co.slip.opencv;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;

/** Owns wrappers as well as pixel references, including Mat-returning mutators such as setTo. */
class MatScope implements AutoCloseable {
    private final List<Mat> owned = new ArrayList<>();
    <T extends Mat> T own(T mat) { owned.add(mat); return mat; }
    Mat mat() { return own(new Mat()); }
    void release(Mat mat) {
        for (int i = owned.size() - 1; i >= 0; i--) if (owned.get(i) == mat) {
            owned.remove(i).release(); return;
        }
        throw new IllegalArgumentException("Mat is not owned by this scope");
    }
    @Override public void close() {
        for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).release();
        owned.clear();
    }
}
