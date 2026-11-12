package org.gettserver.gettcore.skill;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pure rules of the boss skill engine; the entity glue is {@link SkillRunner}. */
public final class SkillRules {
    private SkillRules() {}

    /** First enabled skill in list order that is off cooldown (readyTick by skill id) and minRange <= distance <= maxRange. */
    public static Optional<SkillSpec> pick(List<SkillSpec> skills, double distance, Map<String, Long> readyTick, long now) {
        for (SkillSpec s : skills) {
            if (!s.enabled()) continue;
            if (readyTick.getOrDefault(s.id(), Long.MIN_VALUE) > now) continue;
            if (distance >= s.minRange() && distance <= s.maxRange()) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** The skill's cooldown, times {@code enrageCooldownMult} while health is below {@code enrageBelow} (0 = never). */
    public static int cooldownTicks(SkillSpec s, double healthRatio, double enrageBelow, double enrageCooldownMult) {
        int base = s.cooldownTicks();
        if (enrageBelow > 0 && healthRatio < enrageBelow) return (int) Math.round(base * enrageCooldownMult);
        return base;
    }

    /** Skills hit living players in survival/adventure and owned pets, in the caster's level. */
    public static boolean validTarget(boolean alive, boolean sameLevel, boolean creativeOrSpectator, boolean isPlayerOrOwnedPet) {
        return alive && sameLevel && !creativeOrSpectator && isPlayerOrOwnedPet;
    }

    /** A player this far from the caster sees the skill-name shout; range 0 = nobody. */
    public static boolean hearsShout(double distance, double shoutRange) {
        return shoutRange > 0 && distance <= shoutRange;
    }
}
