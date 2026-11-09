package org.gettserver.gettcore.enchant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.Ledger;
import org.gettserver.gettcore.economy.RateLimiter;
import org.gettserver.gettcore.economy.StoneCounter;
import org.gettserver.gettcore.network.EnchantResultPayload;

/** 마법부여 메뉴 열기와 서버측 부여 판정(스펙 §5.6). 클라이언트 패킷은 데이터를 싣지 않는다. */
public final class EnchantService {
    /** One apply per server tick (50 ms) per player. */
    private static final RateLimiter LIMITER = new RateLimiter(50);

    private EnchantService() {}

    /** 부여 가능: 지원 도구 && 부여석 ≥ 1 && 골드 ≥ 비용. */
    public static boolean canApply(boolean supportedTool, int stones, long gold, long goldCost) {
        return supportedTool && stones >= 1 && gold >= goldCost;
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new EnchantMenu(id, inv),
                Component.translatable("gettcore.gui.enchant_title")));
    }

    public static void apply(ServerPlayer player) {
        // (1) the open menu must be an enchant menu
        if (!(player.containerMenu instanceof EnchantMenu menu)) return;
        if (!LIMITER.tryAcquire(player.getUUID(), System.currentTimeMillis())) return;
        // (2) supported tool, a stone in the inventory and enough gold (server config), else ignore
        ItemStack tool = menu.tool();
        Optional<SpecialEnchants.Tool> kind = SpecialEnchants.toolOf(tool);
        UUID id = player.getUUID();
        EconomyData eco = EconomyData.get(player.server);
        long gold = Math.max(0, GettData.get().reinforce().enchantGoldCost());
        List<ItemStack> slots = StoneCounter.slots(player.getInventory());
        if (!canApply(kind.isPresent(), StoneCounter.count(slots, EnchantMenu::isStone), eco.ledger().get(id), gold)) {
            return;
        }
        // (3) consume the gold and one stone; all or nothing
        if (gold > 0 && eco.ledger().withdraw(id, gold) != Ledger.Result.OK) return;
        if (!StoneCounter.consume(slots, EnchantMenu::isStone, 1)) {
            if (gold > 0) eco.ledger().deposit(id, gold);
            return;
        }
        if (gold > 0) eco.changed(id);
        player.getInventory().setChanged();
        // (4) roll
        SpecialEnchants.Rolled rolled = SpecialEnchants.roll(kind.get(),
                GettData.get().enchant().levelWeights(), player.getRandom());
        // (5) drop every special enchant, then add the rolled one (vanilla enchants stay)
        SpecialEnchants.set(tool, rolled, player.registryAccess());
        menu.broadcastChanges();
        // (6) result packet, chat line, log line
        String abilityId = rolled.ability().id();
        int level = rolled.level();
        PacketDistributor.sendToPlayer(player, new EnchantResultPayload(abilityId, level));
        player.sendSystemMessage(Component.translatable("gettcore.msg.enchant_gained",
                Component.translatable("enchantment.gettcore." + abilityId),
                Component.translatable("enchantment.level." + level)).withStyle(ChatFormatting.GREEN));
        GettCore.LOGGER.info("gettcore: {} enchanted {} with {} {} ({} gold)", player.getGameProfile().getName(),
                BuiltInRegistries.ITEM.getKey(tool.getItem()), abilityId, level, gold);
    }

    /** Registered once at startup: /gett reload closes every open enchant menu. */
    public static void registerReloadHook() {
        GettData.addReloadHook(() -> {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.containerMenu instanceof EnchantMenu) {
                        p.closeContainer();
                    }
                }
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: failed closing enchant menus on reload", e);
            }
        });
    }
}
