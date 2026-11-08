package org.gettserver.gettcore.protect;

import com.mojang.brigadier.CommandDispatcher;
import org.gettserver.gettcore.pet.command.NameArgument;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.perm.Perms;
import org.gettserver.gettcore.protect.ProtectConfig.Flags;
import org.gettserver.gettcore.protect.ProtectConfig.Region;

/** /보호구역 추가|삭제|목록|이동|정보 and /region add|remove|list|goto|info (Perms.PROTECT_ADMIN). */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class ProtectCommands {
    private static final String MSG = "gettcore.msg.region.";

    private static final SuggestionProvider<CommandSourceStack> REGION_IDS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(GettData.get().protect().regions().stream().map(Region::id), builder);

    private ProtectCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("보호구역", "추가", "삭제", "목록", "이동", "정보"));
        d.register(root("region", "add", "remove", "list", "goto", "info"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String add, String remove,
                                                                   String list, String jump, String info) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.PROTECT_ADMIN))
                .then(Commands.literal(add).then(Commands.argument("id", NameArgument.name())
                        .executes(ProtectCommands::add)))
                .then(Commands.literal(remove).then(regionArg().executes(ProtectCommands::remove)))
                .then(Commands.literal(list).executes(ProtectCommands::list))
                .then(Commands.literal(jump).then(regionArg().executes(ProtectCommands::jump)))
                .then(Commands.literal(info).executes(ProtectCommands::info));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> regionArg() {
        return Commands.argument("id", NameArgument.name()).suggests(REGION_IDS);
    }

    private static int add(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = NameArgument.getName(ctx, "id");
        if (!ModList.get().isLoaded("worldedit")) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "need_worldedit"));
            return 0;
        }
        ProtectConfig cfg = GettData.get().protect();
        if (cfg.region(id).isPresent()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "exists", id));
            return 0;
        }
        // Only reached with WorldEdit loaded, so WorldEditBridge (and WorldEdit's classes) load safely.
        Optional<WorldEditBridge.Selection> sel = WorldEditBridge.selection(player);
        if (sel.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "no_selection"));
            return 0;
        }
        Region region = new Region(id, sel.get().dimension(), sel.get().min(), sel.get().max(), Flags.DEFAULT)
                .normalized();
        List<Region> next = new ArrayList<>(cfg.regions());
        next.add(region);
        if (!save(ctx, cfg.withRegions(next))) return 0;
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "added", id, xyz(region.min()),
                xyz(region.max())), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String id = NameArgument.getName(ctx, "id");
        ProtectConfig cfg = GettData.get().protect();
        if (cfg.region(id).isEmpty()) return notFound(ctx, id);
        List<Region> next = new ArrayList<>(cfg.regions());
        next.removeIf(r -> r.id().equals(id));
        if (!save(ctx, cfg.withRegions(next))) return 0;
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "removed", id), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<Region> regions = GettData.get().protect().regions();
        if (regions.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_empty"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_header", regions.size()), false);
        for (Region r : regions) {
            ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "list_line", r.id(),
                    r.dimension().location().toString(), xyz(r.min()), xyz(r.max()), allowed(r.flags().fireSpread()),
                    allowed(r.flags().bossGrief()), allowed(r.flags().explosions())), false);
        }
        return regions.size();
    }

    private static int jump(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String id = NameArgument.getName(ctx, "id");
        Optional<Region> found = GettData.get().protect().region(id);
        if (found.isEmpty()) return notFound(ctx, id);
        Region r = found.get();
        ServerLevel level = ctx.getSource().getServer().getLevel(r.dimension());
        if (level == null) {
            ctx.getSource().sendFailure(Component.translatable(MSG + "no_dimension", id,
                    r.dimension().location().toString()));
            return 0;
        }
        BlockPos c = r.center();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
        player.teleportTo(level, c.getX() + 0.5, y, c.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot());
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "goto", id), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Region> r = GettData.get().protect().regionAt(player.level().dimension(), player.blockPosition());
        if (r.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "none_here"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(MSG + "info", r.get().id()), false);
        return 1;
    }

    private static Component allowed(boolean allowed) {
        return Component.translatable(MSG + (allowed ? "allowed" : "blocked"));
    }

    private static String xyz(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static int notFound(CommandContext<CommandSourceStack> ctx, String id) {
        ctx.getSource().sendFailure(Component.translatable(MSG + "not_found", id));
        return 0;
    }

    private static boolean save(CommandContext<CommandSourceStack> ctx, ProtectConfig cfg) {
        try {
            GettData.get().saveProtect(cfg);
            return true;
        } catch (IOException e) {
            GettCore.LOGGER.error("gettcore: saving protect.json failed", e);
            ctx.getSource().sendFailure(Component.translatable(MSG + "save_failed", String.valueOf(e.getMessage())));
            return false;
        }
    }
}
