package org.gettserver.gettcore.preset;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.DataEvents;
import org.gettserver.gettcore.network.PresetSyncPayload;
import org.gettserver.gettcore.perm.Perms;

/**
 * /프리셋 저장|삭제|받기|목록 과 /preset save|remove|give|list (Perms.ADMIN). 저장 = 손에 든 아이템을 구성 요소까지 그대로
 * 이름 붙여 저장한다. 그 뒤 상점·퀘스트 보상·드롭에서 {@code preset:<이름>} 으로 쓴다.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class PresetCommands {
    private static final SuggestionProvider<CommandSourceStack> NAMES =
            (c, b) -> SharedSuggestionProvider.suggest(ItemPresets.names(), b);

    private PresetCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("프리셋", "저장", "삭제", "받기", "목록"));
        d.register(root("preset", "save", "remove", "give", "list"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String save, String remove, String give,
                                                                  String list) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.literal(save).then(Commands.argument("name", StringArgumentType.word())
                        .executes(PresetCommands::save)))
                .then(Commands.literal(remove).then(Commands.argument("name", StringArgumentType.word())
                        .suggests(NAMES).executes(PresetCommands::remove)))
                .then(Commands.literal(give).then(Commands.argument("name", StringArgumentType.word())
                        .suggests(NAMES)
                        .executes(c -> give(c, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64 * 36))
                                .executes(c -> give(c, IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal(list).executes(PresetCommands::list));
    }

    private static int save(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        ItemStack held = player.getMainHandItem();
        if (!ItemPresets.validName(name)) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.preset.bad_name"));
            return 0;
        }
        if (held.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.preset.empty_hand"));
            return 0;
        }
        if (!ItemPresets.all().containsKey(name) && ItemPresets.all().size() >= ItemPresets.MAX_PRESETS) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.preset.too_many", ItemPresets.MAX_PRESETS));
            return 0;
        }
        try {
            ItemPresets.put(DataEvents.configDir(), player.server.registryAccess(), name, held);
        } catch (IOException | RuntimeException e) {
            GettCore.LOGGER.error("preset save {} failed", name, e);
            ctx.getSource().sendFailure(Component.translatable("gettcore.preset.save_failed"));
            return 0;
        }
        PacketDistributor.sendToAllPlayers(PresetSyncPayload.current());
        Component shown = held.getHoverName();
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.preset.saved",
                ItemPresets.idOf(name).toString(), shown), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        try {
            if (!ItemPresets.remove(DataEvents.configDir(), ctx.getSource().getServer().registryAccess(), name)) {
                ctx.getSource().sendFailure(Component.translatable("gettcore.preset.unknown", name));
                return 0;
            }
        } catch (IOException | RuntimeException e) {
            GettCore.LOGGER.error("preset remove {} failed", name, e);
            ctx.getSource().sendFailure(Component.translatable("gettcore.preset.save_failed"));
            return 0;
        }
        PacketDistributor.sendToAllPlayers(PresetSyncPayload.current());
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.preset.removed", name), true);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        if (!ItemPresets.validName(name) || !ItemPresets.exists(ItemPresets.idOf(name))) {
            ctx.getSource().sendFailure(Component.translatable("gettcore.preset.unknown", name));
            return 0;
        }
        ItemStack proto = ItemPresets.stack(ItemPresets.idOf(name), 1);
        int left = count;
        while (left > 0) {
            int n = Math.min(left, proto.getMaxStackSize());
            left -= n;
            ItemStack s = proto.copyWithCount(n);
            if (!player.getInventory().add(s) && !s.isEmpty()) player.drop(s, false);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.preset.given", proto.getHoverName(), count), false);
        return count;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var all = ItemPresets.all();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.preset.none"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("gettcore.preset.list_header", all.size()), false);
        all.forEach((name, stack) -> ctx.getSource().sendSuccess(
                () -> Component.literal(" " + ItemPresets.idOf(name) + " — ").append(stack.getHoverName()), false));
        return all.size();
    }
}
