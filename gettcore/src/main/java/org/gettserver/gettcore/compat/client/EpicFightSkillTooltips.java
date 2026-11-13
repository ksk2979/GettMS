package org.gettserver.gettcore.compat.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.gettserver.gettcore.GettCore;

/**
 * Epic Fight 스킬 선택 창의 스킬 줄에 마우스를 올리면 스킬북과 같은 설명을 툴팁으로 보여 준다.
 * Epic Fight 는 컴파일 의존이 아니므로 전부 리플렉션(클래스 이름 문자열)으로 접근하고,
 * 하나라도 어긋나면(다른 버전 등) 경고 한 줄만 남기고 그 세션 동안 기능을 끈다.
 */
public final class EpicFightSkillTooltips {
    private static final String SCREEN_CLASS = "yesman.epicfight.client.gui.screen.SkillEditScreen";
    private static final String ROW_CLASS = "yesman.epicfight.client.gui.screen.SkillEditScreen$EquipSkillButton";

    private static boolean disabled;
    private static Field skillField;
    private static Method translationKey;
    private static Method tooltipArgs;

    /** 이미 판정한 줄(설명 없음 포함). 화면이 바뀌면 비운다. */
    private static final Set<Object> judged = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Screen lastScreen;

    private EpicFightSkillTooltips() {}

    /** "skill.ns.path" → "skill.ns.path.tooltip" */
    static String tooltipKey(String translationKey) {
        return translationKey + ".tooltip";
    }

    public static void onRenderPre(ScreenEvent.Render.Pre event) {
        if (disabled) return;
        Screen screen = event.getScreen();
        if (!screen.getClass().getName().equals(SCREEN_CLASS)) {
            if (lastScreen != null) {
                lastScreen = null;
                judged.clear();
            }
            return;
        }
        if (screen != lastScreen) {
            lastScreen = screen;
            judged.clear();
        }
        try {
            for (Object child : screen.children()) {
                if (!(child instanceof AbstractWidget widget)) continue;
                if (!widget.getClass().getName().equals(ROW_CLASS)) continue;
                if (widget.getTooltip() != null || !judged.add(widget)) continue;
                Component text = describe(widget);
                if (text != null) widget.setTooltip(Tooltip.create(text));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disabled = true;
            judged.clear();
            GettCore.LOGGER.warn("gettcore: Epic Fight 스킬 설명 툴팁 비활성 (호환되지 않는 Epic Fight 버전?): {}", e.toString());
        }
    }

    private static Component describe(AbstractWidget row) throws ReflectiveOperationException {
        if (skillField == null) {
            Field f = row.getClass().getDeclaredField("skill");
            f.setAccessible(true);
            Class<?> skillClass = f.getType();
            translationKey = skillClass.getMethod("getTranslationKey");
            tooltipArgs = skillClass.getMethod("getTooltipArgsOfScreen", List.class);
            skillField = f;
        }
        Object skill = skillField.get(row);
        if (skill == null) return null;
        String key = tooltipKey((String) translationKey.invoke(skill));
        if (!I18n.exists(key)) return null;
        List<?> args = (List<?>) tooltipArgs.invoke(skill, new ArrayList<Object>());
        return Component.translatable(key, args.toArray());
    }
}
