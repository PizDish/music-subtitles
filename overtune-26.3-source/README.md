# Overtune Community Port · Minecraft 26.3 (alpha)

**Status: PARTIAL SOURCE REWRITE / NOT A COMPLETE PORT. UNTESTED.**

This repository provides an editable Java/Fabric project targeting Minecraft Java **26.3**. It is a clean-source partial reconstruction based on an uploaded Overtune 1.0.0 JAR originally built for **26.1.2**. It is **not** the original application's complete source and **not** a tested replacement for it. No decompiled Java files, original binary code, or third-party album artwork is bundled.

## Implemented in source (alpha)
- 26.3 Fabric/Java 25 build configuration with CI on GitHub.
- SDL3-era keybinding (default **M**) via `InputConstants.Type.KEYBOARD`.
- Browse sound events tagged as music in the Minecraft registry.
- Basic playback, stop, track skipping, and best-effort automatic next track.
- Simple music browser implemented with `GuiGraphicsExtractor`.
- English and Russian hotkey localization.

## Features NOT ported (yet)
- Overtune's original polished UI and advanced library filters.
- Custom MP3/FLAC/M4A/AAC/Opus song imports and ffmpeg setup.
- Exact per-instance pause/resume, stream seeking and fades.
- Playlists, shuffle, repeat, likes, persisted configuration.
- Event-triggered soundtrack selection and vanilla-music suppression.
- Original mixin features (`SoundBufferLibrary`, options/pause screen hooks and audio channel accessors).

**Current pause implementation stops the instance and restarts the song on resume**. This is temporary, not feature parity.

## Build

Requirements: **JDK 25**, **Gradle 9.8.x**, network access to Fabric/Mojang Maven repositories.

```bash
gradle build
```

The target output is `build/libs/overtune-community-26.3-1.0.0-community.26.3-alpha.jar` (name/version may differ). Install it with Fabric Loader and Fabric API compatible with Minecraft 26.3. **Remove the original 26.1.2 JAR first** because the mod ID is the same (`overtune`).

In GitHub: upload these files into a new repository, then check the **Actions** tab for real compilation results. There is no Gradle wrapper JAR here because this environment could not download Gradle distribution files; CI uses an installed Gradle via setup-gradle.

## Origin and license

The provided Overtune 1.0.0 JAR embeds an **MIT** text license attributing copyright to **OB8Y**. The Modrinth listing appears to use a different license label. Before republishing original branding or artwork, verify the upstream distribution terms with the author. This package does not include original album artwork or the old JAR. Retain original attribution.

## Developer notes

See [PORTING_NOTES.md](PORTING_NOTES.md) and [reference/class-signatures.txt](reference/class-signatures.txt). They document original public/private class signatures for migration planning; the latter is **not** equivalent to editable decompiled Java source.
