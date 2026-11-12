package org.gettserver.gettcore.pet.boss;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import org.gettserver.gettcore.pet.command.NameArgument;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.perm.Perms;
import org.gettserver.gettcore.pet.PetRegistry;

/** /보스지점 추가|삭제|목록|이동|리셋|모션 [람각] and /bosspoint add|remove|list|goto|reset|anim [ramgak] (Perms.BOSS_ADMIN). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class BossCommands {
    private static final String MSG = "gettcore.msg.boss_point.";
    private static final double ANIM_RANGE = 32;

    private static final SuggestionProvider<CommandSourceStack> POINT_IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(GettData.get().bossPoints().stream().map(BossPoint::id), builder);
    private static final SuggestionProvider<CommandSourceStack> BOSS_TYPES = (ctx, builder) ->
            SharedSuggestionProvider.suggestResource(PetRegistry.BOSS_TYPES.stream().map(DeferredHolder::getId),
                    builder);

    private BossCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("보스지점", "추가", "삭제", "목록", "이동", "리셋", "모션", "람각"));
        d.register(root("bosspoint", "add", "remove", "list", "goto", "reset", "anim", "ramgak"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String add, String remove,
                                                                   String list, String jump, String reset,
                                                                   String anim, String ramgak) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.BOSS_ADMIN))
                .then(Commands.literal(add)
                        .then(Commands.argument("id", NameArgument.name())
                                .then(Commands.argument("bossType", ResourceLocationArgument.id())
                                        .suggests(BOSS_TYPES)
                                        .executes(c -> add(c, BossPoint.DEFAULT_RADIUS, BossPoint.DEFAULT_MAX_COUNT))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                                                .executes(c -> add(c, IntegerArgumentType.getInteger(c, "radius"),
                                                        BossPoint.DEFAULT_MAX_COUNT))
                                                .then(Commands.argument("maxCount",
                                                                IntegerArgumentType.integer(0, 10))
                                                        .executes(c -> add(c,
                                                                IntegerArgumentType.getInteger(c, "radius"),
                                                                IntegerArgumentType.getInteger(c, "maxCount"))))))))
                .then(Commands.literal(remove).then(pointArg().executes(BossCommands::remove)))
                .then(Commands.literal(list).executes(BossCommands::list))
                .then(Commands.literal(jump).then(pointArg().executes(BossCommands::jump)))
                .then(Commands.literal(reset).then(pointArg().executes(BossCommands::reset)))
                .then(Commands.literal(anim)
                        .executes(c -> anim(c, BossGiraffe.EVENT_STOMP, "anim_played"))
                        .then(Commands.literal(ramgak)
                                .executes(c -> anim(c, BossGiraffe.EVENT_RAMGAK, "anim_played_ramgak"))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> pointArg() {
        return Commands.argument("id", NameArgument.name()).suggests(POINT_IDS);
    }

    private static int add(CommandContext<CommandSourceStack> ctx, int radius, int maxCount)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = NameArgument.getName(ctx, "id");
        ResourceLocation type = ResourceLocationArgument.getId(ctx, "bossType");
        if (!isBossType(player.serverLevel(), type)) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "not_boss", type.toString()));
            return 0;
        }
        List<BossPoint> points = GettData.get().bossPoints();
        if (find(id).isPresent()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "exists", id));
            return 0;
        }
        BossPoint point = BossPoint.of(id, player, type, radius, maxCount);
        List<BossPoint> next = new ArrayList<>(points);
        next.add(point);
        if (!save(ctx, next)) return 0;
        String pos = point.pos().getX() + " " + point.pos().getY() + " " + point.pos().getZ();
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "added", id, pos), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String id = NameArgument.getName(ctx, "id");
        Optional<BossPoint> point = find(id);
        if (point.isEmpty()) return notFound(ctx, id);
        List<BossPoint> next = new ArrayList<>(GettData.get().bossPoints());
        next.removeIf(p -> p.id().equals(id));
        if (!save(ctx, next)) return 0;
        MinecraftServer server = ctx.getSource().getServer();
        BossPointData data = BossPointData.get(server);
        ServerLevel level = server.getLevel(point.get().dimension());
        int discarded = 0;
        if (level != null) {
            for (UUID u : data.alive(id)) {
                Entity e = level.getEntity(u);
                if (e != null) {
                    e.discard();
                    discarded++;
                }
            }
        }
        data.remove(id);
        int count = discarded;
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "removed", id, count), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<BossPoint> points = GettData.get().bossPoints();
        if (points.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_empty"), false);
            return 0;
        }
        BossPointData data = BossPointData.get(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_header", points.size()), false);
        for (BossPoint p : points) {
            int alive = data.alive(p.id()).size();
            ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_line", p.id(),
                    p.bossType().toString(), p.dimension().location().toString(), p.pos().getX(), p.pos().getY(),
                    p.pos().getZ(), p.radius(), p.maxCount(), p.respawnMinutes(), alive), false);
        }
        return points.size();
    }

    private static int jump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = NameArgument.getName(ctx, "id");
        Optional<BossPoint> point = find(id);
        if (point.isEmpty()) return notFound(ctx, id);
        BossPoint p = point.get();
        ServerLevel level = ctx.getSource().getServer().getLevel(p.dimension());
        if (level == null) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "no_dimension", id,
                    p.dimension().location().toString()));
            return 0;
        }
        player.teleportTo(level, p.pos().getX() + 0.5, p.pos().getY(), p.pos().getZ() + 0.5, Set.of(),
                player.getYRot(), player.getXRot());
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "goto", id), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        String id = NameArgument.getName(ctx, "id");
        if (find(id).isEmpty()) return notFound(ctx, id);
        BossPointData.get(ctx.getSource().getServer()).reset(id);
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "reset_done", id), true);
        return 1;
    }

    /**
     * Debug: plays an animation ({@code event}: the stomp, or 람각 with the extra literal) on the nearest boss giraffe
     * within {@link #ANIM_RANGE} blocks; {@code playedKey} is the success message.
     */
    private static int anim(CommandContext<CommandSourceStack> ctx, byte event, String playedKey) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 pos = ctx.getSource().getPosition();
        Optional<BossGiraffe> nearest = level.getEntitiesOfClass(BossGiraffe.class,
                        AABB.ofSize(pos, ANIM_RANGE * 2, ANIM_RANGE * 2, ANIM_RANGE * 2),
                        g -> g.distanceToSqr(pos) <= ANIM_RANGE * ANIM_RANGE)
                .stream().min(Comparator.comparingDouble(g -> g.distanceToSqr(pos)));
        if (nearest.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "anim_none"));
            return 0;
        }
        level.broadcastEntityEvent(nearest.get(), event);
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + playedKey), false);
        return 1;
    }

    private static Optional<BossPoint> find(String id) {
        return BossBehavior.point(id);
    }

    private static int notFound(CommandContext<CommandSourceStack> ctx, String id) {
        ctx.getSource().sendFailure(Component.translatable(MSG + "not_found", id));
        return 0;
    }

    private static boolean save(CommandContext<CommandSourceStack> ctx, List<BossPoint> points) {
        try {
            GettData.get().saveBossPoints(points);
            return true;
        } catch (IOException e) {
            GettCore.LOGGER.error("gettcore: saving boss_points.json failed", e);
            ctx.getSource().sendFailure(Component.translatable(MSG + "save_failed", String.valueOf(e.getMessage())));
            return false;
        }
    }

    /** A registered entity type whose entities are {@link GettBoss} (checked on a throwaway instance). */
    private static boolean isBossType(ServerLevel level, ResourceLocation type) {
        Optional<EntityType<?>> found = EntityType.byString(type.toString());
        if (found.isEmpty()) return false;
        Entity probe = found.get().create(level);
        if (probe == null) return false;
        probe.discard();
        return probe instanceof GettBoss;
    }
}
