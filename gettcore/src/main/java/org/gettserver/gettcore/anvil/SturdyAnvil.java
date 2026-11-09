package org.gettserver.gettcore.anvil;

/** 튼튼한 모루 규칙(순수 함수). */
public final class SturdyAnvil {
    private SturdyAnvil() {}

    /** 모루 사용 후 금이 갈 확률: 튼튼한 모루면 0, 아니면 바닐라 값 그대로. */
    public static float breakChance(boolean sturdy, float original) {
        return sturdy ? 0f : original;
    }
}
