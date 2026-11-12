package org.gettserver.gettcore.skill;

import java.util.Map;
import java.util.Optional;

/** The skill types by {@link SkillSpec#type()}. A spec whose type has no entry here is never cast. */
public final class SkillTypes {
    private static final Map<String, SkillType> TYPES = Map.of(
            SkillSpec.WIND_LINE, new WindLineSkill(),
            SkillSpec.STOMP, new StompSkill());

    private SkillTypes() {}

    public static Optional<SkillType> get(String type) {
        return Optional.ofNullable(TYPES.get(type));
    }
}
