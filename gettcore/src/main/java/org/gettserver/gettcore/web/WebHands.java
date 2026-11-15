package org.gettserver.gettcore.web;

import java.lang.reflect.Method;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.UseAnim;
import net.neoforged.fml.ModList;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.reinforce.ReinforceTargets;

/**
 * 발사기를 어느 손에 찼는지에 따른 규칙. 오른손(주 손): 줄 이동 + 전투(쏘기·묶기·슬링샷). 왼손(보조 손): 줄 이동만,
 * 그것도 주 손의 아이템이 우클릭을 쓰지 않았을 때만. 주 손에 원거리·양손 무기를 들면 왼손 발사기는 쉰다.
 */
public final class WebHands {
    /** 주 손에 들면 왼손 발사기를 못 쓰게 하는 아이템(자동 판정에 안 잡히는 것을 데이터팩으로 추가). */
    public static final TagKey<Item> BLOCKS_OFFHAND = TagKey.create(Registries.ITEM, GettCore.id("web_blocks_offhand"));

    private static boolean epicFightFailed;
    private static Method efItemCapability;
    private static Method efPlayerPatch;
    private static Method efStyle;
    private static Method efCanUseOffhand;

    private WebHands() {}

    /** Shooter on the main hand: the hand that fights. */
    public static boolean main(Player player) {
        return player.getMainHandItem().is(GettCore.WEB_SHOOTER.get());
    }

    public static boolean dual(Player player) {
        return main(player) && player.getOffhandItem().is(GettCore.WEB_SHOOTER.get());
    }

    /** 주 손의 아이템이 두 손을 다 쓰는가: 활·석궁·삼지창류, 총(NTGL), Epic Fight 의 양손·원거리 무기, 태그. */
    public static boolean mainBlocksOffhand(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.isEmpty() || main.is(GettCore.WEB_SHOOTER.get())) return false;
        if (main.getItem() instanceof ProjectileWeaponItem || main.is(BLOCKS_OFFHAND)) return true;
        UseAnim anim = main.getUseAnimation();
        if (anim == UseAnim.BOW || anim == UseAnim.CROSSBOW || anim == UseAnim.SPEAR) return true;
        if (ReinforceTargets.ntglWeapon.test(main)) return true;
        return epicFightTwoHanded(player, main);
    }

    /** Epic Fight 의 무기 자세가 보조 손을 못 쓰는 자세(양손·원거리)인가. 모드가 없거나 API 가 다르면 false. */
    private static boolean epicFightTwoHanded(Player player, ItemStack main) {
        if (epicFightFailed) return false;
        try {
            if (efItemCapability == null) {
                if (!ModList.get().isLoaded("epicfight")) {
                    epicFightFailed = true;
                    return false;
                }
                Class<?> caps = Class.forName("yesman.epicfight.world.capabilities.EpicFightCapabilities");
                Class<?> item = Class.forName("yesman.epicfight.world.capabilities.item.CapabilityItem");
                Class<?> patch = Class.forName("yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch");
                Class<?> style = Class.forName("yesman.epicfight.world.capabilities.item.Style");
                efPlayerPatch = caps.getMethod("getPlayerPatch", Player.class);
                efStyle = item.getMethod("getStyle", patch);
                efCanUseOffhand = style.getMethod("canUseOffhand");
                efItemCapability = caps.getMethod("getItemStackCapability", ItemStack.class);
            }
            Object patch = efPlayerPatch.invoke(null, player);
            Object capability = efItemCapability.invoke(null, main);
            if (patch == null || capability == null) return false;
            Object style = efStyle.invoke(capability, patch);
            return style != null && !(Boolean) efCanUseOffhand.invoke(style);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            epicFightFailed = true;
            GettCore.LOGGER.warn("gettcore: Epic Fight two-handed check unavailable: {}", e.toString());
            return false;
        }
    }
}
