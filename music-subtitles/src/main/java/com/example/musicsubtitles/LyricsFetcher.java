package com.example.musicsubtitles;

import com.example.musicsubtitles.MediaSessionTracker.Track;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Асинхронный загрузчик синхронизированных текстов (LRC) с публичного API LrcLib
 * (<a href="https://lrclib.net/docs">lrclib.net/docs</a>). Ключ API не требуется.
 *
 * Алгоритм поиска для каждого трека (до нескольких вариантов запроса, т.к. названия в плеерах «грязные»):
 * <ol>
 *   <li>{@code /api/get} — точное совпадение по исполнителю, названию, альбому и длительности;</li>
 *   <li>{@code /api/search} — поиск по названию/исполнителю, выбор результата с ближайшей длительностью;</li>
 *   <li>варианты запроса: исходные данные → «очищенное» название (без feat., remaster и скобок) →
 *       разбор заголовка вида «Исполнитель - Название» (типично для SoundCloud);</li>
 *   <li>последний шанс: свободный поиск {@code /api/search?q=...}.</li>
 * </ol>
 * Все результаты кэшируются в памяти, запросы выполняются в фоновых потоках — render-поток не блокируется.
 */
@Environment(EnvType.CLIENT)
public final class LyricsFetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("MusicSubtitles/Lyrics");
    private static final String USER_AGENT = "MusicSubtitlesFabric/1.0 (Minecraft client mod)";

    /** [mm:ss.xx] / [mm:ss.xxx] / [mm:ss] */
    private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** Посимвольные теги расширенного LRC: &lt;mm:ss.xx&gt; */
    private static final Pattern WORD_TAG = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private static final Pattern FEAT = Pattern.compile("(?i)\\s*[(\\[]?\\s*(feat\\.?|ft\\.?|featuring)\\s.*$");
    private static final Pattern TITLE_SUFFIX = Pattern.compile(
            "(?i)\\s+-\\s+(remaster(ed)?.*|\\d{4}\\s+remaster.*|live.*|radio edit.*|single version.*"
                    + "|album version.*|mono.*|stereo.*|bonus track.*)$");
    private static final Pattern BRACKETS = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]");
    private static final Pattern ARTIST_SPLIT = Pattern.compile("\\s*(?:,|;|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\sx\\s)\\s*",
            Pattern.CASE_INSENSITIVE);

    /** Одна строка текста с временем начала. */
    public record Line(long timeMs, String text) {
    }

    /** Результат: список строк, отсортированный по времени. */
    public record Lyrics(List<Line> lines, boolean synced) {
        public static final Lyrics NONE = new Lyrics(List.of(), false);

        /** Индекс последней строки, время которой &lt;= posMs; -1, если песня ещё не дошла до первой строки. */
        public int indexAt(long posMs) {
            int lo = 0;
            int hi = lines.size() - 1;
            int answer = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (lines.get(mid).timeMs() <= posMs) {
                    answer = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return answer;
        }

        /** Текст строки по индексу; пустая строка, если индекс вне диапазона. */
        public String textAt(int index) {
            return (index < 0 || index >= lines.size()) ? "" : lines.get(index).text();
        }
    }

    /** Вариант поискового запроса. */
    private record Query(String artist, String title, String album, long durationSec) {
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "MusicSubtitles-Lyrics");
        t.setDaemon(true);
        return t;
    });
    // ВАЖНО: собственный executor в HttpClient не передаём — иначе при блокирующих send() в пуле из 2 потоков
    // возможна взаимная блокировка.
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, CompletableFuture<Lyrics>> cache = new ConcurrentHashMap<>();

    /**
     * Запрашивает текст для трека. Возвращает future, который никогда не завершается исключением:
     * при ошибке или отсутствии текста результатом будет {@link Lyrics#NONE}.
     */
    public CompletableFuture<Lyrics> fetch(Track track) {
        if (track.title().isBlank()) {
            return CompletableFuture.completedFuture(Lyrics.NONE);
        }
        if (cache.size() > 200) {
            cache.clear();
        }
        String key = track.lyricsKey() + "|" + (track.durationMs() / 1000);
        return cache.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
            try {
                Lyrics result = loadWithRetry(track);
                LOGGER.info("Текст для '{} - {}': {}", track.artist(), track.title(),
                        result.lines().isEmpty() ? "не найден" : result.lines().size() + " строк");
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cache.remove(k);
                return Lyrics.NONE;
            } catch (Exception e) {
                LOGGER.warn("Ошибка загрузки текста для '{} - {}': {}", track.artist(), track.title(), e.toString());
                cache.remove(k); // ошибки сети не кэшируем — при следующем включении трека попробуем снова
                return Lyrics.NONE;
            }
        }, executor));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------
    // Загрузка
    // ------------------------------------------------------------------------------------------

    private Lyrics loadWithRetry(Track track) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return load(track);
            } catch (IOException e) {
                last = e;
                Thread.sleep(1_500);
            }
        }
        throw last;
    }

    private Lyrics load(Track track) throws IOException, InterruptedException {
        String base = ModConfig.get().lrclibUrl;
        long durMs = track.durationMs();

        for (Query q : buildQueries(track)) {
            Lyrics result = tryGet(base, q);
            if (result != null) {
                return result;
            }
            result = trySearch(base, q, durMs);
            if (result != null) {
                return result;
            }
        }

        // Последняя попытка — свободный поиск по очищенной строке «исполнитель название»
        String free = (firstArtist(track.artist()) + " " + cleanTitle(track.title())).strip();
        Lyrics result = pickBest(httpGet(base + "/api/search?q=" + enc(free)), durMs);
        return result != null ? result : Lyrics.NONE;
    }

    /** Строит список вариантов запроса без дублей (порядок = приоритет). */
    private static List<Query> buildQueries(Track t) {
        Set<Query> set = new LinkedHashSet<>();
        long dur = Math.round(t.durationMs() / 1000.0);
        String artist = t.artist().strip();
        String title = t.title().replace('\u2013', '-').replace('\u2014', '-').strip();
        String album = t.album().strip();

        // 1) как есть
        set.add(new Query(artist, title, album, dur));
        // 2) очищенные название/исполнитель
        set.add(new Query(firstArtist(artist), cleanTitle(title), album, dur));
        // 3) «Исполнитель - Название» в заголовке (SoundCloud, YouTube-подобные источники)
        int sep = title.indexOf(" - ");
        if (sep > 0) {
            String left = title.substring(0, sep).strip();
            String right = title.substring(sep + 3).strip();
            set.add(new Query(firstArtist(left), cleanTitle(right), "", dur));
        }
        return new ArrayList<>(set);
    }

    /** {@code /api/get}: требует исполнителя, название, альбом и длительность. */
    private Lyrics tryGet(String base, Query q) throws IOException, InterruptedException {
        if (q.artist().isBlank() || q.title().isBlank() || q.album().isBlank() || q.durationSec() <= 0) {
            return null;
        }
        String url = base + "/api/get?artist_name=" + enc(q.artist())
                + "&track_name=" + enc(q.title())
                + "&album_name=" + enc(q.album())
                + "&duration=" + q.durationSec();
        String body = httpGet(url);
        if (body == null) {
            return null;
        }
        JsonElement el = JsonParser.parseString(body);
        return el.isJsonObject() ? parseEntry(el.getAsJsonObject()) : null;
    }

    /** {@code /api/search}: возвращает массив кандидатов. */
    private Lyrics trySearch(String base, Query q, long durMs) throws IOException, InterruptedException {
        if (q.title().isBlank()) {
            return null;
        }
        String url = base + "/api/search?track_name=" + enc(q.title())
                + (q.artist().isBlank() ? "" : "&artist_name=" + enc(q.artist()));
        return pickBest(httpGet(url), durMs);
    }

    /** Из массива результатов выбирает запись с синхронизированным текстом и ближайшей длительностью. */
    private static Lyrics pickBest(String body, long durMs) {
        if (body == null) {
            return null;
        }
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            return null;
        }
        JsonArray array = root.getAsJsonArray();
        JsonObject best = null;
        double bestDiff = Double.MAX_VALUE;
        for (JsonElement e : array) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            if (string(o, "syncedLyrics").isBlank()) {
                continue;
            }
            double d = (o.has("duration") && !o.get("duration").isJsonNull()) ? o.get("duration").getAsDouble() : -1;
            double diff = (durMs > 0 && d > 0) ? Math.abs(d - durMs / 1000.0) : 0;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = o;
            }
        }
        if (best == null || (durMs > 0 && bestDiff > 8.0)) {
            return null; // длительность сильно отличается — вероятно, другая версия/другая песня
        }
        return parseEntry(best);
    }

    /** Преобразует JSON-запись LrcLib в {@link Lyrics}. {@code null} — если синхронизированного текста нет. */
    private static Lyrics parseEntry(JsonObject o) {
        if (o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean()) {
            return Lyrics.NONE; // инструментал — текста нет и искать дальше смысла нет
        }
        String synced = string(o, "syncedLyrics");
        if (synced.isBlank()) {
            return null;
        }
        List<Line> lines = parseLrc(synced);
        return lines.isEmpty() ? null : new Lyrics(lines, true);
    }

    // ------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------

    /** GET-запрос. Возвращает тело ответа, {@code null} при 404, бросает IOException при прочих ошибках. */
    private String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = response.statusCode();
        if (code == 404) {
            return null;
        }
        if (code / 100 != 2) {
            throw new IOException("LrcLib вернул HTTP " + code);
        }
        return response.body();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    // ------------------------------------------------------------------------------------------
    // Парсинг LRC
    // ------------------------------------------------------------------------------------------

    /**
     * Разбирает текст в формате LRC. Поддерживает несколько меток времени в одной строке
     * («[00:10.00][01:20.50]Припев»), разные точности долей секунды и расширенный LRC.
     * Метаданные ([ar:...], [ti:...]) игнорируются. Пустые строки сохраняются —
     * они обозначают паузы между фразами (оверлей в это время ничего не показывает).
     */
    static List<Line> parseLrc(String lrc) {
        List<Line> result = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher m = TIMESTAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                times.add(toMillis(m.group(1), m.group(2), m.group(3)));
                end = m.end();
            }
            if (times.isEmpty()) {
                continue; // строка-метаданные или мусор
            }
            String text = WORD_TAG.matcher(raw.substring(end)).replaceAll("").strip();
            for (long t : times) {
                result.add(new Line(t, text));
            }
        }
        result.sort(Comparator.comparingLong(Line::timeMs)); // сортировка устойчивая — порядок равных меток сохраняется
        return result;
    }

    private static long toMillis(String min, String sec, String frac) {
        long ms = Long.parseLong(min) * 60_000L + Long.parseLong(sec) * 1_000L;
        if (frac != null && !frac.isEmpty()) {
            long f = Long.parseLong(frac);
            ms += switch (frac.length()) {
                case 1 -> f * 100;
                case 2 -> f * 10;
                default -> f;
            };
        }
        return ms;
    }

    // ------------------------------------------------------------------------------------------
    // Очистка названий
    // ------------------------------------------------------------------------------------------

    /** Убирает «feat. ...», суффиксы Remastered/Live и текст в скобках. */
    private static String cleanTitle(String title) {
        String s = FEAT.matcher(title).replaceFirst("");
        s = TITLE_SUFFIX.matcher(s).replaceFirst("");
        s = BRACKETS.matcher(s).replaceAll("");
        return s.strip();
    }

    /** Берёт только первого исполнителя из «A, B &amp; C». */
    private static String firstArtist(String artist) {
        String[] parts = ARTIST_SPLIT.split(artist.strip(), 2);
        return parts.length > 0 ? parts[0].strip() : "";
    }
}
