package com.example.musicsubtitles;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа клиентского мода. Собирает компоненты:
 * <pre>
 *   MediaSessionTracker  — что сейчас играет и в какой позиции (фоновый поток)
 *   LyricsFetcher        — загрузка синхронизированного текста с LrcLib (фоновые потоки)
 *   SubtitleOverlay      — отрисовка в HUD (render-поток)
 * </pre>
 */
@Environment(EnvType.CLIENT)
public final class MusicSubtitlesMod implements ClientModInitializer {
    public static final String MOD_ID = "musicsubtitles";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        ModConfig.load();

        MediaSessionTracker tracker = new MediaSessionTracker();
        LyricsFetcher fetcher = new LyricsFetcher();
        SubtitleOverlay overlay = new SubtitleOverlay(tracker, fetcher);

        // Клавиша включения/выключения (по умолчанию F7, меняется в «Управление»)
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.musicsubtitles.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "category.musicsubtitles"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                ModConfig cfg = ModConfig.get();
                cfg.enabled = !cfg.enabled;
                ModConfig.save();
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable(cfg.enabled
                            ? "message.musicsubtitles.enabled"
                            : "message.musicsubtitles.disabled"), true); // true = над хотбаром
                }
            }
        });

        // Рендер поверх HUD. HudRenderCallback помечен устаревшим в новых версиях Fabric API,
        // но в 1.21.7 полностью работает; при желании можно перейти на HudElementRegistry.
        HudRenderCallback.EVENT.register(overlay::render);

        // Корректно гасим фоновые потоки и дочерний процесс PowerShell при выходе из игры
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            tracker.stop();
            fetcher.shutdown();
        });

        tracker.start();
        LOGGER.info("Music Subtitles инициализирован");
    }
}
