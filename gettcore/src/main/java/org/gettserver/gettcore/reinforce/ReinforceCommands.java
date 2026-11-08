package org.gettserver.gettcore.reinforce;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Unbreakable;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.perm.Perms;

/** /강화 &lt;단계&gt; 와 /reinforce &lt;level&gt; (Perms.ADMIN): 주손에 든 강화 대상 장비의 단계를 바로 정한다. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class ReinforceCommands {
    /** 명령으로 정할 수 있는 최대 단계(설정의 최대 단계보다 높아도 된다: 그 뒤 수동 강화만 막힌다). */
    public static final int MAX_COMMAND_LEVEL = 50;

    private ReinforceCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(root("강화", "단계"));
        d.register(root("reinforce", "level"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, String arg) {
        return Commands.literal(name)
                .requires(Perms.require(Perms.ADMIN))
                .then(Commands.argument(arg, IntegerArgumentType.integer(0, MAX_COMMAND_LEVEL))
                        .executes(c -> set(c, IntegerArgumentType.getInteger(c, arg))));
    }

    /** 결과 메시지 키: 대상이 아니면 not_target, 맞으면 set. */
    static String messageKey(Optional<ReinforceRules.Target> target) {
        return target.isPresent() ? "gettcore.msg.reinforce.set" : "gettcore.msg.reinforce.not_target";
    }

    private static int set(CommandContext<CommandSourceStack> ctx, int level) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        Optional<ReinforceRules.Target> target = ReinforceTargets.target(stack);
        if (target.isEmpty()) {
            player.sendSystemMessage(Component.translatable(messageKey(target)).withStyle(ChatFormatting.RED));
            return 0;
        }
        int before = ReinforceRules.level(stack);
        ReinforceRules.setLevel(stack, level);
        if (level > 0) {
            stack.set(DataComponents.UNBREAKABLE, new Unbreakable(false));
            stack.remove(DataComponents.DAMAGE);
        } else {
            stack.remove(DataComponents.UNBREAKABLE);
        }
        player.sendSystemMessage(Component.translatable(messageKey(target), level).withStyle(ChatFormatting.GREEN));
        GettCore.LOGGER.info("gettcore: {} set reinforce {} +{} -> +{} (command)", player.getGameProfile().getName(),
                BuiltInRegistries.ITEM.getKey(stack.getItem()), before, level);
        return 1;
    }
}
