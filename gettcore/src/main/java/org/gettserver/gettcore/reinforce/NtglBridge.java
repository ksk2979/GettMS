package org.gettserver.gettcore.reinforce;

import com.nukateam.ntgl.common.foundation.entity.ProjectileEntity;
import com.nukateam.ntgl.common.foundation.event.MeleeWeaponModifiersEvent;
import com.nukateam.ntgl.common.foundation.item.WeaponItem;
import java.lang.reflect.Field;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.gettserver.gettcore.GettCore;

/**
 * NTGL(Create: Gunsmithing 의 총 라이브러리) 를 참조하는 유일한 클래스. 호출자는 반드시
 * {@code ModList.get().isLoaded("ntgl")} 를 먼저 확인해야 한다 — 그래야 NTGL 이 없는 서버/클라이언트가 이 클래스(와 NTGL
 * 클래스)를 로드하지 않는다. 진입점은 {@link #install()} 하나.
 *
 * <p>탄환 배율: NTGL 3.2.0 은 {@code GunProjectileSpawnEvent} 를 정의만 하고 어디서도 post 하지 않는다(javap 확인).
 * 탄환 피해는 {@code ProjectileEntity.getDamage()} 에서 {@code protected float damageMultiplier}(생성자 기본 1, 저장 안 됨,
 * setter 없음)를 곱하므로, 서버에서 탄환이 월드에 들어올 때({@link EntityJoinLevelEvent}, NTGL 은
 * {@code setWeapon} 다음 {@code addFreshEntity}) 그 탄환의 {@code getWeapon()} 단계로 이 필드에 배율을 곱한다(리플렉션,
 * mixin 아님). 필드를 못 찾으면 경고 한 번 남기고 총 배율만 빠진다.
 */
public final class NtglBridge {
    private static final Field DAMAGE_MULTIPLIER = findDamageMultiplier();

    private NtglBridge() {}

    /** NTGL 무기(총·망치 등 {@link WeaponItem} 하위). */
    public static boolean isWeapon(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof WeaponItem;
    }

    /** GettCore 생성자에서 {@code isLoaded("ntgl")} 분기 안에서만 호출. */
    public static void install() {
        ReinforceTargets.ntglWeapon = NtglBridge::isWeapon;
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, NtglBridge::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(MeleeWeaponModifiersEvent.Damage.class, NtglBridge::onMeleeDamage);
        GettCore.LOGGER.info("gettcore: NTGL found — gun reinforce enabled (projectile multiplier {})",
                DAMAGE_MULTIPLIER != null ? "on" : "unavailable");
    }

    private static void onEntityJoin(EntityJoinLevelEvent event) {
        if (DAMAGE_MULTIPLIER == null || event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ProjectileEntity projectile)) return;
        ItemStack weapon = projectile.getWeapon();
        if (!isWeapon(weapon)) return;
        int level = ReinforceRules.level(weapon);
        if (level <= 0) return;
        double mult = ReinforceRules.gunMultiplier(level, ReinforceEffects.config(false));
        if (mult == 1) return;
        try {
            // 생성자 기본값(1)·다른 배율과 곱연산. 디스크에서 다시 로드된 탄환도 필드가 1 로 새로 시작하므로 다시 곱한다.
            DAMAGE_MULTIPLIER.setFloat(projectile, (float) (DAMAGE_MULTIPLIER.getFloat(projectile) * mult));
        } catch (IllegalAccessException | RuntimeException e) {
            GettCore.LOGGER.warn("gettcore: could not apply reinforce multiplier to NTGL projectile", e);
        }
    }

    /** NTGL 근접(망치·총검 등) 피해. 이벤트의 modifier 가 곧 피해값(WeaponModifierHelper.getMeleeDamage 의 반환). */
    private static void onMeleeDamage(MeleeWeaponModifiersEvent.Damage event) {
        if (event.getWeaponData() == null) return;
        ItemStack weapon = event.getWeaponData().weapon;
        if (weapon == null || !isWeapon(weapon)) return;
        int level = ReinforceRules.level(weapon);
        if (level <= 0) return;
        var wielder = event.getWeaponData().wielder;
        ReinforceConfig cfg = wielder != null ? ReinforceEffects.config(wielder.level().isClientSide())
                : ReinforceEffects.config();
        event.setModifier((float) (event.getModifier() * ReinforceRules.gunMultiplier(level, cfg)));
    }

    private static Field findDamageMultiplier() {
        try {
            Field f = ProjectileEntity.class.getDeclaredField("damageMultiplier");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            GettCore.LOGGER.warn("gettcore: NTGL ProjectileEntity.damageMultiplier not accessible; "
                    + "reinforced guns will not deal extra projectile damage", e);
            return null;
        }
    }
}
