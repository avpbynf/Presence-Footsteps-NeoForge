package com.minelittlepony.common.util;

import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Path;

/**
 * Provides access to all of the basic paths needed to interact with the game.
 * <p>
 * NeoForge port: backed by FMLPaths instead of FabricLoader.
 *
 * @author     Sollace
 */
public class GamePaths {

    private GamePaths() {}

    /**
     * Gets the current game (root) direction as a Path.
     */
    public static Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    /**
     * Gets the current game config direction as a Path.
     */
    public static Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }
}
