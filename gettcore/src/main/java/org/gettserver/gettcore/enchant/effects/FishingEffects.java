package org.gettserver.gettcore.enchant.effects;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.enchant.EnchantConfig;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/** 낚싯대 특수 능력: 손맛(입질 대기 단축), 대어(낚은 아이템 복제), 월척(보물 추가). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class FishingEffects {
    /** 플레이어별 현재 낚시찌와, 그 찌에서 이미 줄인 대기 시간. 서버 스레드 전용. */
    private static final Map<UUID, HookState> LURE = new HashMap<>();

    private static final class HookState {
        final int hookId;
        boolean luredReduced;
        boolean hookedReduced;

        HookState(int hookId) {
            this.hookId = hookId;
        }
    }

    private FishingEffects() {}

    /**
     * 지금 쓰는 낚싯대: 바닐라(FishingHook.shouldStopFishing)처럼 메인핸드가 낚싯대면 메인핸드, 아니면 보조손.
     * 능력은 이 한 스택에서만 읽으므로 양손 낚싯대의 능력이 겹치지 않는다.
     */
    private static ItemStack rod(Player player) {
        ItemStack main = player.getMainHandItem();
        return main.canPerformAction(ItemAbilities.FISHING_ROD_CAST) ? main : player.getOffhandItem();
    }

    private static int rodLevel(Player player, Ability ability) {
        return EffectContext.activeLevel(player, ability, rod(player));
    }

    /** 손맛: 찌마다 입질 대기(timeUntilLured)와 물고기 접근(timeUntilHooked)을 한 번씩 줄인다. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        FishingHook hook = player.fishing;
        if (hook == null || hook.isRemoved()) {
            LURE.remove(player.getUUID());
            return;
        }
        int lvl = rodLevel(player, Ability.LURE);
        if (lvl <= 0) return;
        double pct = GettData.get().enchant().lure().get(lvl);
        if (!(pct > 0)) return;
        double factor = Math.max(0.0, 1.0 - pct / 100.0);

        HookState state = LURE.get(player.getUUID());
        if (state == null || state.hookId != hook.getId()) {
            state = new HookState(hook.getId());
            LURE.put(player.getUUID(), state);
        }
        // Tide 가 있으면 player.fishing 은 껍데기라서 실제 대기 시간은 Tide 찌에 있다
        Object tide = TideHookBridge.hook(player);
        int lured = tide != null ? TideHookBridge.lured(tide) : hook.timeUntilLured;
        if (!state.luredReduced && lured > 0) {
            int reduced = Math.max(1, (int) Math.round(lured * factor));
            if (tide != null) TideHookBridge.setLured(tide, reduced);
            else hook.timeUntilLured = reduced;
            state.luredReduced = true;
        }
        int hooked = tide != null ? TideHookBridge.hooked(tide) : hook.timeUntilHooked;
        if (!state.hookedReduced && hooked > 0) {
            // 둘 다 0이 되면 바닐라가 대기를 새로 뽑으므로 최소 1틱은 남긴다
            int reduced = Math.max(1, (int) Math.round(hooked * factor));
            if (tide != null) TideHookBridge.setHooked(tide, reduced);
            else hook.timeUntilHooked = reduced;
            state.hookedReduced = true;
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LURE.remove(event.getEntity().getUUID());
    }

    /** 대어·월척: 바닐라 낚시 결과가 확정된 뒤(취소되지 않았을 때) 찌 위치에 추가 아이템을 만든다. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFished(ItemFishedEvent event) {
        if (event.isCanceled()) return;
        FishingHook hook = event.getHookEntity();
        if (!(hook.level() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        EnchantConfig cfg = GettData.get().enchant();

        int big = rodLevel(player, Ability.BIGCATCH);
        if (big > 0 && EffectContext.chance(cfg.bigcatch().get(big), level.getRandom())) {
            for (ItemStack stack : event.getDrops()) {
                if (!stack.isEmpty()) spawnAtHook(level, hook, player, stack.copy());
            }
        }

        int treasure = rodLevel(player, Ability.TREASURE);
        if (treasure > 0 && EffectContext.chance(cfg.treasure().get(treasure), level.getRandom())) {
            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, hook.position())
                    .withParameter(LootContextParams.TOOL, rod(player))
                    .withParameter(LootContextParams.THIS_ENTITY, hook)
                    .withLuck(player.getLuck())
                    .create(LootContextParamSets.FISHING);
            LootTable table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING_TREASURE);
            List<ItemStack> loot = table.getRandomItems(params);
            for (ItemStack stack : loot) {
                if (!stack.isEmpty()) spawnAtHook(level, hook, player, stack);
            }
        }
    }

    /** 바닐라 낚시처럼 찌 위치에서 플레이어 쪽으로 던져 준다. */
    private static void spawnAtHook(ServerLevel level, FishingHook hook, Player player, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, hook.getX(), hook.getY(), hook.getZ(), stack);
        double dx = player.getX() - hook.getX();
        double dy = player.getY() - hook.getY();
        double dz = player.getZ() - hook.getZ();
        item.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }
}
