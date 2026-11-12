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
