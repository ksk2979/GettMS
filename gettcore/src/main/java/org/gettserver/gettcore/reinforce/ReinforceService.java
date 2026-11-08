package org.gettserver.gettcore.reinforce;

import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Unbreakable;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.RateLimiter;
import org.gettserver.gettcore.network.ReinforceResultPayload;

/** 강화 메뉴 열기와 서버측 강화 판정. 클라이언트 패킷은 데이터를 싣지 않는다. */
public final class ReinforceService {
    /** One apply per server tick (50 ms) per player. */
    private static final RateLimiter LIMITER = new RateLimiter(50);

    private ReinforceService() {}

    /** 강화 가능: 대상 장비 && 최대 단계 미만 && 강화석 ≥ 이번 단계 비용. */
    public static boolean canApply(Optional<ReinforceRules.Target> target, int level, int stones,
                                   ReinforceConfig config) {
        return target.isPresent() && ReinforceRules.canReinforce(level, config)
                && stones >= ReinforceRules.stoneCost(level, config);
    }

    /** 결과 채팅 메시지 키. */
    public static String outcomeMessageKey(boolean success) {
        return success ? "gettcore.msg.reinforce.success" : "gettcore.msg.reinforce.fail";
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ReinforceMenu(id, inv),
                Component.translatable("gettcore.gui.reinforce_title")));
    }

    public static void apply(ServerPlayer player) {
        // (1) the open menu must be a reinforce menu
        if (!(player.containerMenu instanceof ReinforceMenu menu)) return;
        if (!LIMITER.tryAcquire(player.getUUID(), System.currentTimeMillis())) return;
        // (2) re-check with the server config
        ReinforceConfig config = GettData.get().reinforce();
        if (!menu.canApply(config)) return;
        ItemStack gear = menu.gear();
        int level = ReinforceRules.level(gear);
        int cost = ReinforceRules.stoneCost(level, config);
        // (3) stones are consumed whether or not the roll succeeds
        menu.stone().shrink(cost);
        // (4) roll
        boolean success = ReinforceRules.roll(level, config, player.getRandom().nextDouble());
        int newLevel = level;
        if (success) {
            newLevel = level + 1;
            ReinforceRules.setLevel(gear, newLevel);
            gear.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
            gear.remove(DataComponents.DAMAGE);
        }
        menu.broadcastChanges();
        // (5) result packet, chat line, sound, log line
        PacketDistributor.sendToPlayer(player, new ReinforceResultPayload(success, newLevel));
        if (success) {
            player.sendSystemMessage(Component.translatable(outcomeMessageKey(true), newLevel)
                    .withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(Component.translatable(outcomeMessageKey(false), cost)
                    .withStyle(ChatFormatting.RED));
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                success ? SoundEvents.ANVIL_USE : SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.0f, 1.0f);
        GettCore.LOGGER.info("gettcore: {} reinforce {} +{} -> {} ({} stones)", player.getGameProfile().getName(),
                BuiltInRegistries.ITEM.getKey(gear.getItem()), level, success ? "+" + newLevel : "fail", cost);
    }

    /** Registered once at startup: /gett reload closes every open reinforce menu (config sync is in DataEvents). */
    public static void registerReloadHook() {
        GettData.addReloadHook(() -> {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.containerMenu instanceof ReinforceMenu) {
                        p.closeContainer();
                    }
                }
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: failed closing reinforce menus on reload", e);
            }
        });
    }
}
