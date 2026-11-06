package org.gettserver.gettcore.enchant.effects;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.gettserver.gettcore.enchant.AbilityToggle;
import org.gettserver.gettcore.enchant.SpecialEnchants;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/** 특수 능력 효과 공통: 발동 레벨 판정, 추가 파괴(재귀 방지), 확률, 블록 BFS. */
public final class EffectContext {
    /** 추가 파괴 중이면 true. 서버 스레드 전용. */
    private static boolean REENTRANT = false;

    private EffectContext() {}

    /** 메인핸드 도구 기준 발동 레벨. 조건이 안 맞으면 0. */
    public static int activeLevel(Player player, Ability ability) {
        return activeLevel(player, ability, player.getMainHandItem());
    }

    /**
     * {@code tool} 기준 발동 레벨. 진짜 {@link ServerPlayer}(FakePlayer 제외)이고, 도구 종류가 능력과 맞고,
     * 블록 파괴 능력이면 토글이 켜져 있을 때만 인챈트 레벨을 돌려준다.
     */
    public static int activeLevel(Player player, Ability ability, ItemStack tool) {
        if (!(player instanceof ServerPlayer sp) || player instanceof FakePlayer) return 0;
        if (!SpecialEnchants.toolOf(tool).equals(Optional.of(ability.tool()))) return 0;
        if (isBlockAbility(ability) && !AbilityToggle.enabled(sp)) return 0;
        return SpecialEnchants.level(tool, ability, sp.registryAccess());
    }

    public static boolean isBlockAbility(Ability ability) {
        return switch (ability) {
            case HARVEST, VEIN, AREA, TIMBER -> true;
            default -> false;
        };
    }

    /**
     * 플레이어가 직접 부순 것처럼 {@code pos}를 파괴한다(보호 구역의 BreakEvent 취소가 그대로 적용된다).
     * 메인핸드 도구가 없거나 남은 내구도가 1 이하이면, 또는 파괴에 실패하면 false.
     */
    public static boolean breakExtra(ServerPlayer player, BlockPos pos) {
        if (!toolUsable(player)) return false;
        boolean prev = REENTRANT;
        REENTRANT = true;
        try {
            return player.gameMode.destroyBlock(pos);
        } finally {
            REENTRANT = prev; // 중첩 호출에서도 바깥 호출의 상태를 지킨다
        }
    }

    /** 메인핸드 도구가 있고 남은 내구도가 2 이상(또는 내구도 없음)이면 true. */
    public static boolean toolUsable(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return false;
        return !stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 1;
    }

    /** BreakEvent 핸들러 첫 줄에서 검사: 추가 파괴로 생긴 이벤트면 다시 퍼뜨리지 않는다. */
    public static boolean inReentrant() {
        return REENTRANT;
    }

    /** {@code percent}% 확률. 0 이하는 항상 false, 100 이상은 항상 true. */
    public static boolean chance(double percent, RandomSource random) {
        if (!(percent > 0)) return false;
        if (percent >= 100) return true;
        return random.nextDouble() * 100.0 < percent;
    }

    /**
     * {@code start}에서 {@code same}을 만족하는 블록으로 이어진 위치를 너비 우선으로 찾는다.
     * 시작 위치는 빼고 최대 {@code max}개. {@code diagonal}이면 26방향, 아니면 6방향.
     */
    public static List<BlockPos> bfs(Predicate<BlockPos> same, BlockPos start, int max, boolean diagonal) {
        List<BlockPos> found = new ArrayList<>();
        if (max <= 0) return found;
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos origin = start.immutable();
        seen.add(origin);
        queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (manhattan == 0 || (!diagonal && manhattan != 1)) continue;
                        BlockPos next = cur.offset(dx, dy, dz);
                        if (!seen.add(next) || !same.test(next)) continue;
                        found.add(next);
                        if (found.size() >= max) return found;
                        queue.add(next);
                    }
                }
            }
        }
        return found;
    }
}
