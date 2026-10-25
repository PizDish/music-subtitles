package dev.ob8y.overtune.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ob8y.overtune.client.music.MusicLibrary;
import dev.ob8y.overtune.client.music.PlaybackController;
import dev.ob8y.overtune.client.ui.MusicPlayerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Entry point for the independently rewritten, partial 26.3 port. */
public final class OvertuneClient implements ClientModInitializer {
    private static PlaybackController playback;
    private static MusicLibrary library;

    @Override
    public void onInitializeClient() {
        final KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("overtune", "player")
        );
        final KeyMapping open = KeyBindingHelper.registerKeyMapping(new KeyMapping(
            "key.overtune.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.consumeClick()) {
                openPlayer(client);
            }
            if (playback != null) playback.tick();
        });
    }

    public static void openPlayer(Minecraft client) {
        if (library == null) library = new MusicLibrary();
        if (playback == null) playback = new PlaybackController(client, library);
        library.refresh();
        client.gui.setScreen(new MusicPlayerScreen(playback, library));
    }
}
