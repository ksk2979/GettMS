package org.gettserver.gettcore.pet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * One capturable species. {@code id} is the wild entity id (the key in pets.json, not a JSON field).
 * {@code jumpHeight}: blocks a ground mount jumps when its rider presses jump (0..10). {@code ballChance} /
 * {@code obtainChance}: per-tier overrides of the global capture / obtain tables (a missing tier, or an empty map,
 * uses the global table: the boss one for boss species). The legacy {@code obtainPercent} (&gt; 0) is read as an
 * obtainChance override on all four tiers when {@code obtainChance} is absent (except 20 on a boss species, the
 * 1.3.0 implicit default, which means no override); it is never written back.
 */
public record PetSpecies(String id, Kind kind, ResourceLocation petEntity, double attack, double speed,
                         double attackSpeed, double flySpeed, double climbSpeed, boolean boss, double sizeScale,
                         double healthMult, double attackMult, long killGold, double jumpHeight,
                         Map<CaptureTier, PetConfig.ChanceRange> ballChance, Map<CaptureTier, Double> obtainChance) {

    public PetSpecies {
        ballChance = ballChance == null ? Map.of() : Map.copyOf(ballChance);
        obtainChance = obtainChance == null ? Map.of() : Map.copyOf(obtainChance);
    }

    /** No capture/obtain overrides. */
    public PetSpecies(String id, Kind kind, ResourceLocation petEntity, double attack, double speed,
                      double attackSpeed, double flySpeed, double climbSpeed, boolean boss, double sizeScale,
                      double healthMult, double attackMult, long killGold, double jumpHeight) {
        this(id, kind, petEntity, attack, speed, attackSpeed, flySpeed, climbSpeed, boss, sizeScale, healthMult,
                attackMult, killGold, jumpHeight, Map.of(), Map.of());
    }

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
    static final double DEFAULT_ATTACK = 2.0;
    static final double DEFAULT_SPEED = 0.3;
    static final double DEFAULT_ATTACK_SPEED = 1.0;
    static final double DEFAULT_FLY_SPEED = 0.6;
    static final double DEFAULT_CLIMB_SPEED = 0.3;
    static final double DEFAULT_MULT = 1.0;
    public static final double DEFAULT_JUMP_HEIGHT = 1.0;
    static final double MAX_JUMP_HEIGHT = 10.0;

    /**
     * The implicit 1.3.0 boss obtain percent. The 1.3.0 pets.json and editor wrote it on every boss species, so a
     * boss species carrying exactly this legacy value gets no override (the boss tier table applies instead).
     */
    private static final double LEGACY_DEFAULT_OBTAIN_PERCENT = 20.0;

    /** Optional getter that leaves an empty override map out of the JSON. */
    private static <V> Optional<Map<CaptureTier, V>> nonEmpty(Map<CaptureTier, V> m) {
        return m.isEmpty() ? Optional.empty() : Optional.of(m);
    }

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
            Codec.DOUBLE.optionalFieldOf("jumpHeight", DEFAULT_JUMP_HEIGHT).forGetter(PetSpecies::jumpHeight),
            Codec.unboundedMap(CaptureTier.CODEC, PetConfig.ChanceRange.CODEC).optionalFieldOf("ballChance")
                    .forGetter(s -> nonEmpty(s.ballChance)),
            Codec.unboundedMap(CaptureTier.CODEC, Codec.DOUBLE).optionalFieldOf("obtainChance")
                    .forGetter(s -> nonEmpty(s.obtainChance)),
            // Legacy, read only: never encoded.
            Codec.DOUBLE.optionalFieldOf("obtainPercent").forGetter(s -> Optional.empty())
    ).apply(i, (kind, pet, atk, spd, atkSpd, fly, climb, boss, size, hp, atkMult, gold, jump, ball, obtain,
                legacy) -> new PetSpecies("", kind, pet, atk, spd, atkSpd, fly, climb, boss, size, hp, atkMult, gold,
            jump, ball.orElse(Map.of()), obtain.orElseGet(() -> legacy.filter(p -> p > 0)
                    .filter(p -> !(boss && p == LEGACY_DEFAULT_OBTAIN_PERCENT))
                    .map(PetConfig.Capture::allTiers).orElse(Map.of())))));

    PetSpecies withId(String newId) {
        return new PetSpecies(newId, kind, petEntity, attack, speed, attackSpeed, flySpeed, climbSpeed, boss,
                sizeScale, healthMult, attackMult, killGold, jumpHeight, ballChance, obtainChance);
    }
}
