package org.gettserver.gettcore.web;

/** 거미줄 판정·물리(순수 함수). 벡터는 {x, y, z} 배열, 거리 단위는 칸, 속도는 칸/틱. */
public final class WebRules {
    /** 공중에서 틱마다 곱해지는 바닐라 감속(가로, 세로). 매달려 있을 때 되돌려 진자가 처지지 않게 한다. */
    public static final double AIR_DRAG_H = 0.91;
    public static final double AIR_DRAG_V = 0.98;

    private WebRules() {}

    /** 줄 끝이 {@code distance}칸을 날아가는 데 걸리는 틱(최소 1). */
    public static int flightTicks(double distance, double blocksPerSecond) {
        double perTick = Math.max(0.05, blocksPerSecond / 20.0);
        return Math.max(1, (int) Math.ceil(distance / perTick));
    }

    /** 날아가는 줄 끝의 진행률 0..1. */
    public static double progress(double ticksSinceStart, int flightTicks) {
        if (flightTicks <= 0) return 1;
        return Math.max(0, Math.min(1, ticksSinceStart / flightTicks));
    }

    /**
     * 당기기(-)·풀기(+) 뒤의 줄 길이. 공중에서는 느슨한 만큼 줄이 감겨({@code distance}까지 줄어) 항상 팽팽하다.
     */
    public static double length(double length, double distance, boolean airborne, boolean reelIn, boolean reelOut,
                                double reelPerTick, double min, double max) {
        double l = length;
        if (airborne && distance < l) l = distance;
        if (reelIn) l -= reelPerTick;
        if (reelOut) l += reelPerTick;
        return Math.max(min, Math.min(max, l));
    }

    /**
     * 줄 제약: 고정점 기준 위치 {@code rel}에서 속도 {@code vel}로 한 틱 움직이면 줄 길이를 넘는 경우, 줄 길이의
     * 구면 위 가장 가까운 점으로 가는 속도를 돌려준다(진자 운동). 넘지 않으면 {@code vel} 그대로.
     */
    public static double[] constrain(double[] rel, double[] vel, double length) {
        double nx = rel[0] + vel[0], ny = rel[1] + vel[1], nz = rel[2] + vel[2];
        double d = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (d <= length || d < 1.0e-6) return vel;
        double s = length / d;
        return new double[]{nx * s - rel[0], ny * s - rel[1], nz * s - rel[2]};
    }

    /** 한 틱 뒤 줄이 팽팽한지(= {@link #constrain}이 속도를 바꾸는지). */
    public static boolean taut(double[] rel, double[] vel, double length) {
        double nx = rel[0] + vel[0], ny = rel[1] + vel[1], nz = rel[2] + vel[2];
        return nx * nx + ny * ny + nz * nz > length * length;
    }

    /** 속도의 크기가 {@code max}를 넘으면 방향은 두고 {@code max}로 줄인다. */
    public static double[] capSpeed(double[] vel, double max) {
        double s = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
        if (s <= max || s < 1.0e-9) return vel;
        double k = max / s;
        return new double[]{vel[0] * k, vel[1] * k, vel[2] * k};
    }

    /**
     * 벽에 붙어 움직이는 속도. (wallX, wallZ)는 플레이어에서 벽 쪽을 가리키는 단위 벡터, {@code up}은 +1 오르기/-1
     * 내리기, {@code left}는 벽을 보고 +1 왼쪽/-1 오른쪽. 벽을 보는 방향 f 의 왼쪽은 (f.z, -f.x).
     */
    public static double[] climb(int wallX, int wallZ, double up, double left, double speed) {
        return new double[]{wallZ * left * speed, up * speed, -wallX * left * speed};
    }

    /** 벽을 차고 뛰는 속도: 벽 반대쪽으로, 위로. */
    public static double[] wallJump(int wallX, int wallZ) {
        return new double[]{-wallX * 0.45, 0.5, -wallZ * 0.45};
    }

    /** 꼭대기를 넘어가는 속도: 위로 뛰면서 벽 쪽으로. */
    public static double[] mantle(int wallX, int wallZ) {
        return new double[]{wallX * 0.2, 0.45, wallZ * 0.2};
    }

    /**
     * 슬링샷 세기 0..1: 누르자마자 {@code minPercent}%에서 시작해 {@code chargeTicks} 동안 100%까지 고르게 찬다.
     */
    public static double slingPower(double ticks, double chargeTicks, double minPercent) {
        double min = Math.max(0, Math.min(1, minPercent / 100.0));
        double t = chargeTicks <= 0 ? 1 : Math.max(0, Math.min(1, ticks / chargeTicks));
        return min + (1 - min) * t;
    }

    /**
     * 한 틱의 공기 저항과 중력을 되돌린 속도: 바닐라는 틱마다 가로에 {@link #AIR_DRAG_H}를 곱하고 세로는
     * (vy - 0.08) × {@link #AIR_DRAG_V} 로 만든다. 슬링샷 직후 이 값으로 덮어써 곧게 날아가게 한다.
     */
    public static double[] undoAirTick(double[] vel) {
        return new double[]{vel[0] / AIR_DRAG_H, vel[1] / AIR_DRAG_V + 0.08, vel[2] / AIR_DRAG_H};
    }

    /** 착지 구르기 회전각(도): {@code ticks}/{@code duration} 동안 0 → 360. 범위를 벗어나면 0. */
    public static float rollDegrees(double ticks, int duration) {
        if (ticks < 0 || ticks >= duration) return 0;
        return (float) (360.0 * ticks / duration);
    }
}
