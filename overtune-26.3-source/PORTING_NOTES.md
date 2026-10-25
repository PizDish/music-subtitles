# Porting notes: original Overtune 1.0.0 → Minecraft 26.3

## Source JAR audit
- Source mod id `overtune`, package `dev.ob8y.overtune`, original author `OB8Y`.
- Original target: `~26.1.2` Fabric, Java 25, Fabric Loader >=0.19.2.
- Old build manifest: Loom 1.16.1, Gradle 9.4.1.
- Old mixin set: `ChannelAccessor`, `OptionsScreenMixin`, `PauseScreenMixin`, `SoundBufferLibraryMixin`, `SoundEngineAccessor`, `SoundManagerAccessor`.
- Original JAR has compiled `.class` files, not `.java` source; a safe full-functionality port requires original source or reimplementation and test cycles.

## 26.3 migration choices
1. Gradle/Fabric: use `net.fabricmc.fabric-loom`, *no* legacy `mappings` line; 26.3 uses official unobfuscated names.
2. Keyboard: replace GLFW with SDL3-based `InputConstants.KEY_*`, `InputConstants.Type.KEYBOARD`.
3. GUI: `Screen.extractRenderState(GuiGraphicsExtractor,...)`, `extractBackground`, `Minecraft.gui.setScreen`.
4. Sound: basic playback uses vanilla `SimpleSoundInstance.forMusic` and `SoundManager.play/stop/isActive`.
5. Remove *old* mixins rather than silently copying bytecode into 26.3. This alpha lacks their original functions.
6. Test audio, save/load, menus, world switches, and event music separately before marking a stable release.

## Remaining high-priority work
- Obtain original source legally; migrate original classes and six mixins one at a time.
- Test against 26.3 with Java 25; fix any renamed APIs/build errors.
- Add imported OGG audio streams with resource reload and safe path traversal checks.
- Design exact pause/resume, volume and seeking without conflicting with vanilla sound engine.
- Restore persistent playlists/likes, search and album art.
- Restore world/event triggers and avoid duplicate vanilla music.
- Verify license conflict between embedded MIT and external mod listing.

## Version reference
- Minecraft: 26.3, Java 25
- Fabric Loader: 0.20.1
- Fabric API: 0.165.0+26.3
- Loom: 1.19-SNAPSHOT
- Gradle: 9.8.0 (Actions)

Update versions to latest compatible releases when building.
