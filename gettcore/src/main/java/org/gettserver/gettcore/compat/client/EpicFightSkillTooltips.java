package org.gettserver.gettcore.compat.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.gettserver.gettcore.GettCore;

/**
 * Epic Fight 스킬 선택 창의 스킬 줄에 마우스를 올리면 스킬북과 같은 설명(소모·획득 포함)을 툴팁으로 보여 준다.
 * Epic Fight 는 컴파일 의존이 아니므로 전부 리플렉션(클래스 이름 문자열)으로 접근하고,
 * 하나라도 어긋나면(다른 버전 등) 경고 한 줄만 남기고 그 세션 동안 기능을 끈다.
 * 소모·획득 줄에 쓰는 멤버는 설명용과 따로 해석해, 그것만 없어도 설명은 계속 나온다.
 */
public final class EpicFightSkillTooltips {
    private static final String SCREEN_CLASS = "yesman.epicfight.client.gui.screen.SkillEditScreen";
    private static final String ROW_CLASS = "yesman.epicfight.client.gui.screen.SkillEditScreen$EquipSkillButton";

    private static boolean disabled;
    private static boolean rowFailureLogged;
    private static Field skillField;
    private static Method translationKey;
    private static Method tooltipArgs;

    /** 소모·획득 줄용 리플렉션. 실패하면 그 줄들만 끈다. */
    private static boolean extrasDisabled;
    private static boolean extrasResolved;
    private static Method resourceType;
    private static Method consumption;
    private static Method modifierEntries;

    /** 이미 판정한 줄(설명 없음 포함). 화면이 바뀌면 비운다. */
    private static final Set<Object> judged = Collections.newSetFromMap(new IdentityHashMap<>());
    private static Screen lastScreen;

    private EpicFightSkillTooltips() {}

    /** "skill.ns.path" → "skill.ns.path.tooltip" */
    static String tooltipKey(String translationKey) {
        return translationKey + ".tooltip";
    }

    /**
     * "a, b, c" 형태 인자의 각 토큰을 translateOrNull 로 번역한다(null 이면 그 토큰은 그대로).
     * 알려진 토큰이 없는 문장 등은 바뀌지 않는다.
     */
    static String translateWeaponTypes(String arg, UnaryOperator<String> translateOrNull) {
        if (arg.isEmpty()) return arg;
        String[] tokens = arg.split(", ", -1);
        for (int i = 0; i < tokens.length; i++) {
            String translated = translateOrNull.apply(tokens[i]);
            if (translated != null) tokens[i] = translated;
        }
        return String.join(", ", tokens);
    }

    /** 스킬북과 같은 소모량 표기: 4 → "4.0" */
    static String formatCost(float consumption) {
        return String.format(Locale.ROOT, "%.1f", consumption);
    }

    /** 스킬북과 같은 획득 수치 표기: 가산 6.0 → "+6", -2.5 → "-2.5", 배율 0.15 → "+15%". */
    static String formatBonus(double amount, boolean additive) {
        DecimalFormat df = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        String sign = amount < 0 ? "-" : "+";
        double shown = Math.abs(additive ? amount : amount * 100);
        return sign + df.format(shown) + (additive ? "" : "%");
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
                resolveReflection(widget);
                try {
                    Component text = describe(widget);
                    if (text != null) widget.setTooltip(Tooltip.create(text));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    // 이 줄만 설명 없음으로 판정(이미 judged). 세션당 한 번만 기록.
                    if (!rowFailureLogged) {
                        rowFailureLogged = true;
                        GettCore.LOGGER.warn("gettcore: Epic Fight 스킬 설명 툴팁 한 줄 생성 실패(이후 같은 오류는 생략): {}", e.toString());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disabled = true;
            judged.clear();
            GettCore.LOGGER.warn("gettcore: Epic Fight 스킬 설명 툴팁 비활성 (호환되지 않는 Epic Fight 버전?): {}", e.toString());
        }
    }

    /** 리플렉션 대상(Field/Method) 해석. 실패하면 호출한 쪽에서 기능 전체를 끈다. */
    private static void resolveReflection(AbstractWidget row) throws ReflectiveOperationException {
        if (skillField == null) {
            Field f = row.getClass().getDeclaredField("skill");
            f.setAccessible(true);
            Class<?> skillClass = f.getType();
            translationKey = skillClass.getMethod("getTranslationKey");
            tooltipArgs = skillClass.getMethod("getTooltipArgsOfScreen", List.class);
            skillField = f;
        }
    }

    /** 소모·획득용 멤버 해석. 실패하면 경고 한 줄 남기고 그 줄들만 끈다(설명은 유지). */
    private static void resolveExtras() {
        if (extrasResolved || extrasDisabled) return;
        try {
            Class<?> skillClass = skillField.getType();
            Method rt = skillClass.getMethod("getResourceType");
            Method c = skillClass.getMethod("getConsumption");
            Method m = skillClass.getMethod("getModfierEntry"); // 철자 그대로(Epic Fight 쪽 오타)
            resourceType = rt;
            consumption = c;
            modifierEntries = m;
            extrasResolved = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            extrasDisabled = true;
            GettCore.LOGGER.warn("gettcore: Epic Fight 스킬 소모·획득 표시 비활성 (호환되지 않는 Epic Fight 버전?): {}", e.toString());
        }
    }

    private static Component describe(AbstractWidget row) throws ReflectiveOperationException {
        Object skill = skillField.get(row);
        if (skill == null) return null;
        String key = tooltipKey((String) translationKey.invoke(skill));
        if (!I18n.exists(key)) return null;
        List<?> args = (List<?>) tooltipArgs.invoke(skill, new ArrayList<Object>());
        Object[] shown = args.toArray();
        for (int i = 0; i < shown.length; i++) {
            if (shown[i] instanceof String s) {
                shown[i] = translateWeaponTypes(s, token -> {
                    String k = "weapon_category.epicfight." + token;
                    return I18n.exists(k) ? I18n.get(k) : null;
                });
            }
        }
        Component description = Component.translatable(key, shown);
        Component extras = extraLines(skill);
        if (extras == null) return description;
        return Component.empty().append(extras).append("\n\n").append(description);
    }

    /** 소모·획득 줄(없으면 null). 실패하면 한 번만 기록하고 null. */
    private static Component extraLines(Object skill) {
        resolveExtras();
        if (extrasDisabled) return null;
        try {
            List<Component> lines = new ArrayList<>();
            float amount = ((Number) consumption.invoke(skill)).floatValue();
            Object resource = resourceType.invoke(skill);
            String name = resource instanceof Enum<?> e ? e.name() : "";
            String consumeKey = switch (name) {
                case "STAMINA" -> "attribute.name.epicfight.stamina.consume";
                case "COOLDOWN" -> "attribute.name.epicfight.cooldown.consume";
                case "HEALTH" -> "attribute.name.epicfight.health.consume";
                default -> null;
            };
            if (amount > 0 && consumeKey != null) {
                lines.add(Component.translatable("gettcore.epicfight.cost",
                        Component.translatable(consumeKey, formatCost(amount))).withStyle(ChatFormatting.GOLD));
            }
            if (modifierEntries.invoke(skill) instanceof Set<?> set) {
                for (Object o : set) {
                    @SuppressWarnings("unchecked")
                    Map.Entry<Holder<Attribute>, AttributeModifier> entry = (Map.Entry<Holder<Attribute>, AttributeModifier>) o;
                    AttributeModifier mod = entry.getValue();
                    boolean additive = mod.operation() == AttributeModifier.Operation.ADD_VALUE;
                    MutableComponent value = Component.literal(formatBonus(mod.amount(), additive) + " ")
                            .append(Component.translatable(entry.getKey().value().getDescriptionId()));
                    lines.add(Component.translatable("gettcore.epicfight.gain", value).withStyle(ChatFormatting.GREEN));
                }
            }
            if (lines.isEmpty()) return null;
            MutableComponent joined = Component.empty();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) joined.append("\n");
                joined.append(lines.get(i));
            }
            return joined;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!rowFailureLogged) {
                rowFailureLogged = true;
                GettCore.LOGGER.warn("gettcore: Epic Fight 스킬 소모·획득 줄 생성 실패(이후 같은 오류는 생략): {}", e.toString());
            }
            return null;
        }
    }
}
