package org.gettserver.gettcore.shop;

/**
 * 상점 탭 줄의 보이는 범위 계산. 탭 줄 폭은 탭 {@link #SLOTS}칸 고정이고,
 * 넘치면 양 끝에 ◁/▷ 자리를 두고 그 사이에 {@link #OVERFLOW_SLOTS}칸만 보인다.
 */
final class TabStrip {
    static final int SLOTS = 7;
    static final int OVERFLOW_SLOTS = 6;

    private TabStrip() {}

    static boolean overflows(int total) {
        return total > SLOTS;
    }

    static int visibleCount(int total) {
        return overflows(total) ? OVERFLOW_SLOTS : total;
    }

    static int clampOffset(int offset, int total) {
        return Math.max(0, Math.min(offset, total - visibleCount(total)));
    }

    /** 선택한 탭이 보이도록 최소한만 옮긴 offset. */
    static int ensureVisible(int offset, int selected, int total) {
        int visible = visibleCount(total);
        if (selected < offset) offset = selected;
        else if (selected >= offset + visible) offset = selected - visible + 1;
        return clampOffset(offset, total);
    }
}
