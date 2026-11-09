package org.gettserver.gettcore.hunting;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.hunting.HuntingConfig.Entry;
import org.gettserver.gettcore.hunting.HuntingConfig.Zone;
import org.gettserver.gettcore.perm.Perms;
import org.gettserver.gettcore.pet.command.NameArgument;
import org.gettserver.gettcore.protect.WorldEditBridge;

/** /사냥터 추가|삭제|목록|이동|소환 and /hunt add|remove|list|goto|spawn (Perms.ADMIN). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class HuntingCommands {
    private static final String MSG = "gettcore.msg.hunt.";

    private static final SuggestionProvider<CommandSourceStack> ZONE_IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(GettData.get().hunting().zones().stream().map(Zone::id), builder);
    private static final SuggestionProvider<CommandSourceStack> ENTRY_NAMES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(entryNames(GettData.get().hunting(), NameArgument.getName(ctx, "id")),
                    builder);

    private HuntingCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("사냥터", "추가", "삭제", "목록", "이동", "소환"));
        d.register(root("hunt", "add", "remove", "list", "goto", "spawn"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String add, String remove,
                                                                   String list, String jump, String spawn) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal(add).then(Commands.argument("id", NameArgument.name())
                        .executes(HuntingCommands::add)))
                .then(Commands.literal(remove).then(zoneArg().executes(HuntingCommands::remove)))
                .then(Commands.literal(list).executes(HuntingCommands::list))
                .then(Commands.literal(jump).then(zoneArg().executes(HuntingCommands::jump)))
                // The monster name is the rest of the line, so names with spaces ("큰 좀비") work.
                .then(Commands.literal(spawn).then(zoneArg().then(Commands.argument("monster",
                        StringArgumentType.greedyString()).suggests(ENTRY_NAMES).executes(HuntingCommands::spawn))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> zoneArg() {
        return Commands.argument("id", NameArgument.name()).suggests(ZONE_IDS);
    }

    /** A new zone exactly over the selection (min/max ordered, no Y padding) with default numbers and no monsters. */
    static Zone newZone(String id, ResourceKey<Level> dim, BlockPos a, BlockPos b) {
        BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        return new Zone(id, dim, lo, hi, HuntingConfig.DEFAULT_MAX_ALIVE, HuntingConfig.DEFAULT_REFILL_SECONDS,
                List.of());
    }

    static List<String> entryNames(HuntingConfig cfg, String zone) {
        return cfg.zone(zone).map(z -> z.entries().stream().map(Entry::name).toList()).orElse(List.of());
    }

    private static int add(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = NameArgument.getName(ctx, "id");
        if (!ModList.get().isLoaded("worldedit")) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "need_worldedit"));
            return 0;
        }
        HuntingConfig cfg = GettData.get().hunting();
        if (cfg.zone(id).isPresent()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "exists", id));
            return 0;
        }
        // Only reached with WorldEdit loaded, so WorldEditBridge (and WorldEdit's classes) load safely.
        Optional<WorldEditBridge.Selection> sel = WorldEditBridge.selection(player);
        if (sel.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "no_selection"));
            return 0;
        }
        Zone zone = newZone(id, sel.get().dimension(), sel.get().min(), sel.get().max());
        if (!save(ctx, cfg.withZone(zone))) return 0;
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "added", id, xyz(zone.min()), xyz(zone.max())),
                true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String id = NameArgument.getName(ctx, "id");
        HuntingConfig cfg = GettData.get().hunting();
        if (cfg.zone(id).isEmpty()) return notFound(ctx, id);
        // Its variants are discarded by the next HuntingService sweep (and on load).
        if (!save(ctx, cfg.without(id))) return 0;
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "removed", id), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<Zone> zones = GettData.get().hunting().zones();
        if (zones.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_empty"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_header", zones.size()), false);
        for (Zone z : zones) {
            ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_line", z.id(),
                    z.dimension().location().toString(), xyz(z.min()), xyz(z.max()), z.maxAlive(), z.refillSeconds(),
                    z.entries().size()), false);
        }
        return zones.size();
    }

    private static int jump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = NameArgument.getName(ctx, "id");
        Optional<Zone> found = GettData.get().hunting().zone(id);
        if (found.isEmpty()) return notFound(ctx, id);
        Zone z = found.get();
        ServerLevel level = ctx.getSource().getServer().getLevel(z.dimension());
        if (level == null) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "no_dimension", id,
                    z.dimension().location().toString()));
            return 0;
        }
        BlockPos c = z.center();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
        player.teleportTo(level, c.getX() + 0.5, y, c.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot());
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "goto", id), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = NameArgument.getName(ctx, "id");
        String name = StringArgumentType.getString(ctx, "monster").trim();
        Optional<Zone> zone = GettData.get().hunting().zone(id);
        if (zone.isEmpty()) return notFound(ctx, id);
        Optional<Entry> entry = zone.get().entry(name);
        if (entry.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "no_entry", id, name));
            return 0;
        }
        if (!HuntingService.spawnTest(player, zone.get(), entry.get())) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "spawn_failed", name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "spawned", name), false);
        return 1;
    }

    private static String xyz(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static int notFound(CommandContext<CommandSourceStack> ctx, String id) {
        ctx.getSource().sendFailure(Component.translatable(MSG + "not_found", id));
        return 0;
    }

    private static boolean save(CommandContext<CommandSourceStack> ctx, HuntingConfig cfg) {
        try {
            GettData.get().saveHunting(cfg);
            return true;
        } catch (IOException e) {
            GettCore.LOGGER.error("gettcore: saving hunting.json failed", e);
            ctx.getSource().sendFailure(Component.translatable(MSG + "save_failed", String.valueOf(e.getMessage())));
            return false;
        }
    }
}
