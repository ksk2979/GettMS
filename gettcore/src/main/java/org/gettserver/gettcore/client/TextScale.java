package org.gettserver.gettcore.client;

/**
 * 글자 배율 보정(순수 함수): 배율 × GUI 배율이 정수가 되도록 맞춰 픽셀 글꼴이 뭉개지지 않게 한다(예: GUI 2 에서 0.75 → 1,
 * 1.25 → 1.5). 화면에 최소 1 픽셀은 남긴다.
 */
public final class TextScale {
    private TextScale() {}

    /** 가장 가까운 정수 화면 픽셀 배율. */
    public static float snap(float target, double guiScale) {
        double gs = Math.max(1, guiScale);
        return (float) (Math.max(1, Math.round(target * gs)) / gs);
    }

    /** {@code limit} 을 넘지 않는 정수 화면 픽셀 배율(폭 맞춤용). */
    public static float snapDown(float limit, double guiScale) {
        double gs = Math.max(1, guiScale);
        return (float) (Math.max(1, Math.floor(limit * gs)) / gs);
    }
}
