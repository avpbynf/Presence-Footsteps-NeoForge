package com.minelittlepony.common.event;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;

/**
 * NeoForge port: Kirin's Fabric event plumbing is removed; only the
 * ButtonList contract used by IViewRoot is kept.
 */
@FunctionalInterface
public interface ScreenInitCallback {

    void init(Screen screen, ButtonList buttons);

    interface ButtonList {
        /**
         * Adds a button to this screen.
         * <p>
         * Made public to help with mod development.
         */
        <T extends GuiEventListener & Renderable & NarratableEntry> T addButton(T button);
    }
}
