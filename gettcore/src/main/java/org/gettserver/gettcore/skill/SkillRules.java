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

    /** A possible skill target: its entity id and distance from the caster. */
    public record Candidate(int id, double distance) {}

    /**
     * The target a skill is cast at: the farthest of {@code candidates} when {@code roll} (uniform 0..1) is below
     * {@code chance}, else {@code currentId}. No candidate keeps {@code currentId}.
     */
    public static int farTarget(List<Candidate> candidates, int currentId, double roll, double chance) {
        if (!(roll < chance) || candidates.isEmpty()) return currentId;
        Candidate far = candidates.get(0);
        for (Candidate c : candidates) {
            if (c.distance() > far.distance()) far = c;
        }
        return far.id();
    }

    /** A player this far from the caster sees the skill-name shout; range 0 = nobody. */
    public static boolean hearsShout(double distance, double shoutRange) {
        return shoutRange > 0 && distance <= shoutRange;
    }
}
