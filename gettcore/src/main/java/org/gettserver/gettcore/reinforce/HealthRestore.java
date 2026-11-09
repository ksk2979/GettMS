package org.gettserver.gettcore.reinforce;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.gettserver.gettcore.GettCore;

/**
 * 강화 방어구 체력 보존(MC-17876 보정). 강화 체력은 아이템 수정자라 저장되지 않아, 접속 시 NBT 의 Health 를 읽는 순간 최대 체력이
 * 기본값(20)이고 체력이 거기로 잘린다. 엔드 귀환(새 플레이어 객체로 체력 복사)도 같다. 그래서 매 틱 체력을 영구 데이터에
 * 적어 두고(로그아웃 이벤트는 싱글 종료·서버 종료 때 저장보다 늦게 와서 못 씀; 엔드 귀환은 원래 개체의 체력), 첫 장비 패스로
 * 최대 체력이 돌아온 뒤 복원한다.
 *
 * <p>또 풀피일 때 방어구를 바꿔 최대 체력이 늘면 현재 체력도 새 최대치로 채운다(조금이라도 깎여 있으면 그대로). 접속·엔드 귀환
 * 직후의 첫 장비 패스는 제외한다(재접속만으로 회복되지 않게; 그때는 위 복원이 맡음).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class HealthRestore {
    static final String TAG = "gettcore_saved_health";
    /** 장비 패스를 기다리는 최대 틱. 그 안에 최대 체력이 저장 값에 못 미치면 그 시점 최대치까지만 복원. */
    static final int MAX_WAIT_TICKS = 20;

    private record Pending(float health, int ticksLeft) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** 이번 틱 방어구 변경 시점에 풀피였던 플레이어 → 틱 끝에서 새 최대치로 채움. */
    private static final Set<UUID> TOP_UP = new HashSet<>();
    /** 접속·엔드 귀환 후 첫 틱이 아직 안 끝난 플레이어(첫 장비 패스는 채우기 제외). */
    private static final Set<UUID> JUST_JOINED = new HashSet<>();

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
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            TOP_UP.remove(player.getUUID());
            JUST_JOINED.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        JUST_JOINED.add(player.getUUID());
        var data = player.getPersistentData();
        if (!data.contains(TAG)) return;
        float saved = data.getFloat(TAG);
        if (saved > player.getHealth()) PENDING.put(player.getUUID(), new Pending(saved, MAX_WAIT_TICKS));
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer player)) return;
        JUST_JOINED.add(player.getUUID());
        float saved = event.getOriginal().getHealth();
        if (saved > player.getHealth()) PENDING.put(player.getUUID(), new Pending(saved, MAX_WAIT_TICKS));
    }

    /** 방어구 칸 변경(수정자 적용 전에 불림): 지금 풀피면 틱 끝에서 채우도록 표시. */
    @SubscribeEvent
    public static void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getSlot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return;
        if (player.isAlive() && player.getHealth() >= player.getMaxHealth()) TOP_UP.add(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Pending p = PENDING.get(player.getUUID());
        if (p != null) {
            if (!player.isAlive()) {
                PENDING.remove(player.getUUID());
            } else {
                float target = decide(p.health(), player.getHealth(), player.getMaxHealth(), p.ticksLeft());
                if (target < 0) {
                    PENDING.put(player.getUUID(), new Pending(p.health(), p.ticksLeft() - 1));
                } else {
                    PENDING.remove(player.getUUID());
                    if (target > player.getHealth()) player.setHealth(target);
                }
            }
        }
        boolean topUp = TOP_UP.remove(player.getUUID());
        boolean joined = JUST_JOINED.remove(player.getUUID());
        if (topUp && !joined && !PENDING.containsKey(player.getUUID()) && player.isAlive()
                && player.getHealth() < player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
        // 다음 저장(자동 저장·종료·로그아웃 어느 것이든)에 실릴 체력. 복원 대기 중엔 잘리기 전 값을 유지.
        Pending still = PENDING.get(player.getUUID());
        float health = still != null ? still.health() : player.getHealth();
        if (player.isAlive()) player.getPersistentData().putFloat(TAG, health);
        else player.getPersistentData().remove(TAG);
    }
}
