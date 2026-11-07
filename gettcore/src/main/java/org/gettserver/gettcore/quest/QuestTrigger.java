package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;

/** How a quest starts or completes: automatically, or by talking to a named NPC. */
public record QuestTrigger(Type type, Optional<String> npc) {
    public enum Type implements StringRepresentable {
        AUTO("auto"), NPC("npc");

        private final String serialized;

        Type(String serialized) {
            this.serialized = serialized;
        }

        @Override
        public String getSerializedName() {
            return serialized;
        }
    }

    public static final QuestTrigger AUTO = new QuestTrigger(Type.AUTO, Optional.empty());

    public static final Codec<QuestTrigger> CODEC = RecordCodecBuilder.create(i -> i.group(
            StringRepresentable.fromEnum(Type::values).fieldOf("type").forGetter(QuestTrigger::type),
            Codec.STRING.optionalFieldOf("npc").forGetter(QuestTrigger::npc)
    ).apply(i, QuestTrigger::new));
}
