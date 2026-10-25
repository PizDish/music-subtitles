package com.example.musicsubtitles;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Отслеживает, какой трек сейчас играет в системе.
 *
 * <h3>Как это работает</h3>
 * <ul>
 *   <li><b>Windows</b>: Spotify (в т.ч. со Spicetify), приложение Яндекс Музыки и браузеры
 *   (SoundCloud / Яндекс Музыка в вебе) публикуют своё состояние в системный
 *   Media Session (SMTC — Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager).
 *   WinRT через JNA практически нереализуем без тяжёлой обвязки COM, поэтому мы запускаем
 *   ОДИН долгоживущий скрытый процесс {@code powershell.exe} (Windows PowerShell 5.1, есть в каждой
 *   Windows 10/11), который ~2.5 раза в секунду печатает JSON со всеми активными медиа-сессиями.</li>
 *   <li><b>Linux</b>: MPRIS через утилиту {@code playerctl} (нужно установить отдельно).</li>
 *   <li><b>macOS</b>: не поддерживается.</li>
 * </ul>
 *
 * Результат публикуется как неизменяемый {@link Snapshot} в volatile-поле, поэтому его можно
 * свободно читать из render-потока без блокировок.
 */
@Environment(EnvType.CLIENT)
public final class MediaSessionTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger("MusicSubtitles/Tracker");

    /**
     * Скрипт PowerShell. Печатает по одной JSON-строке (массив сессий) каждые ~400 мс.
     * Вся «магия» с AsTask нужна, потому что PowerShell 5.1 не умеет напрямую await-ить
     * WinRT-операции IAsyncOperation.
     */
    private static final String POWERSHELL_SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
                $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
                $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
            function Await($op, $type) {
                $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
                [void]$task.Wait(-1)
                $task.Result
            }
            $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
            $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
            $mgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
            $propType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
            $mgr = Await ($mgrType::RequestAsync()) $mgrType
            while ($true) {
                $list = New-Object System.Collections.ArrayList
                try {
                    foreach ($s in $mgr.GetSessions()) {
                        try {
                            $pb = $s.GetPlaybackInfo()
                            $props = Await ($s.TryGetMediaPropertiesAsync()) $propType
                            $tl = $s.GetTimelineProperties()
                            $rate = 1.0
                            if ($pb.PlaybackRate -ne $null) { $rate = [double]$pb.PlaybackRate }
                            [void]$list.Add([ordered]@{
                                src    = [string]$s.SourceAppUserModelId
                                status = $pb.PlaybackStatus.ToString()
                                artist = [string]$props.Artist
                                title  = [string]$props.Title
                                album  = [string]$props.AlbumTitle
                                pos    = [long]$tl.Position.TotalMilliseconds
                                end    = [long]$tl.EndTime.TotalMilliseconds
                                upd    = [long]$tl.LastUpdatedTime.ToUnixTimeMilliseconds()
                                rate   = $rate
                            })
                        } catch { }
                    }
                } catch { }
                [Console]::Out.WriteLine((ConvertTo-Json -InputObject @($list) -Compress -Depth 4))
                [Console]::Out.Flush()
                Start-Sleep -Milliseconds 400
            }
            """;

    /** Описание трека. */
    public record Track(String source, String artist, String title, String album, long durationMs) {
        /** Ключ для кэша и определения смены трека (источник и длительность не учитываются). */
        public String lyricsKey() {
            return (artist + "|" + title).toLowerCase(Locale.ROOT).strip();
        }
    }

    /**
     * Снимок состояния плеера. Текущая позиция НЕ хранится «как есть» — вместо этого хранится
     * опорная точка (basePositionMs в момент baseWallMs), от которой позиция экстраполируется
     * по системным часам. Благодаря этому позиция обновляется плавно каждый кадр,
     * даже если сам плеер сообщает её раз в несколько секунд.
     */
    public record Snapshot(Track track, boolean playing, long basePositionMs, long baseWallMs,
                           double rate, long rawPositionMs, boolean timelineSupported) {

        /** Оценка текущей позиции воспроизведения в миллисекундах. */
        public long estimatePositionMs() {
            long pos = basePositionMs;
            if (playing) {
                pos += (long) ((System.currentTimeMillis() - baseWallMs) * rate);
            }
            if (track.durationMs() > 0) {
                pos = Math.min(pos, track.durationMs());
            }
            return Math.max(pos, 0);
        }
    }

    /** Сырые данные одной медиа-сессии от ОС. */
    private record RawSession(String source, String status, String artist, String title, String album,
                              long positionMs, long endMs, long updatedMs, double rate) {
        boolean isPlaying() {
            return "Playing".equalsIgnoreCase(status);
        }

        boolean isPaused() {
            return "Paused".equalsIgnoreCase(status);
        }
    }

    private volatile Snapshot snapshot;
    private volatile boolean running;
    private volatile Process process;
    private Thread worker;

    /** Последний снимок или {@code null}, если ничего не играет. Потокобезопасно. */
    public Snapshot getSnapshot() {
        return snapshot;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::run, "MusicSubtitles-MediaTracker");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
        }
        if (worker != null) {
            worker.interrupt();
        }
        snapshot = null;
    }

    // ------------------------------------------------------------------------------------------
    // Рабочий поток
    // ------------------------------------------------------------------------------------------

    private void run() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                runWindows();
            } else if (os.contains("linux")) {
                runLinux();
            } else {
                LOGGER.warn("Операционная система '{}' не поддерживается: треки определяться не будут", os);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOGGER.error("Трекер медиа-сессий аварийно остановлен", t);
        }
    }

    // ---- Windows ----

    private void runWindows() throws InterruptedException {
        // -EncodedCommand принимает base64 от UTF-16LE — так не нужно экранировать кавычки и переводы строк
        String encoded = Base64.getEncoder()
                .encodeToString(POWERSHELL_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        int quickFailures = 0;

        while (running) {
            long startedAt = System.currentTimeMillis();
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                        "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                        "-EncodedCommand", encoded);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                process = p;
                p.getOutputStream().close(); // stdin нам не нужен

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        handleWindowsLine(line);
                    }
                }
                p.destroyForcibly();
            } catch (IOException e) {
                LOGGER.warn("Не удалось запустить/прочитать PowerShell: {}", e.toString());
            }

            if (!running) {
                break;
            }
            snapshot = null;

            // Защита от бесконечного цикла перезапусков, если PowerShell недоступен
            quickFailures = (System.currentTimeMillis() - startedAt < 5_000) ? quickFailures + 1 : 0;
            if (quickFailures >= 5) {
                LOGGER.error("PowerShell завершается сразу после запуска — отслеживание музыки отключено");
                return;
            }
            Thread.sleep(2_000);
        }
    }

    private void handleWindowsLine(String line) {
        if (line.startsWith("\uFEFF")) {
            line = line.substring(1);
        }
        line = line.strip();
        if (line.isEmpty() || (line.charAt(0) != '[' && line.charAt(0) != '{')) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(line);
            List<RawSession> sessions = new ArrayList<>();
            if (root.isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray()) {
                    if (e.isJsonObject()) {
                        sessions.add(parseRaw(e.getAsJsonObject()));
                    }
                }
            } else if (root.isJsonObject()) {
                sessions.add(parseRaw(root.getAsJsonObject()));
            }
            ingest(sessions);
        } catch (RuntimeException e) {
            LOGGER.debug("Не удалось разобрать строку от PowerShell: {}", line, e);
        }
    }

    private static RawSession parseRaw(JsonObject o) {
        return new RawSession(
                str(o, "src"), str(o, "status"), str(o, "artist"), str(o, "title"), str(o, "album"),
                num(o, "pos"), num(o, "end"), num(o, "upd"),
                o.has("rate") && !o.get("rate").isJsonNull() ? o.get("rate").getAsDouble() : 1.0);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? 0L : e.getAsLong();
    }

    // ---- Linux (MPRIS через playerctl) ----

    private void runLinux() throws InterruptedException {
        // Поля разделены табуляцией; позиция и длительность в микросекундах
        String format = String.join("\t",
                "{{playerName}}", "{{status}}", "{{artist}}", "{{title}}", "{{album}}",
                "{{mpris:length}}", "{{position}}");
        boolean warned = false;

        while (running) {
            try {
                Process p = new ProcessBuilder("playerctl", "-a", "metadata", "--format", format)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                process = p;
                p.getOutputStream().close();

                List<RawSession> sessions = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] f = line.split("\t", -1);
                        if (f.length < 7) {
                            continue;
                        }
                        sessions.add(new RawSession(f[0], f[1], f[2], f[3], f[4],
                                parseLongSafe(f[6]) / 1000, parseLongSafe(f[5]) / 1000, 0L, 1.0));
                    }
                }
                p.waitFor(2, TimeUnit.SECONDS);
                p.destroyForcibly();
                ingest(sessions);
            } catch (IOException e) {
                if (!warned) {
                    LOGGER.warn("Не удалось запустить 'playerctl' (установите его: sudo apt install playerctl): {}",
                            e.toString());
                    warned = true;
                }
            }
            Thread.sleep(500);
        }
    }

    private static long parseLongSafe(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Выбор активной сессии и вычисление опорной точки позиции
    // ------------------------------------------------------------------------------------------

    private void ingest(List<RawSession> sessions) {
        ModConfig cfg = ModConfig.get();
        Snapshot prev = snapshot;
        String prevSource = prev != null ? prev.track().source() : null;

        // Выбираем лучшую сессию: играющая > на паузе; при равенстве — та, что была выбрана раньше
        // (чтобы субтитры не «перепрыгивали» между двумя одновременно играющими плеерами).
        RawSession chosen = null;
        double bestScore = -1;
        for (RawSession s : sessions) {
            if (s.title().isBlank() || !matchesFilter(s.source(), cfg.sourceFilter)) {
                continue;
            }
            if (!s.isPlaying() && !s.isPaused()) {
                continue;
            }
            double score = (s.isPlaying() ? 2.0 : 1.0) + (s.source().equals(prevSource) ? 0.5 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                chosen = s;
            }
        }

        if (chosen == null) {
            snapshot = null;
            return;
        }

        Track track = new Track(chosen.source(), chosen.artist().strip(), chosen.title().strip(),
                chosen.album().strip(), Math.max(0, chosen.endMs()));
        boolean playing = chosen.isPlaying();
        double rate = chosen.rate() > 0 ? chosen.rate() : 1.0;
        long now = System.currentTimeMillis();

        boolean sameTrack = prev != null
                && prev.track().lyricsKey().equals(track.lyricsKey())
                && prev.track().source().equals(track.source());

        // Плеер «поддерживает таймлайн», если он сообщает длительность или позицию
        boolean timeline = chosen.endMs() > 0 || chosen.positionMs() > 0;

        long basePos;
        long baseWall;
        if (timeline) {
            long upd = chosen.updatedMs();
            if (upd > 0 && Math.abs(now - upd) < 6L * 3_600_000L) {
                // Лучший случай: ОС сообщает позицию И момент её обновления — экстраполируем от него
                basePos = chosen.positionMs();
                baseWall = upd;
            } else if (sameTrack && prev.timelineSupported()
                    && prev.rawPositionMs() == chosen.positionMs() && prev.playing() == playing) {
                // Позиция не менялась с прошлого опроса — сохраняем старую опорную точку и идём дальше по часам
                basePos = prev.basePositionMs();
                baseWall = prev.baseWallMs();
            } else {
                // Позиция изменилась (перемотка/первый опрос) — новая опорная точка «сейчас»
                basePos = chosen.positionMs();
                baseWall = now;
            }
        } else {
            // Плеер вообще не отдаёт позицию: считаем от момента обнаружения трека (приблизительно!)
            if (!sameTrack) {
                basePos = 0;
                baseWall = now;
            } else if (prev.playing() == playing) {
                basePos = prev.basePositionMs();
                baseWall = prev.baseWallMs();
            } else {
                // Пауза/возобновление: фиксируем накопленную позицию
                basePos = prev.estimatePositionMs();
                baseWall = now;
            }
        }

        snapshot = new Snapshot(track, playing, basePos, baseWall, rate, chosen.positionMs(), timeline);
    }

    private static boolean matchesFilter(String source, List<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        String s = source.toLowerCase(Locale.ROOT);
        for (String f : filter) {
            if (f != null && !f.isBlank() && s.contains(f.toLowerCase(Locale.ROOT).strip())) {
                return true;
            }
        }
        return false;
    }
}
