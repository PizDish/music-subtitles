package dev.ob8y.overtune.client.music;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Read music sound events from the Minecraft registry (no original class files used). */
public final class MusicLibrary {
    private final List<MusicTrack> tracks = new ArrayList<>();

    public void refresh() {
        tracks.clear();
        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            if (!isMusic(id)) continue;
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(id);
            if (sound == null) continue;
            String path = id.getPath();
            String title = nice(path.substring(path.lastIndexOf('.') + 1));
            String album = path.contains("disc") ? "Music Discs" : "Minecraft Soundtrack";
            tracks.add(new MusicTrack(id, sound, title, album));
        }
        tracks.sort(Comparator.comparing(MusicTrack::album).thenComparing(MusicTrack::title));
    }

    public List<MusicTrack> tracks() { return List.copyOf(tracks); }

    private static boolean isMusic(Identifier id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        return path.startsWith("music.") || path.startsWith("music_disc.")
            || path.startsWith("record.") || path.equals("music");
    }

    private static String nice(String text) {
        String[] words = text.replace('_', ' ').replace('-', ' ').split(" ");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
