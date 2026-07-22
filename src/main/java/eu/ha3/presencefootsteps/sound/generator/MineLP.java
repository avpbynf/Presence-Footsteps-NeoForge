package eu.ha3.presencefootsteps.sound.generator;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * NeoForge port: Mine Little Pony is Fabric-only, so this is a no-op stub.
 */
public class MineLP {
    public static boolean hasPonies() {
        return false;
    }

    public static Locomotion getLocomotion(Entity entity, Locomotion fallback) {
        return fallback;
    }

    public static Locomotion getLocomotion(Player ply) {
        return Locomotion.BIPED;
    }
}
