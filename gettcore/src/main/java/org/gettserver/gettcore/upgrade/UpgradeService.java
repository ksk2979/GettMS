package org.gettserver.gettcore.upgrade;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.economy.EconomyData;
import org.gettserver.gettcore.economy.Ledger;
import org.gettserver.gettcore.economy.RateLimiter;
import org.gettserver.gettcore.economy.StoneCounter;

/** 업그레이드 메뉴 열기와 서버측 판정·적용. 클라이언트 패킷은 데이터를 싣지 않는다. */
public final class UpgradeService {
    /** One apply per server tick (50 ms) per player. */
    private static final RateLimiter LIMITER = new RateLimiter(50);

    /**
     * Components of the old stack that are NOT carried over even when the stack set them explicitly: they describe the
     * old item itself (durability scale, stats, name, stack size), and the new item's own defaults must apply.
     * Damage is recomputed from the old wear ratio. Everything else in the patch moves over (reinforce level,
     * unbreakable, enchantments incl. gettcore abilities, custom name, lore, banner patterns, custom data, ...).
     */
    private static final Set<DataComponentType<?>> ITEM_SPECIFIC = Set.of(DataComponents.DAMAGE,
            DataComponents.MAX_DAMAGE, DataComponents.ATTRIBUTE_MODIFIERS, DataComponents.ITEM_NAME,
            DataComponents.MAX_STACK_SIZE, DataComponents.TOOL);

    private UpgradeService() {}

    public static String key(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** The registered item for {@code id}, empty for a bad or unknown id (or air). */
    public static Optional<Item> item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Optional.empty();
        return BuiltInRegistries.ITEM.getOptional(rl).filter(i -> i != Items.AIR);
    }

    /** Next step for {@code gear}; empty when there is none or its result/material item is not installed. */
    public static Optional<UpgradeConfig.Step> nextStep(ItemStack gear, UpgradeConfig config) {
        if (gear.isEmpty()) return Optional.empty();
        if (BackpackBridge.isBackpack(gear)) {
            return config.backpackStep(key(gear), BackpackBridge.tier(gear)).filter(s -> item(s.material()).isPresent());
        }
        return config.step(key(gear)).filter(s -> item(s.to()).isPresent() && item(s.material()).isPresent());
    }

    /** Material predicate for {@link StoneCounter}: the plain item, any components. */
    public static boolean isMaterial(ItemStack stack, UpgradeConfig.Step step) {
        return item(step.material()).map(stack::is).orElse(false);
    }

    /** Material count in {@code slots} for {@code step} (0 without a step). */
    public static int held(List<ItemStack> slots, Optional<UpgradeConfig.Step> step) {
        return step.map(s -> StoneCounter.count(slots, st -> isMaterial(st, s))).orElse(0);
    }

    /**
     * A new stack of {@code to} carrying the old stack's component patch minus {@link #ITEM_SPECIFIC}, so the new
     * item's own defaults (attributes, max damage, name) apply; wear keeps its ratio.
     */
    public static ItemStack upgraded(ItemStack old, Item to) {
        ItemStack out = new ItemStack(to, old.getCount());
        DataComponentPatch patch = old.getComponentsPatch().forget(ITEM_SPECIFIC::contains);
        out.applyComponents(patch);
        if (out.isDamageableItem() && old.isDamageableItem()) {
            out.setDamageValue(UpgradeRules.damageAfter(old.getDamageValue(), old.getMaxDamage(), out.getMaxDamage()));
        }
        return out;
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new UpgradeMenu(id, inv),
                Component.translatable("gettcore.gui.upgrade_title")));
    }

    public static void apply(ServerPlayer player) {
        // (1) the open menu must be an upgrade menu
        if (!(player.containerMenu instanceof UpgradeMenu menu)) return;
        if (!LIMITER.tryAcquire(player.getUUID(), System.currentTimeMillis())) return;
        // (2) re-check with the server config, the inventory materials and the ledger balance
        ItemStack gear = menu.gear();
        Optional<UpgradeConfig.Step> found = nextStep(gear, GettData.get().upgrade());
        if (found.isEmpty()) return;
        UpgradeConfig.Step step = found.get();
        Item to = item(step.to()).orElseThrow();
        boolean backpack = UpgradeConfig.isBackpackStep(step);
        // The result is made before anything is taken: a backpack that cannot be raised costs nothing.
        ItemStack result = backpack ? BackpackBridge.upgraded(gear) : upgraded(gear, to);
        if (result.isEmpty()) return;
        UUID id = player.getUUID();
        EconomyData eco = EconomyData.get(player.server);
        List<ItemStack> slots = StoneCounter.slots(player.getInventory());
        if (!UpgradeRules.canUpgrade(found, held(slots, found), eco.ledger().get(id))) return;
        // (3) gold and material: all or nothing
        if (step.gold() > 0 && eco.ledger().withdraw(id, step.gold()) != Ledger.Result.OK) return;
        if (!StoneCounter.consume(slots, st -> isMaterial(st, step), step.count())) {
            if (step.gold() > 0) eco.ledger().deposit(id, step.gold());
            return;
        }
        if (step.gold() > 0) eco.changed(id);
        player.getInventory().setChanged();
        // (4) replace the gear
        menu.setGear(result);
        menu.broadcastChanges();
        // (5) chat line, sound, log line
        Component line = backpack
                ? Component.translatable("gettcore.msg.upgrade.backpack", BackpackBridge.tierName(BackpackBridge.tier(result)))
                : Component.translatable("gettcore.msg.upgrade.success", to.getDescription());
        player.sendSystemMessage(line.copy().withStyle(ChatFormatting.GREEN));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SMITHING_TABLE_USE,
                SoundSource.PLAYERS, 1.0f, 1.0f);
        GettCore.LOGGER.info("gettcore: {} upgrade {} -> {} ({} x{}, {} gold)", player.getGameProfile().getName(),
                step.from(), step.to(), step.material(), step.count(), step.gold());
    }

    /** Registered once at startup: /gett reload closes every open upgrade menu (config sync is in DataEvents). */
    public static void registerReloadHook() {
        GettData.addReloadHook(() -> {
            try {
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (p.containerMenu instanceof UpgradeMenu) {
                        p.closeContainer();
                    }
                }
            } catch (RuntimeException e) {
                GettCore.LOGGER.error("gettcore: failed closing upgrade menus on reload", e);
            }
        });
    }
}
