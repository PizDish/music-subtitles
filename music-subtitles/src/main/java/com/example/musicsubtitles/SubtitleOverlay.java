package com.example.musicsubtitles;

import com.example.musicsubtitles.LyricsFetcher.Lyrics;
import com.example.musicsubtitles.MediaSessionTracker.Snapshot;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Random;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Рисует субтитры «в мире» — перед лицом игрока на расстоянии 2–6 блоков.
 *
 * Как это работает: у текста есть собственное направление (yaw/pitch), которое плавно «догоняет» взгляд игрока,
 * а положение = глаза + направление * расстояние. Эта точка проецируется на экран (с учётом FOV), и блок
 * рисуется через {@link DrawContext} с масштабом, зависящим от расстояния. Точка всегда лежит перед камерой,
 * поэтому текст никогда не оказывается сзади; если на пути стоит стена ближе 2 блоков — текст плавно гаснет.
 *
 * Раскладка блока (сверху вниз), центр — текущая строка:
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

    // Состояние «летающего» текста
    private boolean anchorValid;
    private float anchorYaw;
    private float anchorPitch;
    private float anchorDist = 4f;
    /** 1 — текст виден, 0 — загорожен блоками (плавно). */
    private float occlusionFade = 1f;
    /** Смещение «места» текста относительно (отстающего) взгляда, градусы: + вправо / + вниз. */
    private float spotYaw;
    private float spotPitch;
    private float spotYawTarget;
    private float spotPitchTarget;
    private float spotDist = 4f;
    private int lineChanges;
    private boolean relocate = true;
    private float offscreenTime;
    private float retryTimer;
    private int lastSide = 1;
    private final Random random = new Random();

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
            anchorValid = false;
            return;
        }

        // Текущая строка по расчётной позиции воспроизведения
        int index = snap != null ? lyrics.indexAt(snap.estimatePositionMs() + cfg.offsetMs) : lastIndex;
        if (index != lastIndex) {
            lastIndex = index;
            lineChangeNanos = now;
            // Каждые две строки текст «переезжает» на новое место в поле зрения
            if (++lineChanges % 2 == 0) {
                relocate = true;
            }
        }
        float lineFade = clamp01((now - lineChangeNanos) / 1_000_000f / Math.max(1, cfg.fadeInMs));
        lineFade = lineFade * lineFade * (3f - 2f * lineFade); // smoothstep — мягкое начало и конец

        if (mc.world == null || mc.player == null) {
            return;
        }

        TextRenderer tr = mc.textRenderer;
        int maxWidth = cfg.worldWrapWidth;
        int lineHeight = tr.fontHeight;
        float dim = (float) cfg.dimAlpha;

        String curText = lyrics.textAt(index).strip();
        String prevText = cfg.showPrevious ? lyrics.textAt(index - 1).strip() : "";
        String nextText = cfg.showNext ? lyrics.textAt(index + 1).strip() : "";
        List<OrderedText> curLines = curText.isEmpty() ? List.of() : wrap(tr, curText, maxWidth);
        List<OrderedText> prevLines = prevText.isEmpty() ? List.of() : wrap(tr, prevText, maxWidth);
        List<OrderedText> nextLines = nextText.isEmpty() ? List.of() : wrap(tr, nextText, maxWidth);
        List<OrderedText> outLines = List.of();
        if (curText.isEmpty()) {
            String outgoing = lyrics.textAt(index - 1).strip();
            outLines = outgoing.isEmpty() ? List.of() : wrap(tr, outgoing, maxWidth);
        }

        // Размер всего блока в пикселях шрифта — нужен, чтобы подобрать место, где он целиком помещается на экране
        int blockW = 40;
        for (List<OrderedText> l : List.of(curLines, prevLines, nextLines, outLines)) {
            for (OrderedText t : l) {
                blockW = Math.max(blockW, tr.getWidth(t));
            }
        }
        int blockH = (Math.max(1, curLines.size()) + prevLines.size() + nextLines.size()) * lineHeight + 2 * ROW_GAP;

        // ---- Камера и экран ----
        Camera camera = mc.gameRenderer.getCamera();
        Vec3d eye = camera.getPos();
        float camYaw = camera.getYaw();
        float camPitch = camera.getPitch();
        int screenW = context.getScaledWindowWidth();
        int screenH = context.getScaledWindowHeight();
        double tanHalfFov = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
        double aspect = (double) screenW / screenH;

        float desiredDist = (float) MathHelper.clamp(cfg.textDistance, 2.0, 6.0);
        float follow = 1f - (float) Math.exp(-dt * cfg.followSpeed);

        if (!anchorValid) {
            anchorYaw = camYaw;
            anchorPitch = camPitch;
            anchorDist = desiredDist;
            occlusionFade = 0f; // не показываем, пока не найдено свободное место
            relocate = true;
            anchorValid = true;
            spotYaw = 0f;
            spotPitch = 0f;
            spotYawTarget = 0f;
            spotPitchTarget = 0f;
            spotDist = desiredDist;
        } else {
            anchorYaw = lagAngle(anchorYaw, camYaw, follow, 70f);
            anchorPitch = lagAngle(anchorPitch, camPitch, follow, 40f);
        }

        retryTimer -= dt;
        boolean found = true;
        if (relocate && retryTimer <= 0f) {
            found = pickSpot(mc, eye, camYaw, camPitch, tanHalfFov, aspect, screenW, screenH,
                    blockW, blockH, desiredDist, (float) cfg.worldTextScale, cfg);
            relocate = !found;
            retryTimer = found ? 0f : 0.3f; // в тесноте пробуем снова через 0.3 с
        }

        // Место плавно «переезжает» к выбранному
        float move = 1f - (float) Math.exp(-dt * 4.0);
        spotYaw += (spotYawTarget - spotYaw) * move;
        spotPitch += (spotPitchTarget - spotPitch) * move;
        anchorDist += (spotDist - anchorDist) * move;

        float dirYaw = anchorYaw + spotYaw;
        float dirPitch = MathHelper.clamp(anchorPitch + spotPitch, -85f, 85f);
        Vec3d dir = lookVector(dirYaw, dirPitch);

        // Проверка пути до текста: если путь перекрыт ближе 2 блоков — плавно гасим и ищем новое место
        boolean blocked = false;
        float dist = anchorDist;
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, eye.add(dir.multiply(dist + 0.5)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS) {
            double free = hit.getPos().distanceTo(eye) - 0.5;
            if (free < 2.0) {
                blocked = true;
                relocate = true;
            } else {
                dist = (float) Math.min(dist, free);
            }
        }
        if (!found && relocate) {
            blocked = true; // свободного места не нашлось
        }
        occlusionFade = clamp01(occlusionFade + (blocked ? -dt : dt) / 0.25f);

        float eased = visibility * visibility * (3f - 2f * visibility);
        dist = Math.min(6f, dist + (1f - eased) * 0.8f);

        Vec3d d = dir.multiply(dist);
        float[] p = project(d, camYaw, camPitch, tanHalfFov, aspect, screenW, screenH, (float) cfg.worldTextScale);
        if (p == null) {
            relocate = true;
            return; // позади камеры
        }
        float sx = p[0];
        float sy = p[1];
        float scale = p[2] * (0.9f + 0.1f * eased);

        // Если блок надолго вылез за экран (быстрый поворот / смена FOV) — выбираем новое место
        float halfW = blockW * scale / 2f + 4f;
        float halfH = blockH * scale / 2f + 4f;
        boolean off = sx - halfW < 0 || sx + halfW > screenW || sy - halfH < 0 || sy + halfH > screenH;
        offscreenTime = off ? offscreenTime + dt : 0f;
        if (offscreenTime > 0.6f) {
            relocate = true;
            offscreenTime = 0f;
        }

        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(sx, sy);
        matrices.scale(scale, scale);
        try {
            int curHeight;
            int anchorY;

            if (!curLines.isEmpty()) {
                curHeight = curLines.size() * lineHeight;
                anchorY = -curHeight / 2;
                float curAlpha = lerp(cfg.showNext ? dim : 0f, 1f, lineFade);
                drawRow(context, tr, curLines, anchorY, 0, curAlpha, cfg);
                if (!prevLines.isEmpty()) {
                    drawRow(context, tr, prevLines, anchorY - ROW_GAP - prevLines.size() * lineHeight, 0, dim, cfg);
                }
            } else {
                curHeight = lineHeight;
                anchorY = -curHeight / 2;
                if (!outLines.isEmpty()) {
                    drawRow(context, tr, outLines, anchorY, 0, 1f - lineFade, cfg);
                }
            }
            if (!nextLines.isEmpty()) {
                drawRow(context, tr, nextLines, anchorY + curHeight + ROW_GAP, 0, dim, cfg);
            }
        } finally {
            matrices.popMatrix();
        }
    }

    /**
     * Подбирает новое место для текста: перебирает направления сбоку/сверху внутри поля зрения и берёт то,
     * где путь от глаз свободен (не в блоках, пещера достаточно просторна), блок целиком помещается на экране
     * и сторона отличается от прошлой.
     *
     * @return false, если подходящего места нет (например, узкий тоннель) — тогда текст гаснет и поиск повторяется
     */
    private boolean pickSpot(MinecraftClient mc, Vec3d eye, float camYaw, float camPitch, double tanHalfFov,
                             double aspect, int screenW, int screenH, int blockW, int blockH,
                             float desiredDist, float worldScale, ModConfig cfg) {
        double hHalfDeg = Math.toDegrees(Math.atan(tanHalfFov * aspect));
        double vHalfDeg = Math.toDegrees(Math.atan(tanHalfFov));

        float bestScore = -1e9f;
        float bestYaw = 0f;
        float bestPitch = 0f;
        float bestDist = desiredDist;
        boolean any = false;

        for (int i = 0; i < 40; i++) {
            // Сторона и высота внутри поля зрения; от центра отступаем, чтобы текст не закрывал прицел
            int side = (i % 2 == 0) ? lastSide * -1 : lastSide;
            float yawOff = side * (float) (hHalfDeg * (0.28 + 0.42 * random.nextDouble()));
            // В MC отрицательный pitch — вверх: смещаем преимущественно вверх, иногда слегка вниз
            float pitchOff = (float) (vHalfDeg * (-0.60 + 0.70 * random.nextDouble()));

            float yaw = camYaw + yawOff;
            float pitch = MathHelper.clamp(camPitch + pitchOff, -85f, 85f);
            Vec3d dir = lookVector(yaw, pitch);

            // Сколько свободного места вдоль этого направления (узкая пещера — мало)
            Vec3d far = eye.add(dir.multiply(6.5));
            BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, far,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            double free = hit.getType() == HitResult.Type.MISS ? 6.0 : hit.getPos().distanceTo(eye) - 0.5;
            float dist = (float) Math.min(6.0, free);
            if (dist < 2f) {
                continue; // упёрлись в стену — тут текста быть не может
            }
            dist = Math.min(dist, desiredDist);

            // Блок целиком на экране?
            float[] p = project(dir.multiply(dist), camYaw, camPitch, tanHalfFov, aspect, screenW, screenH, worldScale);
            if (p == null) {
                continue;
            }
            float halfW = blockW * p[2] / 2f + 8f;
            float halfH = blockH * p[2] / 2f + 8f;
            if (p[0] - halfW < 0 || p[0] + halfW > screenW || p[1] - halfH < 0 || p[1] + halfH > screenH) {
                continue;
            }

            // Оценка: больше простора и ближе к желаемой дистанции — лучше; немного случайности для разнообразия
            float score = dist + (float) (free >= desiredDist ? 1.0 : 0.0) - Math.abs(dist - desiredDist) * 0.5f
                    + (float) random.nextDouble();
            if (score > bestScore) {
                bestScore = score;
                bestYaw = yawOff;
                bestPitch = pitchOff;
                bestDist = dist;
                any = true;
            }
        }

        if (any) {
            lastSide = bestYaw >= 0 ? 1 : -1;
            // Смещение хранится относительно отстающего взгляда: пересчитываем, чтобы не было скачка
            spotYawTarget = bestYaw + MathHelper.wrapDegrees(camYaw - anchorYaw);
            spotPitchTarget = bestPitch + (camPitch - anchorPitch);
            spotDist = bestDist;
        }
        return any;
    }

    /**
     * Проецирует вектор от глаз до точки на экран.
     *
     * @return {x, y, масштаб текста} в GUI-пикселях или null, если точка за камерой / слишком близко
     */
    private static float[] project(Vec3d d, float camYaw, float camPitch, double tanHalfFov, double aspect,
                                   int screenW, int screenH, float worldScale) {
        Vec3d fwd = lookVector(camYaw, camPitch);
        double yawRad = Math.toRadians(camYaw);
        Vec3d right = new Vec3d(-Math.cos(yawRad), 0, -Math.sin(yawRad));
        Vec3d up = right.crossProduct(fwd);
        double depth = d.dotProduct(fwd);
        if (depth < 0.5) {
            return null;
        }
        double ndcX = d.dotProduct(right) / (depth * tanHalfFov * aspect);
        double ndcY = d.dotProduct(up) / (depth * tanHalfFov);
        float sx = (float) ((ndcX + 1.0) * 0.5 * screenW);
        float sy = (float) ((1.0 - ndcY) * 0.5 * screenH);
        float pxPerBlock = (float) (screenH / (2.0 * depth * tanHalfFov));
        return new float[] {sx, sy, pxPerBlock * worldScale};
    }

    /** Единичный вектор взгляда Minecraft по yaw/pitch в градусах. */
    private static Vec3d lookVector(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch);
        return new Vec3d(-Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }

    /** Плавно подтягивает угол {@code cur} к {@code target} по кратчайшей дуге; отставание не больше {@code maxLag}. */
    private static float lagAngle(float cur, float target, float factor, float maxLag) {
        float diff = MathHelper.wrapDegrees(target - cur);
        diff = MathHelper.clamp(diff * (1f - factor), -maxLag, maxLag); // остаток отставания после шага
        return target - diff;
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
        float a = clamp01(alpha) * visibility * occlusionFade;
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
