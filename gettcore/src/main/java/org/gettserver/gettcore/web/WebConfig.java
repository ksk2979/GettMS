package org.gettserver.gettcore.web;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * web.json: 거미줄 발사기 수치.
 *
 * @param range                사거리(칸)
 * @param shotSpeed            줄 끝이 날아가는 속도(칸/초)
 * @param reelSpeed            달리기 키로 당기고 웅크리기 키로 푸는 속도(칸/초)
 * @param minLength            줄을 당길 수 있는 가장 짧은 길이(칸)
 * @param swingBoost           매달려 앞으로 키를 누를 때 틱마다 더해지는 속도(칸/틱)
 * @param maxSpeed             가속으로 낼 수 있는 최고 속도(칸/초)
 * @param cooldownSeconds      다시 쏠 수 있을 때까지의 시간(초, 0 = 없음)
 * @param landingDamagePercent 줄을 쓴 뒤 착지할 때 받는 낙하 피해 비율(%)
 * @param rollMinFall          이 높이(칸) 이상 떨어져 착지하면 구르기 동작
 * @param wallCling            발사기를 찬 동안 공중에서 벽에 붙을 수 있는지
 * @param climbSpeed           벽에 붙어 움직이는 속도(칸/초)
 * @param combat               양손에 하나씩 찼을 때의 전투 수치
 * @param sling                양손 슬링샷(앉아서 모았다가 보는 방향으로 발사) 수치
 */
public record WebConfig(double range, double shotSpeed, double reelSpeed, double minLength, double swingBoost,
                        double maxSpeed, double cooldownSeconds, int landingDamagePercent, double rollMinFall,
                        boolean wallCling, double climbSpeed, Combat combat, Sling sling) {
    public static final String FILE = "web.json";

    /**
     * @param dualSpeedPercent    양손 착용 시 이동속도 증가(%)
     * @param shotDamage          거미줄 쏘기 피해
     * @param shotCooldownSeconds 거미줄 쏘기 재사용(초)
     * @param bindSeconds         몹이 묶여 있는 시간(초)
     * @param bindPlayerSeconds   플레이어가 묶여 있는 시간(초)
     * @param bindCooldownSeconds 거미줄 묶기 재사용(초)
     * @param bindPlayers         플레이어도 묶을 수 있는지
     */
    public record Combat(double dualSpeedPercent, double shotDamage, double shotCooldownSeconds, double bindSeconds,
                         double bindPlayerSeconds, double bindCooldownSeconds, boolean bindPlayers) {
        public static final Combat DEFAULT = new Combat(15, 2, 0.1, 5, 2, 15, true);
        public static final Codec<Combat> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("dualSpeedPercent").forGetter(c -> Optional.of(c.dualSpeedPercent())),
                Codec.DOUBLE.optionalFieldOf("shotDamage").forGetter(c -> Optional.of(c.shotDamage())),
                Codec.DOUBLE.optionalFieldOf("shotCooldownSeconds").forGetter(c -> Optional.of(c.shotCooldownSeconds())),
                Codec.DOUBLE.optionalFieldOf("bindSeconds").forGetter(c -> Optional.of(c.bindSeconds())),
                Codec.DOUBLE.optionalFieldOf("bindPlayerSeconds").forGetter(c -> Optional.of(c.bindPlayerSeconds())),
                Codec.DOUBLE.optionalFieldOf("bindCooldownSeconds").forGetter(c -> Optional.of(c.bindCooldownSeconds())),
                Codec.BOOL.optionalFieldOf("bindPlayers").forGetter(c -> Optional.of(c.bindPlayers()))
        ).apply(i, (speed, damage, shotCd, bind, bindPlayer, bindCd, players) -> new Combat(
                speed.orElse(DEFAULT.dualSpeedPercent), damage.orElse(DEFAULT.shotDamage),
                shotCd.orElse(DEFAULT.shotCooldownSeconds), bind.orElse(DEFAULT.bindSeconds),
                bindPlayer.orElse(DEFAULT.bindPlayerSeconds), bindCd.orElse(DEFAULT.bindCooldownSeconds),
                players.orElse(DEFAULT.bindPlayers))));

        Combat sanitized(List<String> warnings) {
            return new Combat(
                    in("combat.dualSpeedPercent", dualSpeedPercent, 0, 200, DEFAULT.dualSpeedPercent, warnings),
                    in("combat.shotDamage", shotDamage, 0, 1000, DEFAULT.shotDamage, warnings),
                    in("combat.shotCooldownSeconds", shotCooldownSeconds, 0, 60, DEFAULT.shotCooldownSeconds, warnings),
                    in("combat.bindSeconds", bindSeconds, 0, 60, DEFAULT.bindSeconds, warnings),
                    in("combat.bindPlayerSeconds", bindPlayerSeconds, 0, 60, DEFAULT.bindPlayerSeconds, warnings),
                    in("combat.bindCooldownSeconds", bindCooldownSeconds, 0, 3600, DEFAULT.bindCooldownSeconds,
                            warnings),
                    bindPlayers);
        }
    }

    /**
     * @param power           다 모았을 때의 발사 속도(칸/초)
     * @param chargeSeconds   다 모으는 데 걸리는 시간(초)
     * @param minPowerPercent 누르자마자 놓았을 때의 세기(다 모은 것의 %)
     * @param glideSeconds    발사 뒤 속도가 줄지 않고 곧게 날아가는 시간(초)
     */
    public record Sling(double power, double chargeSeconds, double minPowerPercent, double glideSeconds) {
        public static final Sling DEFAULT = new Sling(70, 1.0, 30, 0.6);
        public static final Codec<Sling> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("power").forGetter(c -> Optional.of(c.power())),
                Codec.DOUBLE.optionalFieldOf("chargeSeconds").forGetter(c -> Optional.of(c.chargeSeconds())),
                Codec.DOUBLE.optionalFieldOf("minPowerPercent").forGetter(c -> Optional.of(c.minPowerPercent())),
                Codec.DOUBLE.optionalFieldOf("glideSeconds").forGetter(c -> Optional.of(c.glideSeconds()))
        ).apply(i, (power, charge, min, glide) -> new Sling(power.orElse(DEFAULT.power),
                charge.orElse(DEFAULT.chargeSeconds), min.orElse(DEFAULT.minPowerPercent),
                glide.orElse(DEFAULT.glideSeconds))));

        Sling sanitized(List<String> warnings) {
            return new Sling(in("sling.power", power, 0, 150, DEFAULT.power, warnings),
                    in("sling.chargeSeconds", chargeSeconds, 0.05, 30, DEFAULT.chargeSeconds, warnings),
                    in("sling.minPowerPercent", minPowerPercent, 0, 100, DEFAULT.minPowerPercent, warnings),
                    in("sling.glideSeconds", glideSeconds, 0, 10, DEFAULT.glideSeconds, warnings));
        }
    }

    public static final WebConfig DEFAULT = new WebConfig(32, 120, 8, 1.5, 0.03, 30, 0, 50, 3, true, 4,
            Combat.DEFAULT, Sling.DEFAULT);

    /** Every field is optional on read (a missing one means the default) but always written. */
    public static final Codec<WebConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.optionalFieldOf("range").forGetter(c -> Optional.of(c.range())),
            Codec.DOUBLE.optionalFieldOf("shotSpeed").forGetter(c -> Optional.of(c.shotSpeed())),
            Codec.DOUBLE.optionalFieldOf("reelSpeed").forGetter(c -> Optional.of(c.reelSpeed())),
            Codec.DOUBLE.optionalFieldOf("minLength").forGetter(c -> Optional.of(c.minLength())),
            Codec.DOUBLE.optionalFieldOf("swingBoost").forGetter(c -> Optional.of(c.swingBoost())),
            Codec.DOUBLE.optionalFieldOf("maxSpeed").forGetter(c -> Optional.of(c.maxSpeed())),
            Codec.DOUBLE.optionalFieldOf("cooldownSeconds").forGetter(c -> Optional.of(c.cooldownSeconds())),
            Codec.INT.optionalFieldOf("landingDamagePercent").forGetter(c -> Optional.of(c.landingDamagePercent())),
            Codec.DOUBLE.optionalFieldOf("rollMinFall").forGetter(c -> Optional.of(c.rollMinFall())),
            Codec.BOOL.optionalFieldOf("wallCling").forGetter(c -> Optional.of(c.wallCling())),
            Codec.DOUBLE.optionalFieldOf("climbSpeed").forGetter(c -> Optional.of(c.climbSpeed())),
            Combat.CODEC.optionalFieldOf("combat").forGetter(c -> Optional.of(c.combat())),
            Sling.CODEC.optionalFieldOf("sling").forGetter(c -> Optional.of(c.sling()))
    ).apply(i, (range, shot, reel, min, boost, max, cooldown, landing, roll, cling, climb, combat, sling) ->
            new WebConfig(
                    range.orElse(DEFAULT.range), shot.orElse(DEFAULT.shotSpeed), reel.orElse(DEFAULT.reelSpeed),
                    min.orElse(DEFAULT.minLength), boost.orElse(DEFAULT.swingBoost), max.orElse(DEFAULT.maxSpeed),
                    cooldown.orElse(DEFAULT.cooldownSeconds), landing.orElse(DEFAULT.landingDamagePercent),
                    roll.orElse(DEFAULT.rollMinFall), cling.orElse(DEFAULT.wallCling),
                    climb.orElse(DEFAULT.climbSpeed), combat.orElse(Combat.DEFAULT), sling.orElse(Sling.DEFAULT))));

    /** Server -> client copy (the client runs the swing). */
    public static final StreamCodec<ByteBuf, WebConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 16).map(
            WebConfig::fromJsonString, WebConfig::toJsonString);

    public static String toJsonString(WebConfig c) {
        return CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow(IllegalStateException::new).toString();
    }

    public static WebConfig fromJsonString(String s) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(s)).getOrThrow(IllegalStateException::new);
    }

    /** Values outside their allowed range become the default, one warning line each. */
    public WebConfig sanitized(List<String> warnings) {
        WebConfig c = new WebConfig(
                in("range", range, 4, 96, DEFAULT.range, warnings),
                in("shotSpeed", shotSpeed, 10, 2000, DEFAULT.shotSpeed, warnings),
                in("reelSpeed", reelSpeed, 0, 60, DEFAULT.reelSpeed, warnings),
                in("minLength", minLength, 0.5, 16, DEFAULT.minLength, warnings),
                in("swingBoost", swingBoost, 0, 0.5, DEFAULT.swingBoost, warnings),
                in("maxSpeed", maxSpeed, 1, 120, DEFAULT.maxSpeed, warnings),
                in("cooldownSeconds", cooldownSeconds, 0, 3600, DEFAULT.cooldownSeconds, warnings),
                (int) in("landingDamagePercent", landingDamagePercent, 0, 100, DEFAULT.landingDamagePercent, warnings),
                in("rollMinFall", rollMinFall, 0, 256, DEFAULT.rollMinFall, warnings),
                wallCling,
                in("climbSpeed", climbSpeed, 0.5, 20, DEFAULT.climbSpeed, warnings),
                combat.sanitized(warnings), sling.sanitized(warnings));
        return c.equals(this) ? this : c;
    }

    private static double in(String name, double value, double min, double max, double fallback,
                             List<String> warnings) {
        if (value >= min && value <= max) return value;
        warnings.add(FILE + ": " + name + " " + value + " must be " + min + ".." + max + " (set to " + fallback + ")");
        return fallback;
    }
}
