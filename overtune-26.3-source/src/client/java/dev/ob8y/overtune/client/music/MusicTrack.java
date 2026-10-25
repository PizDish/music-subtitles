package dev.ob8y.overtune.client.music;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Vanilla music event + human readable title. */
public record MusicTrack(Identifier id, SoundEvent soundEvent, String title, String album) {
    public String configId() { return id.toString(); }
}
