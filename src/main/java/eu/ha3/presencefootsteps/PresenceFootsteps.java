package eu.ha3.presencefootsteps;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import com.minelittlepony.common.client.gui.GameGui;
import com.minelittlepony.common.util.GamePaths;
import com.mojang.blaze3d.platform.InputConstants;

import eu.ha3.presencefootsteps.sound.SoundEngine;
import eu.ha3.presencefootsteps.util.Edge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = PresenceFootsteps.MODID, dist = Dist.CLIENT)
public class PresenceFootsteps {
    public static final Logger logger = LogManager.getLogger("PFSolver");

    static final String MODID = "presencefootsteps";
    private static final KeyMapping.Category KEY_BINDING_CATEGORY = KeyMapping.Category.register(id("category"));

    public static final Component MOD_NAME = Component.translatable("mod.presencefootsteps.name");

    public static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(MODID, name);
    }

    private static PresenceFootsteps instance;

    public static PresenceFootsteps getInstance() {
        return instance;
    }

    // NeoForge port: upstream's update checker used to create this directory as
    // a side effect. Kirin's config adapter does not create parent directories,
    // so without this the config would never be written to disk.
    private final Path pfFolder = createDirectories(GamePaths.getConfigDirectory().resolve("presencefootsteps"));

    private static Path createDirectories(Path path) {
        try {
            java.nio.file.Files.createDirectories(path);
        } catch (java.io.IOException e) {
            logger.error("Could not create config directory {}", path, e);
        }
        return path;
    }

    private final PFConfig config = new PFConfig(pfFolder.resolve("userconfig.json"), this);
    private final SoundEngine engine = new SoundEngine(config);
    private final PFDebugHud debugHud = new PFDebugHud(engine);

    private final KeyMapping optionsKeyBinding = new KeyMapping("key.presencefootsteps.settings", InputConstants.Type.KEYSYM, InputConstants.KEY_F10, KEY_BINDING_CATEGORY);
    private final KeyMapping toggleKeyBinding = new KeyMapping("key.presencefootsteps.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_BINDING_CATEGORY);
    private final KeyMapping debugToggleKeyBinding = new KeyMapping("key.presencefootsteps.debug_toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_Z, KEY_BINDING_CATEGORY);
    private final Edge toggler = new Edge(z -> {
        if (z) {
            config.toggleDisabled();
        }
    });
    private final Edge debugToggle = new Edge(z -> {
        if (z) {
            Minecraft.getInstance().debugEntries.toggleStatus(PFDebugHud.ID);
        }
    });

    private final AtomicBoolean configChanged = new AtomicBoolean();

    public PresenceFootsteps(IEventBus modBus, ModContainer container) {
        instance = this;

        config.load();
        config.onChangedExternally(_ -> configChanged.set(true));

        modBus.addListener(this::onRegisterDebugEntries);
        modBus.addListener(this::onRegisterKeyMappings);
        modBus.addListener(this::onAddClientReloadListeners);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> onTick(Minecraft.getInstance()));

        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new PFOptionsScreen(parent));
    }

    public PFDebugHud getDebugHud() {
        return debugHud;
    }

    public SoundEngine getEngine() {
        return engine;
    }

    public PFConfig getConfig() {
        return config;
    }

    public KeyMapping getOptionsKeyBinding() {
        return optionsKeyBinding;
    }

    private void onRegisterDebugEntries(RegisterDebugEntriesEvent event) {
        event.register(PFDebugHud.ID, debugHud);
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(optionsKeyBinding);
        event.register(toggleKeyBinding);
        event.register(debugToggleKeyBinding);
    }

    private void onAddClientReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(SoundEngine.ID, engine);
    }

    private void onTick(Minecraft client) {
        if (client.screen instanceof PFOptionsScreen screen && configChanged.getAndSet(false)) {
            screen.init(screen.width, screen.height);
        }

        debugToggle.accept(GameGui.isKeyDown(InputConstants.KEY_F3) && debugToggleKeyBinding.isDown());

        Optional.ofNullable(client.player).filter(e -> !e.isRemoved()).ifPresent(cameraEntity -> {
            if (client.screen == null) {
                if (optionsKeyBinding.isDown()) {
                    client.setScreen(new PFOptionsScreen(client.screen));
                }
                toggler.accept(toggleKeyBinding.isDown());
            }

            engine.onFrame(client, cameraEntity);
        });
    }

    void onEnabledStateChange(boolean enabled) {
        engine.reload();
        showSystemToast(
                MOD_NAME,
                Component.translatable("key.presencefootsteps.toggle." + (enabled ? "enabled" : "disabled")).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.GRAY)
        );
    }

    public void showSystemToast(Component title, Component body) {
        Minecraft client = Minecraft.getInstance();
        client.getToastManager().addToast(SystemToast.multiline(client, SystemToast.SystemToastId.PACK_LOAD_FAILURE, title, body));
    }
}
