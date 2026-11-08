package org.gettserver.gettcore.protect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.ExplosionKnockbackEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.pet.boss.GettBoss;
import org.gettserver.gettcore.protect.ProtectConfig.Flags;
import org.gettserver.gettcore.protect.ProtectConfig.Region;

/**
 * Applies protect.json: explosion block damage, enderman pickup, boss griefing and fire spread in regions.
 * Player building and breaking is never touched.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class ProtectEvents {
    /** A player's flint and steel / fire charge fire is allowed if it appears within this many ticks. */
    private static final long IGNITE_TICKS = 2;
    /** Positions a player is lighting right now -> game time (server thread only). */
    private static final Map<GlobalPos, Long> IGNITED = new HashMap<>();

    private ProtectEvents() {}

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        Explosion explosion = event.getExplosion();
        if (!ProtectRules.filtersBlocks(explosion.getBlockInteraction())) return;
        if (level.isClientSide() || event.getAffectedBlocks().isEmpty()) return;
        ProtectConfig cfg = GettData.get().protect();
        boolean boss = explosion.getDirectSourceEntity() instanceof GettBoss
                || explosion.getIndirectSourceEntity() instanceof GettBoss;
        if (cfg.regions().isEmpty() && (cfg.explosionsBreakBlocks() || boss)) return;
        ResourceKey<Level> dim = level.dimension();
        List<BlockPos> keep = ProtectRules.filterBlocks(event.getAffectedBlocks(), boss, cfg.explosionsBreakBlocks(),
                pos -> cfg.regionAt(dim, pos).map(Region::flags));
        if (keep.size() == event.getAffectedBlocks().size()) return;
        event.getAffectedBlocks().clear();
        event.getAffectedBlocks().addAll(keep);
    }

    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) return;
        ProtectConfig cfg = GettData.get().protect();
        if (entity instanceof EnderMan) {
            if (!cfg.endermanPickup()) event.setCanGrief(false);
        } else if (entity instanceof GettBoss) {
            Optional<Region> r = cfg.regionAt(entity.level().dimension(), entity.blockPosition());
            if (r.isPresent() && !r.get().flags().bossGrief()) event.setCanGrief(false);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.FLINT_AND_STEEL) && !stack.is(Items.FIRE_CHARGE)) return;
        long now = event.getLevel().getGameTime();
        IGNITED.values().removeIf(t -> now - t > IGNITE_TICKS);
        BlockPos target = event.getFace() == null ? event.getPos() : event.getPos().relative(event.getFace());
        IGNITED.put(GlobalPos.of(event.getLevel().dimension(), target), now);
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getState().getBlock() instanceof BaseFireBlock)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ProtectConfig cfg = GettData.get().protect();
        if (cfg.regions().isEmpty()) return;
        BlockPos pos = event.getPos();
        Optional<Flags> flags = cfg.regionAt(level.dimension(), pos).map(Region::flags);
        if (flags.isEmpty()) return;
        Long lit = IGNITED.get(GlobalPos.of(level.dimension(), pos));
        boolean playerIgnited = lit != null && level.getGameTime() - lit <= IGNITE_TICKS;
        if (ProtectRules.shouldExtinguish(flags, playerIgnited)) {
            level.removeBlock(pos, false);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) return;
        if (!event.getSource().is(DamageTypeTags.IS_EXPLOSION)) return;
        ProtectConfig cfg = GettData.get().protect();
        Optional<Region> region = cfg.regionAt(player.level().dimension(), player.blockPosition());
        boolean blocked = ProtectRules.blocksExplosionDamage(region.map(Region::flags));
        GettCore.LOGGER.debug("gettcore protect: explosion dmg {} to {} at {} region={} blocked={}",
                event.getSource().typeHolder().unwrapKey().map(k -> k.location().toString()).orElse("?"),
                player.getName().getString(), player.blockPosition(), region.map(Region::id).orElse("-"), blocked);
        if (blocked) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onExplosionKnockback(ExplosionKnockbackEvent event) {
        if (!(event.getAffectedEntity() instanceof Player player) || event.getLevel().isClientSide()) return;
        ProtectConfig cfg = GettData.get().protect();
        Optional<Region> region = cfg.regionAt(event.getLevel().dimension(), player.blockPosition());
        if (ProtectRules.blocksExplosionDamage(region.map(Region::flags))) {
            event.setKnockbackVelocity(Vec3.ZERO);
            GettCore.LOGGER.debug("gettcore protect: explosion knockback zeroed for {} at {} region={}",
                    player.getName().getString(), player.blockPosition(), region.map(Region::id).orElse("-"));
        }
    }
}
