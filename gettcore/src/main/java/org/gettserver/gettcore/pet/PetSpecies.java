package org.gettserver.gettcore.pet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * One capturable species. {@code id} is the wild entity id (the key in pets.json, not a JSON field).
 * {@code obtainPercent} 0 means "use the global capture.obtainPercent".
 */
public record PetSpecies(String id, Kind kind, ResourceLocation petEntity, double attack, double speed,
                         double attackSpeed, double flySpeed, double climbSpeed, boolean boss, double sizeScale,
                         double healthMult, double attackMult, long killGold, double obtainPercent) {

    public enum Kind {
        FIGHTER, MOUNT, FLYING_MOUNT;

        static final Codec<Kind> CODEC = Codec.STRING.comapFlatMap(s -> {
            for (Kind k : values()) {
                if (k.name().equalsIgnoreCase(s)) return DataResult.success(k);
            }
            return DataResult.error(() -> "Unknown pet kind: " + s);
        }, k -> k.name().toLowerCase(Locale.ROOT));
    }

    // JSON defaults of the optional fields (also what PetConfig#sanitized resets bad values to).
    static final double DEFAULT_ATTACK = 6.0;
    static final double DEFAULT_SPEED = 0.3;
    static final double DEFAULT_ATTACK_SPEED = 1.0;
    static final double DEFAULT_FLY_SPEED = 0.6;
    static final double DEFAULT_CLIMB_SPEED = 0.3;
    static final double DEFAULT_MULT = 1.0;

    /** Codec for the species body; {@code id} is filled in from the map key by {@link PetConfig}. */
    static final Codec<PetSpecies> CODEC = RecordCodecBuilder.create(i -> i.group(
            Kind.CODEC.fieldOf("kind").forGetter(PetSpecies::kind),
            ResourceLocation.CODEC.fieldOf("petEntity").forGetter(PetSpecies::petEntity),
            Codec.DOUBLE.optionalFieldOf("attack", DEFAULT_ATTACK).forGetter(PetSpecies::attack),
            Codec.DOUBLE.optionalFieldOf("speed", DEFAULT_SPEED).forGetter(PetSpecies::speed),
            Codec.DOUBLE.optionalFieldOf("attackSpeed", DEFAULT_ATTACK_SPEED).forGetter(PetSpecies::attackSpeed),
            Codec.DOUBLE.optionalFieldOf("flySpeed", DEFAULT_FLY_SPEED).forGetter(PetSpecies::flySpeed),
            Codec.DOUBLE.optionalFieldOf("climbSpeed", DEFAULT_CLIMB_SPEED).forGetter(PetSpecies::climbSpeed),
            Codec.BOOL.optionalFieldOf("boss", false).forGetter(PetSpecies::boss),
            Codec.DOUBLE.optionalFieldOf("sizeScale", DEFAULT_MULT).forGetter(PetSpecies::sizeScale),
            Codec.DOUBLE.optionalFieldOf("healthMult", DEFAULT_MULT).forGetter(PetSpecies::healthMult),
            Codec.DOUBLE.optionalFieldOf("attackMult", DEFAULT_MULT).forGetter(PetSpecies::attackMult),
            Codec.LONG.optionalFieldOf("killGold", 0L).forGetter(PetSpecies::killGold),
            Codec.DOUBLE.optionalFieldOf("obtainPercent").forGetter(s -> Optional.of(s.obtainPercent()))
    ).apply(i, (kind, pet, atk, spd, atkSpd, fly, climb, boss, size, hp, atkMult, gold, obtain) ->
            new PetSpecies("", kind, pet, atk, spd, atkSpd, fly, climb, boss, size, hp, atkMult, gold,
                    obtain.orElse(boss ? 20.0 : 0.0))));

    PetSpecies withId(String newId) {
        return new PetSpecies(newId, kind, petEntity, attack, speed, attackSpeed, flySpeed, climbSpeed, boss,
                sizeScale, healthMult, attackMult, killGold, obtainPercent);
    }
}
