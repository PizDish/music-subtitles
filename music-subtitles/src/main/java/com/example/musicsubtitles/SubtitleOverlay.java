package com.example.musicsubtitles;

import com.example.musicsubtitles.LyricsFetcher.Lyrics;
import com.example.musicsubtitles.MediaSessionTracker.Snapshot;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Рисует субтитры поверх HUD через {@link DrawContext}.
 *
 * Раскладка (сверху вниз), якорь — строка на высоте {@code screenHeight - bottomOffset}:
 * <pre>
 *   предыдущая строка  (приглушена)
 *   ТЕКУЩАЯ СТРОКА     (яркая, плавно проявляется при смене)
 *   следующая строка   (приглушена)
 * </pre>
 * Плавность: следующая строка уже видна приглушённой, а когда наступает её время, её прозрачность
 * плавно растёт до 100% — получается непрерывное «проявление» без резких миганий.
 * В паузах между фразами (пустые строки LRC) предыдущая строка плавно гаснет.
 */
@Environment(EnvType.CLIENT)
public final class SubtitleOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("MusicSubtitles/Overlay");
    /** Вертикальный зазор между строками, px. */
    private static final int ROW_GAP = 3;
    /** Время появления/исчезновения всего блока субтитров, секунд. */
    private static final float GLOBAL_FADE_SECONDS = 0.35f;

    private final MediaSessionTracker tracker;
    private final LyricsFetcher fetcher;

    private String currentKey = "";
    private CompletableFuture<Lyrics> pending;
    private Lyrics lyrics = Lyrics.NONE;

    private int lastIndex = -2;
    private long lineChangeNanos;
    private float visibility;
    private long lastFrameNanos;
    private boolean errorLogged;

    /** Кэш переноса строк по ширине (перенос — дорогая операция, а текст меняется редко). */
    private final Map<String, List<OrderedText>> wrapCache = new HashMap<>();
    private int wrapCacheWidth = -1;

    public SubtitleOverlay(MediaSessionTracker tracker, LyricsFetcher fetcher) {
        this.tracker = tracker;
        this.fetcher = fetcher;
    }

    /** Точка входа для {@code HudRenderCallback}. Любая ошибка рендера перехватывается — мод не должен ронять клиент. */
    public void render(DrawContext context, RenderTickCounter tickCounter) {
        try {
            renderInternal(context);
        } catch (RuntimeException e) {
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.error("Ошибка отрисовки субтитров (повторные ошибки не логируются)", e);
            }
        }
    }

    private void renderInternal(DrawContext context) {
        ModConfig cfg = ModConfig.get();
        MinecraftClient mc = MinecraftClient.getInstance();

        // Если мод выключен и всё уже погасло — ничего не делаем (и не ходим в сеть)
        if (!cfg.enabled && visibility <= 0f) {
            lastFrameNanos = 0;
            return;
        }

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : Math.min((now - lastFrameNanos) / 1_000_000_000f, 0.1f);
        lastFrameNanos = now;

        Snapshot snap = tracker.getSnapshot();
        syncLyrics(snap, cfg);

        boolean hasLyrics = lyrics.synced() && !lyrics.lines().isEmpty();
        boolean wanted = cfg.enabled && hasLyrics && snap != null
                && (snap.playing() || !cfg.hideWhenPaused)
                && !mc.options.hudHidden; // F1 скрывает и субтитры

        float step = dt / GLOBAL_FADE_SECONDS;
        visibility = clamp01(visibility + (wanted ? step : -step));
        if (visibility <= 0f || !hasLyrics) {
            return;
        }

        // Текущая строка по расчётной позиции воспроизведения
        int index = snap != null ? lyrics.indexAt(snap.estimatePositionMs() + cfg.offsetMs) : lastIndex;
        if (index != lastIndex) {
            lastIndex = index;
            lineChangeNanos = now;
        }
        float lineFade = clamp01((now - lineChangeNanos) / 1_000_000f / Math.max(1, cfg.fadeInMs));
        lineFade = lineFade * lineFade * (3f - 2f * lineFade); // smoothstep — мягкое начало и конец

        TextRenderer tr = mc.textRenderer;
        int screenW = context.getScaledWindowWidth();
        int screenH = context.getScaledWindowHeight();
        int centerX = screenW / 2;
        int maxWidth = Math.max(80, screenW * cfg.maxWidthPercent / 100);
        int lineHeight = tr.fontHeight;
        float dim = (float) cfg.dimAlpha;

        String curText = lyrics.textAt(index).strip();
        String prevText = cfg.showPrevious ? lyrics.textAt(index - 1).strip() : "";
        String nextText = cfg.showNext ? lyrics.textAt(index + 1).strip() : "";

        int anchorY = screenH - cfg.bottomOffset;
        int curHeight;

        if (!curText.isEmpty()) {
            // Текущая строка: из приглушённой (если «следующая» была видна) плавно в 100%
            List<OrderedText> cur = wrap(tr, curText, maxWidth);
            curHeight = cur.size() * lineHeight;
            float curAlpha = lerp(cfg.showNext ? dim : 0f, 1f, lineFade);
            drawRow(context, tr, cur, anchorY, centerX, curAlpha, cfg);

            // Предыдущая строка — над текущей
            if (!prevText.isEmpty()) {
                List<OrderedText> prev = wrap(tr, prevText, maxWidth);
                drawRow(context, tr, prev, anchorY - ROW_GAP - prev.size() * lineHeight, centerX, dim, cfg);
            }
        } else {
            // Пауза между фразами: предыдущая строка плавно гаснет на месте текущей
            curHeight = lineHeight;
            String outgoing = lyrics.textAt(index - 1).strip();
            if (!outgoing.isEmpty()) {
                drawRow(context, tr, wrap(tr, outgoing, maxWidth), anchorY, centerX, 1f - lineFade, cfg);
            }
        }

        // Следующая строка — под текущей
        if (!nextText.isEmpty()) {
            List<OrderedText> next = wrap(tr, nextText, maxWidth);
            drawRow(context, tr, next, anchorY + curHeight + ROW_GAP, centerX, dim, cfg);
        }
    }

    /** Следит за сменой трека, запускает загрузку текста и забирает готовый результат. */
    private void syncLyrics(Snapshot snap, ModConfig cfg) {
        if (snap != null) {
            String key = snap.track().lyricsKey();
            if (!key.equals(currentKey)) {
                currentKey = key;
                lyrics = Lyrics.NONE;
                lastIndex = -2;
                visibility = 0f;
                wrapCache.clear();
                pending = fetcher.fetch(snap.track());
            }
        }
        if (pending != null && pending.isDone()) {
            try {
                lyrics = pending.getNow(Lyrics.NONE);
            } catch (RuntimeException e) {
                lyrics = Lyrics.NONE;
            }
            pending = null;
            lastIndex = -2;
        }
    }

    /**
     * Рисует блок строк (после переноса) с подложкой.
     *
     * @param topY  верхняя координата блока
     * @param alpha прозрачность строки 0..1 (дополнительно умножается на общую видимость)
     */
    private void drawRow(DrawContext context, TextRenderer tr, List<OrderedText> lines,
                         int topY, int centerX, float alpha, ModConfig cfg) {
        if (lines.isEmpty()) {
            return;
        }
        float a = clamp01(alpha) * visibility;
        int textAlpha = (int) (a * 255f);
        // Ванильный TextRenderer трактует альфу < 4 как «непрозрачно», поэтому такие кадры пропускаем
        if (textAlpha < 5) {
            return;
        }

        int width = 0;
        for (OrderedText line : lines) {
            width = Math.max(width, tr.getWidth(line));
        }
        int height = lines.size() * tr.fontHeight;

        int bgAlpha = (int) (cfg.backgroundOpacity * a);
        if (bgAlpha > 0) {
            context.fill(centerX - width / 2 - 3, topY - 2, centerX + (width + 1) / 2 + 3, topY + height + 1,
                    bgAlpha << 24);
        }

        int color = (textAlpha << 24) | cfg.colorRgb();
        int y = topY;
        for (OrderedText line : lines) {
            context.drawCenteredTextWithShadow(tr, line, centerX, y, color);
            y += tr.fontHeight;
        }
    }

    private List<OrderedText> wrap(TextRenderer tr, String text, int maxWidth) {
        if (maxWidth != wrapCacheWidth || wrapCache.size() > 40) {
            wrapCache.clear();
            wrapCacheWidth = maxWidth;
        }
        return wrapCache.computeIfAbsent(text, t -> tr.wrapLines(Text.literal(t), maxWidth));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
