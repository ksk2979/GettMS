package org.gettserver.gettcore.enchant;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.Rolled;

/** 묶음과 뽑기 결과의 표시 이름. */
public final class BundleNames {
    private BundleNames() {}

    /** 묶음 이름: 설정에 이름이 있으면 그 문자열, 없으면 능력 이름을 {@code ·}로 이은 것("광맥·제련"). */
    public static MutableComponent name(PoolConfig.Bundle bundle) {
        if (bundle.name() != null && !bundle.name().isBlank()) return Component.literal(bundle.name());
        return joined(bundle.abilityList());
    }

    private static MutableComponent joined(List<Ability> abilities) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < abilities.size(); i++) {
            if (i > 0) out.append("·");
            out.append(Component.translatable("enchantment.gettcore." + abilities.get(i).id()));
        }
        return out;
    }

    /**
     * 뽑기 결과 한 줄: 이름 뒤에 레벨("최강의 농부 III", "광맥 II"). 3레벨 능력이 없으면 이름만("광역·제련", "제련").
     * 묶음 설정에서 찾지 못한 여러 능력은 능력 이름을 이어 붙인다.
     */
    public static MutableComponent display(List<Rolled> parts, Optional<PoolConfig.Bundle> bundle) {
        MutableComponent name = bundle.map(BundleNames::name)
                .orElseGet(() -> joined(parts.stream().map(Rolled::ability).toList()));
        int level = SpecialEnchants.bundleLevel(parts);
        if (level <= 0) return name;
        return name.append(" ").append(Component.translatable("enchantment.level." + level));
    }
}
