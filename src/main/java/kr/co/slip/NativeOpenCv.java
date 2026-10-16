package kr.co.slip;

public final class NativeOpenCv {
    private NativeOpenCv() {}
    private static class Holder {
        static final boolean LOADED = load();
        private static boolean load() {
            nu.pattern.OpenCV.loadLocally();
            return true;
        }
    }
    public static void load() { if (!Holder.LOADED) throw new IllegalStateException("OpenCV load failed"); }
}
