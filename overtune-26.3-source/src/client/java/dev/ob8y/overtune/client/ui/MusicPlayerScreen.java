package dev.ob8y.overtune.client.ui;

import dev.ob8y.overtune.client.music.MusicLibrary;
import dev.ob8y.overtune.client.music.MusicTrack;
import dev.ob8y.overtune.client.music.PlaybackController;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Simple Minecraft 26.3 GUI using the new extractor-based rendering path. */
public final class MusicPlayerScreen extends Screen {
    private static final int PAGE_SIZE = 7;
    private final PlaybackController playback;
    private final MusicLibrary library;
    private int page;

    public MusicPlayerScreen(PlaybackController playback, MusicLibrary library) {
        super(Component.literal("Overtune • Community Port"));
        this.playback = playback;
        this.library = library;
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 134;
        int top = Math.max(35, this.height / 2 - 102);
        List<MusicTrack> tracks = library.tracks();
        page = Math.min(page, Math.max(0, (tracks.size() - 1) / PAGE_SIZE));
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int absolute = page * PAGE_SIZE + i;
            if (absolute >= tracks.size()) break;
            MusicTrack track = tracks.get(absolute);
            addRenderableWidget(Button.builder(Component.literal(track.title()),
                button -> playback.playIndex(absolute))
                .bounds(left, top + i * 23, 268, 20).build());
        }
        int y = top + PAGE_SIZE * 23 + 8;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
            .bounds(left, y, 28, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▶/Ⅱ"), b -> playback.togglePause())
            .bounds(left + 32, y, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("■"), b -> playback.stop())
            .bounds(left + 88, y, 31, 20).build());
        addRenderableWidget(Button.builder(Component.literal("|<"), b -> playback.previous())
            .bounds(left + 124, y, 38, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">|"), b -> playback.next())
            .bounds(left + 166, y, 38, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
            .bounds(left + 209, y, 28, 20).build());
    }

    private void changePage(int delta) {
        int pages = Math.max(1, (library.tracks().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.floorMod(page + delta, pages);
        // Rebuild on a fresh screen to avoid manipulating widget internals.
        this.minecraft.gui.setScreen(this.copyPage());
    }

    private MusicPlayerScreen copyPage() {
        MusicPlayerScreen next = new MusicPlayerScreen(playback, library);
        next.page = page;
        return next;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xE6101420);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int top = Math.max(35, this.height / 2 - 102);
        graphics.centeredText(this.font, Component.literal("OVERTUNE · 26.3"), width / 2, top - 26, 0xFFB5E7FF);
        String description = "Tracks: " + library.tracks().size() + " | Page " + (page + 1);
        graphics.centeredText(this.font, Component.literal(description), width / 2, top - 13, 0xFFAAB2C8);
        MusicTrack track = playback.nowPlaying();
        String label = track == null ? "No track selected" : (playback.isPaused() ? "Paused: " : "Playing: ") + track.title();
        graphics.centeredText(this.font, Component.literal(label), width / 2, top + PAGE_SIZE * 23 + 32, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
