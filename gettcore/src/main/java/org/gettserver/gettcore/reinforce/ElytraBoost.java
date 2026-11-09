package org.gettserver.gettcore.reinforce;

/**
 * 겉날개 강화 효과의 순수 계산. 바닐라 활강은 수평 속도에 대해 매 틱 {@code v' = 0.99 (v + a)} 이므로 정상 상태는
 * {@code 99a}. 감쇠를 뺀 이번 틱의 변화량 {@code v' - 0.99 v} 만 percent% 키우면 정상 상태 속도가 정확히 (1 + percent/100)
 * 배가 되고, 틱마다 속도를 곱하는 방식과 달리 쌓여서 끝없이 빨라지지 않는다.
 */
public final class ElytraBoost {
    /** 바닐라 활강의 수평 감쇠(LivingEntity#travel 의 0.99). */
    static final double DRAG = 0.99;

    private ElytraBoost() {}

    /** 활강 한 틱 전후의 수평 속도 → 보정된 수평 속도 {x, z}. */
    public static double[] glide(double beforeX, double beforeZ, double afterX, double afterZ, double percent) {
        double k = Math.max(0, percent) / 100;
        return new double[]{
                afterX + (afterX - DRAG * beforeX) * k,
                afterZ + (afterZ - DRAG * beforeZ) * k};
    }

    /**
     * 폭죽 로켓 한 틱의 추가 가속: 바닐라 가속 {@code look*0.1 + (look*1.5 - v)*0.5} (FireworkRocketEntity#tick) 의
     * percent%. {@code look}·{@code v} 는 {x, y, z}.
     */
    public static double[] fireworkExtra(double[] look, double[] v, double percent) {
        double k = Math.max(0, percent) / 100;
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = (look[i] * 0.1 + (look[i] * 1.5 - v[i]) * 0.5) * k;
        return out;
    }
}
