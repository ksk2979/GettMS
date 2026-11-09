package org.gettserver.gettcore.reinforce;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.gettserver.gettcore.GettCore;

/**
 * 강화 방어구 체력 보존(MC-17876 보정). 강화 체력은 아이템 수정자라 저장되지 않아, 접속 시 NBT 의 Health 를 읽는 순간 최대 체력이
 * 기본값(20)이고 체력이 거기로 잘린다. 엔드 귀환(새 플레이어 객체로 체력 복사)도 같다. 그래서 로그아웃 때 체력을 영구 데이터에
 * 적어 두고(엔드 귀환은 원래 개체의 체력), 첫 장비 패스로 최대 체력이 돌아온 뒤 복원한다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class HealthRestore {
    static final String TAG = "gettcore_saved_health";
    /** 장비 패스를 기다리는 최대 틱. 그 안에 최대 체력이 저장 값에 못 미치면 그 시점 최대치까지만 복원. */
    static final int MAX_WAIT_TICKS = 20;

    private record Pending(float health, int ticksLeft) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    private HealthRestore() {}

    /**
     * 이번 틱에 복원할 체력, 아직 기다리면 -1. 현재 체력이 이미 저장 값 이상이면(복원할 게 없음) 현재 체력을 돌려준다.
     */
    static float decide(float saved, float current, float max, int ticksLeft) {
        if (current >= saved) return current;
        if (max >= saved) return saved;
        return ticksLeft <= 0 ? max : -1;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // remove() 에서 저장보다 먼저 불린다 → 이 태그가 함께 저장됨.
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            if (player.isAlive()) player.getPersistentData().putFloat(TAG, player.getHealth());
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var data = player.getPersistentData();
        if (!data.contains(TAG)) return;
        float saved = data.getFloat(TAG);
        data.remove(TAG); // 비정상 종료 뒤 옛 값이 다시 쓰이지 않게.
        if (saved > player.getHealth()) PENDING.put(player.getUUID(), new Pending(saved, MAX_WAIT_TICKS));
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer player)) return;
        float saved = event.getOriginal().getHealth();
        if (saved > player.getHealth()) PENDING.put(player.getUUID(), new Pending(saved, MAX_WAIT_TICKS));
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (PENDING.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
        Pending p = PENDING.get(player.getUUID());
        if (p == null) return;
        if (!player.isAlive()) {
            PENDING.remove(player.getUUID());
            return;
        }
        float target = decide(p.health(), player.getHealth(), player.getMaxHealth(), p.ticksLeft());
        if (target < 0) {
            PENDING.put(player.getUUID(), new Pending(p.health(), p.ticksLeft() - 1));
            return;
        }
        PENDING.remove(player.getUUID());
        if (target > player.getHealth()) player.setHealth(target);
    }
}
