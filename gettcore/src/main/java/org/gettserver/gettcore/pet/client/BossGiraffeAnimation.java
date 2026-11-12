package org.gettserver.gettcore.pet.client;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;

/** Boss giraffe keyframe animations on Naturalist's GiraffeModel bones. Client only. */
public final class BossGiraffeAnimation {
    private BossGiraffeAnimation() {}

    /** Stomp: rears up through the 0.8 s charge, slams down by 0.85 s, the shoulders overshoot at 0.9 s. */
    public static final AnimationDefinition STOMP = AnimationDefinition.Builder.withLength(1.1F)
            .addAnimation("shoulders", rotX(0, 0, 0.6F, -35, 0.8F, -35, 0.85F, 0, 0.9F, 8, 1.0F, 0))
            .addAnimation("left_arm", rotX(0, 0, 0.6F, -50, 0.8F, -50, 0.85F, 0))
            .addAnimation("right_arm", rotX(0, 0, 0.6F, -50, 0.8F, -50, 0.85F, 0))
            .addAnimation("neck", rotX(0, 0, 0.6F, 20, 0.8F, 20, 0.85F, 0))
            .build();

    /**
     * 람각, timed to the default 1.0 s charge: the right foreleg draws back and the chest leans back through 0.8 s,
     * the leg kicks forward at the release (1.05 s) with the neck following through, all settled by 1.4 s.
     */
    public static final AnimationDefinition RAMGAK = AnimationDefinition.Builder.withLength(1.4F)
            .addAnimation("right_arm", rotX(0, 0, 0.8F, 40, 0.95F, 45, 1.05F, -65, 1.15F, -60, 1.4F, 0))
            .addAnimation("shoulders", rotX(0, 0, 0.8F, -10, 0.95F, -10, 1.05F, 6, 1.4F, 0))
            .addAnimation("neck", rotX(0, 0, 0.8F, -12, 0.95F, -12, 1.1F, 25, 1.4F, 0))
            .build();

    /** X rotation channel from (seconds, degrees) pairs, linear between them. */
    private static AnimationChannel rotX(float... timeDegrees) {
        Keyframe[] frames = new Keyframe[timeDegrees.length / 2];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new Keyframe(timeDegrees[i * 2], KeyframeAnimations.degreeVec(timeDegrees[i * 2 + 1], 0, 0),
                    AnimationChannel.Interpolations.LINEAR);
        }
        return new AnimationChannel(AnimationChannel.Targets.ROTATION, frames);
    }
}
