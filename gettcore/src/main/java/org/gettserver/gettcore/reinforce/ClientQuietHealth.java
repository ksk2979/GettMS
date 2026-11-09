package org.gettserver.gettcore.reinforce;

import net.minecraft.client.Minecraft;

/** 클라이언트 전용: 다음 체력 패킷을 피격 연출 없이 적용(바닐라가 리스폰 직후에 쓰는 flashOnSetHealth 를 끈다). */
public final class ClientQuietHealth {
    private ClientQuietHealth() {}

    public static void suppressNextFlash() {
        var player = Minecraft.getInstance().player;
        if (player != null) player.flashOnSetHealth = false;
    }
}
