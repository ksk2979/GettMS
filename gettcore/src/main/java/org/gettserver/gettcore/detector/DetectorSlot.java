package org.gettserver.gettcore.detector;

import javax.annotation.Nullable;

/** 커서 스택으로 탐지기의 대상 칸을 우클릭했을 때의 순수 결정 규칙(레지스트리 없이 테스트하려고 (id, count) 로 추상화). */
public final class DetectorSlot {
    private DetectorSlot() {}

    public record Stack(String id, int count) {}

    /** accepted=false 면 아무것도 바뀌지 않고 클릭도 바닐라에 넘긴다. */
    public record Result(boolean accepted, @Nullable Stack newStored, @Nullable Stack cursorAfter, @Nullable Stack giveBack) {}

    public static Result click(@Nullable Stack stored, @Nullable Stack cursor, boolean cursorIsTarget) {
        if (cursor == null) {
            if (stored == null) return new Result(false, null, null, null);
            return new Result(true, null, stored, null);
        }
        // 대상이 아니거나 이미 같은 아이템이 들어 있으면 클릭만 삼킨다.
        if (!cursorIsTarget || (stored != null && stored.id().equals(cursor.id()))) {
            return new Result(true, stored, cursor, null);
        }
        Stack one = new Stack(cursor.id(), 1);
        if (cursor.count() <= 1) return new Result(true, one, stored, null);
        return new Result(true, one, new Stack(cursor.id(), cursor.count() - 1), stored);
    }
}
