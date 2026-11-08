package org.gettserver.gettcore.pet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.perm.Perms;

/** Pet admin commands: /몹정보 and /mobinfo (Perms.ADMIN). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class PetCommands {
    static final double REACH = 32;

    private PetCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(mobInfo("몹정보"));
        d.register(mobInfo("mobinfo"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mobInfo(String name) {
        return Commands.literal(name).requires(Perms.require(Perms.ADMIN)).executes(PetCommands::mobInfo);
    }

    private static int mobInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<LivingEntity> target = lookedAt(player);
        if (target.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.msg.mobinfo.none"));
            return 0;
        }
        LivingEntity e = target.get();
        PetConfig config = GettData.get().pets();
        Optional<PetSpecies> species = config.species(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
        for (Component line : mobInfoLines(e.getDisplayName(), e.getHealth(), e.getMaxHealth(), species,
                CaptureService.hasOwner(e), config.capture())) {
            ctx.getSource().sendSystemMessage(line);
        }
        return 1;
    }

    /** The living entity (not the player) the player looks at within {@link #REACH} blocks, stopped by blocks. */
    private static Optional<LivingEntity> lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        double reach = REACH;
        HitResult block = player.pick(REACH, 0, false);
        if (block.getType() != HitResult.Type.MISS) reach = block.getLocation().distanceTo(eye);
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));
        AABB box = player.getBoundingBox().expandTowards(player.getViewVector(1.0F).scale(reach)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
                en -> en instanceof LivingEntity && en != player && !en.isSpectator(), reach * reach);
        return hit != null && hit.getEntity() instanceof LivingEntity le ? Optional.of(le) : Optional.empty();
    }

    /** Chat lines for /몹정보; pure so it can be unit tested. Chances use the current health. */
    static List<Component> mobInfoLines(Component name, float health, float maxHealth, Optional<PetSpecies> species,
                                        boolean owned, PetConfig.Capture capture) {
        double ratio = maxHealth > 0 ? Math.max(0, Math.min(1, health / maxHealth)) : 0;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gettcore.msg.mobinfo.header", name, fmt(health), fmt(maxHealth),
                (int) Math.ceil(ratio * 100)));
        if (species.isEmpty()) {
            lines.add(Component.translatable("gettcore.msg.mobinfo.not_species"));
        } else if (owned) {
            lines.add(Component.translatable("gettcore.msg.mobinfo.owned"));
        } else {
            lines.add(Component.translatable("gettcore.msg.mobinfo.capturable", fmt(capture.maxPercent())));
            for (CaptureTier tier : CaptureTier.values()) {
                double chance = CaptureRules.chance(species.get(), tier, capture, ratio);
                lines.add(Component.translatable("gettcore.msg.mobinfo.chance_line",
                        Component.translatable("gettcore.tier." + tier.key()), fmt(chance)));
            }
        }
        return lines;
    }

    /** One decimal, without a trailing ".0". */
    static String fmt(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? Long.toString((long) r) : Double.toString(r);
    }
}
