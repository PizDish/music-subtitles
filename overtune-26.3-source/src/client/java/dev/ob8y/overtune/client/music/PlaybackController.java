package dev.ob8y.overtune.client.music;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

/** Basic sound control. This does not implement the original Overtune fading/seek engine. */
public final class PlaybackController {
    private final Minecraft client;
    private final MusicLibrary library;
    private SimpleSoundInstance currentSound;
    private MusicTrack currentTrack;
    private int currentIndex = -1;
    private boolean paused;
    private int ticksSincePlay;

    public PlaybackController(Minecraft client, MusicLibrary library) {
        this.client = client;
        this.library = library;
    }

    public MusicTrack nowPlaying() { return currentTrack; }
    public boolean isPaused() { return paused; }

    public void playIndex(int index) {
        List<MusicTrack> tracks = library.tracks();
        if (index < 0 || index >= tracks.size()) return;
        stop();
        currentIndex = index;
        currentTrack = tracks.get(index);
        currentSound = SimpleSoundInstance.forMusic(currentTrack.soundEvent());
        client.getSoundManager().play(currentSound);
        paused = false;
        ticksSincePlay = 0;
    }

    public void stop() {
        if (currentSound != null) client.getSoundManager().stop(currentSound);
        currentSound = null;
        currentTrack = null;
        currentIndex = -1;
        ticksSincePlay = 0;
        paused = false;
    }

    public void togglePause() {
        if (currentTrack == null) return;
        // Vanilla sound API does not provide reliable per-instance pause/resume.
        // Alpha fallback: stopping while paused, restarting on resume.
        if (!paused) {
            if (currentSound != null) client.getSoundManager().stop(currentSound);
            paused = true;
        } else {
            currentSound = SimpleSoundInstance.forMusic(currentTrack.soundEvent());
            client.getSoundManager().play(currentSound);
            paused = false;
            ticksSincePlay = 0;
        }
    }

    public void next() { playIndex(currentIndex + 1 < library.tracks().size() ? currentIndex + 1 : 0); }
    public void previous() { playIndex(currentIndex > 0 ? currentIndex - 1 : library.tracks().size() - 1); }

    public void tick() {
        if (currentSound == null || paused) return;
        if (++ticksSincePlay > 40 && !client.getSoundManager().isActive(currentSound)) {
            next();
        }
    }
}
