package org.gettserver.gettcore.skill;

import java.util.Map;
import java.util.Optional;

/** The skill types by {@link SkillSpec#type()}. A spec whose type has no entry here is never cast. */
public final class SkillTypes {
    private static final Map<String, SkillType> TYPES = Map.of();

    private SkillTypes() {}

    public static Optional<SkillType> get(String type) {
        return Optional.ofNullable(TYPES.get(type));
    }
}
