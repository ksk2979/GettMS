package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

/**
 * One quest goal. {@code target} is an item/entity id or #tag (collect/kill), an advancement id, or an NPC name (talk);
 * in JSON it is stored under a per-type field name (item, entity, id, npc).
 */
public record Objective(Type type, String target, int count, boolean consume, Optional<String> label) {
    public enum Type implements StringRepresentable {
        COLLECT("collect", "item"), KILL("kill", "entity"), ADVANCEMENT("advancement", "id"), TALK("talk", "npc");

        private final String serialized;
        private final String field;

        Type(String serialized, String field) {
            this.serialized = serialized;
            this.field = field;
        }

        /** JSON field that holds the target for this type. */
        public String targetField() {
            return field;
        }

        @Override
        public String getSerializedName() {
            return serialized;
        }
    }

    private static Optional<String> targetFor(Objective o, Type t) {
        return o.type == t ? Optional.of(o.target) : Optional.empty();
    }

    public static final Codec<Objective> CODEC = RecordCodecBuilder.<Objective>create(i -> i.group(
            StringRepresentable.fromEnum(Type::values).fieldOf("type").forGetter(Objective::type),
            Codec.STRING.optionalFieldOf("item").forGetter(o -> targetFor(o, Type.COLLECT)),
            Codec.STRING.optionalFieldOf("entity").forGetter(o -> targetFor(o, Type.KILL)),
            Codec.STRING.optionalFieldOf("id").forGetter(o -> targetFor(o, Type.ADVANCEMENT)),
            Codec.STRING.optionalFieldOf("npc").forGetter(o -> targetFor(o, Type.TALK)),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(Objective::count),
            Codec.BOOL.optionalFieldOf("consume", false).forGetter(Objective::consume),
            Codec.STRING.optionalFieldOf("label").forGetter(Objective::label)
    ).apply(i, (type, item, entity, id, npc, count, consume, label) -> {
        Optional<String> t = switch (type) {
            case COLLECT -> item;
            case KILL -> entity;
            case ADVANCEMENT -> id;
            case TALK -> npc;
        };
        return new Objective(type, t.orElse(""), count, consume, label);
    })).validate(o -> o.target.isBlank()
            ? DataResult.error(() -> "objective '" + o.type.getSerializedName() + "' needs field '"
                    + o.type.targetField() + "'")
            : DataResult.success(o));

    /** 처치 목표 대상: 몬스터(Enemy)면 무엇이든. */
    public static final String ANY_MONSTER = "@monster";

    public boolean isTag() {
        return target.startsWith("#");
    }
}
