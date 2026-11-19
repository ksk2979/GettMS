package org.gettserver.gettcore.compat.client;

import java.lang.reflect.Method;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.compat.AutoModeRules;
import org.gettserver.gettcore.compat.AutoModeRules.Action;
import org.gettserver.gettcore.compat.AutoModeRules.Mode;

/**
 * Epic Fight 모드 자동 전환(클라이언트). 주 손에 Epic Fight 공격 동작이 있는 무기를 들면 전투 모드, 그 밖의 것(도구·음식·
 * 빈손·활·총 등)을 들면 기본 모드로 바꾼다({@link AutoModeRules}). 직접 바꾼 모드(전환 키)는 손에 든 것을 바꿀 때까지 유지.
 * Iron's Spells 시전이나 Emotecraft 감정표현 중에는 그 동작이 기본 모델용이라 잠깐 기본 모드로 두고, 끝나면 되돌린다.
 *
 * <p>Epic Fight·Iron's Spells·Emotecraft 는 컴파일 의존이 아니므로 리플렉션으로 접근한다. Epic Fight 쪽이 어긋나면
 * (다른 버전 등) 경고 한 줄만 남기고 이 세션 동안 끈다. 시전·감정표현 감지는 따로 해석해, 그 모드가 없거나 어긋나면
 * 그 감지만 꺼진다.
 */
public final class EpicFightAutoMode {
    private static final String CAPS = "yesman.epicfight.world.capabilities.EpicFightCapabilities";
    private static final String PATCH = "yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch";
    private static final String CAP_ITEM = "yesman.epicfight.world.capabilities.item.CapabilityItem";
    private static final String IRONS_CLIENT = "io.redspace.ironsspellbooks.player.ClientMagicData";
    private static final String EMOTE_PLAYER = "io.github.kosmx.emotes.executor.emotePlayer.IEmotePlayerEntity";

    private static boolean disabled;
    private static boolean resolved;
    private static Method getLocalPatch;
    private static Method getItemCap;
    private static Method isEpicFightMode;
    private static Method toEpicFightMode;
    private static Method toVanillaMode;
    private static Method isEmptyCap;
    private static Method getWeaponCategory;

    private static boolean extrasResolved;
    private static Method ironsIsCasting;
    private static Class<?> emotePlayerClass;
    private static Method isPlayingEmote;

    /** The player the state below belongs to (a respawn or a new world gives a new one). */
    private static LocalPlayer statePlayer;
    private static Item decidedFor;
    private static boolean suspended;

    private static Set<String> combatIds = Set.of();
    private static Set<String> normalIds = Set.of();

    private EpicFightAutoMode() {}

    /** Item ids pinned to a mode in gettcore-client.properties ({@link AutoModeRules#parseIds}). */
    public static void pin(Set<String> combat, Set<String> normal) {
        combatIds = combat;
        normalIds = normal;
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (disabled) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            statePlayer = null;
            return;
        }
        try {
            if (!resolved) resolve();
            Object patch = getLocalPatch.invoke(null, player);
            if (patch == null) return;   // Epic Fight has not attached its player data yet
            if (player != statePlayer) {
                statePlayer = player;
                decidedFor = null;
                suspended = false;
            }
            ItemStack held = player.getMainHandItem();
            boolean epicNow = (Boolean) isEpicFightMode.invoke(patch);
            String itemId = held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            Action action = AutoModeRules.decide(busy(player), epicNow, suspended, held.getItem() != decidedFor,
                    AutoModeRules.forMainHand(itemId, categoryOf(held), held.getItem() instanceof DiggerItem,
                            combatIds, normalIds));
            if (action.switchTo == Mode.EPIC_FIGHT) toEpicFightMode.invoke(patch, true);
            else if (action.switchTo == Mode.VANILLA) toVanillaMode.invoke(patch, true);
            if (action.startsSuspend) suspended = true;
            if (action.endsSuspend) suspended = false;
            if (action.remember) decidedFor = held.getItem();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disabled = true;
            GettCore.LOGGER.warn("gettcore: Epic Fight 모드 자동 전환을 끕니다 (이 Epic Fight 버전과 맞지 않음)", e);
        }
    }

    private static void resolve() throws ReflectiveOperationException {
        Class<?> caps = Class.forName(CAPS);
        Class<?> patch = Class.forName(PATCH);
        Class<?> capItem = Class.forName(CAP_ITEM);
        getLocalPatch = caps.getMethod("getLocalPlayerPatch", LocalPlayer.class);
        getItemCap = caps.getMethod("getItemStackCapability", ItemStack.class);
        isEpicFightMode = patch.getMethod("isEpicFightMode");
        toEpicFightMode = patch.getMethod("toEpicFightMode", boolean.class);
        toVanillaMode = patch.getMethod("toVanillaMode", boolean.class);
        isEmptyCap = capItem.getMethod("isEmpty");
        getWeaponCategory = capItem.getMethod("getWeaponCategory");
        resolved = true;
    }

    /** Epic Fight weapon category name of the stack ("" = no weapon data). */
    private static String categoryOf(ItemStack stack) throws ReflectiveOperationException {
        if (stack.isEmpty()) return "";
        Object cap = getItemCap.invoke(null, stack);
        if (cap == null || (Boolean) isEmptyCap.invoke(cap)) return "";
        Object category = getWeaponCategory.invoke(cap);
        if (category == null) return "";
        return category instanceof Enum<?> e ? e.name() : category.toString();
    }

    /** Casting an Iron's spell or playing an Emotecraft emote. Either check is skipped when its mod is absent. */
    private static boolean busy(LocalPlayer player) {
        if (!extrasResolved) resolveExtras();
        try {
            if (ironsIsCasting != null && (Boolean) ironsIsCasting.invoke(null)) return true;
            if (isPlayingEmote != null && emotePlayerClass.isInstance(player) && (Boolean) isPlayingEmote.invoke(player)) {
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ironsIsCasting = null;
            isPlayingEmote = null;
            GettCore.LOGGER.warn("gettcore: 시전·감정표현 감지를 끕니다 (모드 버전과 맞지 않음)", e);
        }
        return false;
    }

    private static void resolveExtras() {
        extrasResolved = true;
        if (ModList.get().isLoaded("irons_spellbooks")) {
            try {
                ironsIsCasting = Class.forName(IRONS_CLIENT).getMethod("isCasting");
            } catch (ReflectiveOperationException | LinkageError e) {
                GettCore.LOGGER.warn("gettcore: Iron's Spells 시전 감지를 쓸 수 없습니다: {}", e.toString());
            }
        }
        if (ModList.get().isLoaded("emotecraft")) {
            try {
                emotePlayerClass = Class.forName(EMOTE_PLAYER);
                isPlayingEmote = emotePlayerClass.getMethod("isPlayingEmote");
            } catch (ReflectiveOperationException | LinkageError e) {
                GettCore.LOGGER.warn("gettcore: Emotecraft 감정표현 감지를 쓸 수 없습니다: {}", e.toString());
            }
        }
    }
}
