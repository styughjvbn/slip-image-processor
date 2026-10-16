package kr.co.slip.api;

/** Select before processing so unused overlays and PNGs are never allocated. */
public enum OutputMode {
    PRODUCTION, REVIEW, ALL;
    public boolean review() { return this != PRODUCTION; }
    public boolean diagnostics() { return this == ALL; }
}
