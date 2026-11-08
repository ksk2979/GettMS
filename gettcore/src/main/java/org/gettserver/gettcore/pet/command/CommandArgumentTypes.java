package org.gettserver.gettcore.pet.command;

import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.gettserver.gettcore.GettCore;

/** Custom command argument types; must be registered so the client can parse them from the command tree. */
public final class CommandArgumentTypes {
    public static final DeferredRegister<ArgumentTypeInfo<?, ?>> TYPES =
            DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, GettCore.MOD_ID);

    public static final DeferredHolder<ArgumentTypeInfo<?, ?>, SingletonArgumentInfo<NameArgument>> NAME =
            TYPES.register("name", () -> SingletonArgumentInfo.contextFree(NameArgument::new));

    private CommandArgumentTypes() {}

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) -> e.enqueueWork(() ->
                net.minecraft.commands.synchronization.ArgumentTypeInfos.registerByClass(NameArgument.class, NAME.get())));
    }
}
