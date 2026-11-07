package org.gettserver.gettcore.quest.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.economy.ClientConfig;
import org.gettserver.gettcore.network.QuestNotifyPayload;

/**
 * Quest accepted/completed notifications: a centered title + subtitle and a sound, queued so that several arriving at
 * once (one action can complete a chain of quests) are shown 0.5 s apart. Client only.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class QuestToasts {
    /** 0.5 s at 20 ticks per second. */
    public static final int SPACING_TICKS = 10;

    private static final NotifyQueue<QuestNotifyPayload> QUEUE = new NotifyQueue<>(SPACING_TICKS, 32);
    private static long ticks;

    private QuestToasts() {}

    public static void enqueue(QuestNotifyPayload payload) {
        QUEUE.offer(payload);
    }

    static void clear() {
        QUEUE.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        QuestNotifyPayload next = QUEUE.poll(ticks);
        if (next != null) show(mc, next);
    }

    private static void show(Minecraft mc, QuestNotifyPayload p) {
        boolean completed = p.kind() == QuestNotifyPayload.Kind.COMPLETED;
        Component title = completed
                ? Component.translatable("gettcore.quest.toast.completed").withStyle(ChatFormatting.GOLD)
                : Component.translatable("gettcore.quest.toast.accepted").withStyle(ChatFormatting.YELLOW);
        long gold = completed ? p.gold() : 0;
        mc.gui.resetTitleTimes();
        mc.gui.setSubtitle(QuestText.toastSubtitle(p.title(), gold, ClientConfig.currencyName()));
        mc.gui.setTitle(title);
        mc.getSoundManager().play(completed
                ? SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 0.7F)
                : SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1.0F, 1.0F));
    }
}
