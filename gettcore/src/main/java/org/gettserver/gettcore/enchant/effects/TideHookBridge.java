package org.gettserver.gettcore.enchant.effects;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import org.gettserver.gettcore.GettCore;

/**
 * Tide(선택 모드) 연동. Tide 는 찌를 자체 개체(TideFishingHook)로 바꾸고 {@code player.fishing} 에는 위치만 넘겨주는
 * 껍데기(HookAccessor)를 넣으므로, 입질 대기 시간은 Tide 찌의 필드를 직접 고쳐야 한다. 컴파일 의존성 없이 리플렉션으로만
 * 접근하고, Tide 가 없거나 구조가 바뀌었으면 조용히 꺼진다(Tide 2.1.1 기준).
 */
final class TideHookBridge {
    private static final Method GET_HOOK;
    private static final Field LURED;
    private static final Field HOOKED;

    static {
        Method getHook = null;
        Field lured = null;
        Field hooked = null;
        if (ModList.get() != null && ModList.get().isLoaded("tide")) {
            try {
                String pkg = "com.li64.tide.registries.entities.misc.fishing.";
                getHook = Class.forName(pkg + "HookAccessor").getMethod("getHook", Player.class);
                Class<?> hookClass = Class.forName(pkg + "TideFishingHook");
                lured = hookClass.getDeclaredField("timeUntilLured");
                hooked = hookClass.getDeclaredField("timeUntilHooked");
                lured.setAccessible(true);
                hooked.setAccessible(true);
                GettCore.LOGGER.info("gettcore: Tide found — lure ability shortens Tide bite time");
            } catch (ReflectiveOperationException | RuntimeException e) {
                GettCore.LOGGER.warn("gettcore: Tide found but its hook could not be linked; lure ability is off for Tide rods", e);
                getHook = null;
            }
        }
        GET_HOOK = getHook;
        LURED = lured;
        HOOKED = hooked;
    }

    private TideHookBridge() {}

    /** 플레이어가 던져 둔 Tide 찌. Tide 가 없거나 Tide 찌가 아니면 null. */
    static Object hook(Player player) {
        if (GET_HOOK == null) return null;
        try {
            return GET_HOOK.invoke(null, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static int lured(Object hook) {
        return get(LURED, hook);
    }

    static void setLured(Object hook, int value) {
        set(LURED, hook, value);
    }

    static int hooked(Object hook) {
        return get(HOOKED, hook);
    }

    static void setHooked(Object hook, int value) {
        set(HOOKED, hook, value);
    }

    private static int get(Field field, Object hook) {
        try {
            return field.getInt(hook);
        } catch (IllegalAccessException | RuntimeException e) {
            return 0;
        }
    }

    private static void set(Field field, Object hook, int value) {
        try {
            field.setInt(hook, value);
        } catch (IllegalAccessException | RuntimeException e) {
            // 못 고치면 그 찌는 그대로 둔다
        }
    }
}
