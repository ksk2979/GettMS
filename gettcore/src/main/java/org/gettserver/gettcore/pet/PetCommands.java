package org.gettserver.gettcore.pet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

/**
 * Pet admin commands (Perms.ADMIN): /몹정보 (/mobinfo), and /펫 주기|삭제|목록 (/pet give|remove|list) on an online
 * player's dex.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class PetCommands {
    static final double REACH = 32;
    private static final String MSG = "gettcore.msg.pet.";

    private static final SuggestionProvider<CommandSourceStack> SPECIES = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(GettData.get().pets().species().keySet().stream()
                    .map(ResourceLocation::tryParse).filter(Objects::nonNull), builder);

    private PetCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(mobInfo("몹정보"));
        d.register(mobInfo("mobinfo"));
        d.register(pet("펫", "주기", "삭제", "목록"));
        d.register(pet("pet", "give", "remove", "list"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pet(String name, String give, String remove,
                                                                  String list) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal(give)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("species", ResourceLocationArgument.id()).suggests(SPECIES)
                                        .executes(ctx -> give(ctx, ""))
                                        .then(Commands.argument("variant", StringArgumentType.string())
                                                .executes(ctx -> give(ctx,
                                                        StringArgumentType.getString(ctx, "variant")))))))
                .then(Commands.literal(remove)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(PetCommands::remove))))
                .then(Commands.literal(list)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(PetCommands::list)));
    }

    private static int give(CommandContext<CommandSourceStack> ctx, String variant) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        ResourceLocation speciesId = ResourceLocationArgument.getId(ctx, "species");
        if (GettData.get().pets().species(speciesId).isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "admin_unknown_species", speciesId.toString()));
            return 0;
        }
        PetKey key = new PetKey(speciesId.toString(), variant);
        if (!PetData.get(target.server).add(target.getUUID(), key)) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "admin_duplicate", target.getDisplayName(),
                    CapturedMobItem.displayName(key)));
            return 0;
        }
        PetService.sync(target);
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "admin_given", target.getDisplayName(),
                CapturedMobItem.displayName(key)), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        int number = IntegerArgumentType.getInteger(ctx, "index");
        List<PetKey> owned = PetData.get(target.server).owned(target.getUUID());
        Optional<PetKey> key = byNumber(owned, number);
        if (key.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "admin_bad_index", number, owned.size()));
            return 0;
        }
        PetService.removeKey(target, key.get());
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "admin_removed", target.getDisplayName(),
                CapturedMobItem.displayName(key.get())), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        List<PetKey> owned = PetData.get(target.server).owned(target.getUUID());
        if (owned.isEmpty()) {
            ctx.getSource().sendSystemMessage(Component.translatable(MSG + "admin_list_empty",
                    target.getDisplayName()).withStyle(ChatFormatting.GRAY));
            return 0;
        }
        ctx.getSource().sendSystemMessage(Component.translatable(MSG + "admin_list_header", target.getDisplayName(),
                owned.size()).withStyle(ChatFormatting.YELLOW));
        PetConfig config = GettData.get().pets();
        for (int i = 0; i < owned.size(); i++) {
            PetKey key = owned.get(i);
            Component kind = Optional.ofNullable(ResourceLocation.tryParse(key.speciesId()))
                    .flatMap(config::species)
                    .<Component>map(sp -> Component.translatable(
                            "gettcore.kind." + sp.kind().name().toLowerCase(Locale.ROOT)))
                    .orElseGet(() -> Component.translatable("gettcore.gui.pet_dex.unknown"));
            ctx.getSource().sendSystemMessage(Component.translatable(MSG + "admin_list_line", i + 1,
                    CapturedMobItem.displayName(key), kind));
        }
        return owned.size();
    }

    /** The pet at 1-based {@code number} (as /펫 목록 numbers them), or empty when out of range. */
    static Optional<PetKey> byNumber(List<PetKey> owned, int number) {
        return number >= 1 && number <= owned.size() ? Optional.of(owned.get(number - 1)) : Optional.empty();
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
