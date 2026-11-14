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
        // (4) roll: single abilities and bundles share one weighted pool (armor has no bundles)
        EnchantConfig config = GettData.get().enchant();
        SpecialEnchants.Tool target = kind.get();
        SpecialEnchants.Pick pick = SpecialEnchants.pick(candidates(config, target), levelWeights(config, target),
                player.getRandom());
        List<SpecialEnchants.Rolled> parts = pick.parts();
        // (5) drop every special enchant, then add the rolled ones (vanilla enchants stay)
        SpecialEnchants.set(tool, parts, player.registryAccess());
        menu.broadcastChanges();
        // (6) result packet, chat line, log line
        announce(player, tool, parts, Optional.ofNullable(pick.candidate().bundle()), gold, "enchanted");
    }

    /** The weighted pool of one tool kind: every single ability, then the bundles from {@code pool}. */
    public static List<SpecialEnchants.Candidate> candidates(EnchantConfig config, SpecialEnchants.Tool target) {
        return target.isArmor()
                ? SpecialEnchants.candidates(target, a -> config.armor().weight(target, a), List.of())
                : SpecialEnchants.candidates(target, a -> config.pool().weight(target, a),
                        config.pool().bundles(target));
    }

    private static SpecialEnchants.LevelWeights levelWeights(EnchantConfig config, SpecialEnchants.Tool target) {
        return target.isArmor() ? config.armor().levelWeights() : config.levelWeights();
    }

    private static void announce(ServerPlayer player, ItemStack tool, List<SpecialEnchants.Rolled> parts,
                                 Optional<PoolConfig.Bundle> bundle, long gold, String verb) {
        PacketDistributor.sendToPlayer(player, new EnchantResultPayload(parts.get(0).ability().id(),
                parts.get(0).level()));
        Component shown = BundleNames.display(parts, bundle);
        player.sendSystemMessage(Component.translatable("gettcore.msg.enchant_gained_one", shown)
                .withStyle(ChatFormatting.GREEN));
        GettCore.LOGGER.info("gettcore: {} {} {} with {} ({} gold)", player.getGameProfile().getName(), verb,
                BuiltInRegistries.ITEM.getKey(tool.getItem()), parts, gold);
    }

    /** 레벨만 다시 뽑기 가능: 기능이 켜져 있고, 3레벨 특수 능력이 있고, 부여석과 골드가 비용 이상. */
    public static boolean canReroll(boolean enabled, boolean hasLeveled, int stones, int stoneCost, long gold,
                                    long goldCost) {
        return enabled && hasLeveled && stones >= stoneCost && gold >= goldCost;
    }

    /** "레벨만 다시 뽑기": keeps the abilities on the tool and rolls only their level. Ignored while disabled. */
    public static void rerollLevel(ServerPlayer player) {
        if (!(player.containerMenu instanceof EnchantMenu menu)) return;
        if (!LIMITER.tryAcquire(player.getUUID(), System.currentTimeMillis())) return;
        EnchantConfig config = GettData.get().enchant();
        PoolConfig.LevelReroll rule = config.pool().levelReroll();
        ItemStack tool = menu.tool();
        Optional<SpecialEnchants.Tool> kind = SpecialEnchants.toolOf(tool);
        if (kind.isEmpty()) return;
        List<SpecialEnchants.Rolled> current = SpecialEnchants.all(tool);
        boolean leveled = SpecialEnchants.bundleLevel(current) > 0;
        UUID id = player.getUUID();
        EconomyData eco = EconomyData.get(player.server);
        long gold = Math.max(0, rule.goldCost());
        int stoneCost = Math.max(0, rule.stoneCost());
        List<ItemStack> slots = StoneCounter.slots(player.getInventory());
        if (!canReroll(rule.enabled(), leveled, StoneCounter.count(slots, EnchantMenu::isStone), stoneCost,
                eco.ledger().get(id), gold)) {
            return;
        }
        if (gold > 0 && eco.ledger().withdraw(id, gold) != Ledger.Result.OK) return;
        if (stoneCost > 0 && !StoneCounter.consume(slots, EnchantMenu::isStone, stoneCost)) {
            if (gold > 0) eco.ledger().deposit(id, gold);
            return;
        }
        if (gold > 0) eco.changed(id);
        player.getInventory().setChanged();
        SpecialEnchants.Tool target = kind.get();
        int level = SpecialEnchants.rollLevel(levelWeights(config, target), player.getRandom());
        List<SpecialEnchants.Rolled> parts = SpecialEnchants.withLevel(current, level);
        SpecialEnchants.set(tool, parts, player.registryAccess());
        menu.broadcastChanges();
        announce(player, tool, parts, SpecialEnchants.bundleOf(parts, config.pool().bundles(target)), gold,
                "rerolled the level of");
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
