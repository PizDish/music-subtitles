package com.example.musicsubtitles;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Простая JSON-конфигурация мода.
 * Файл: {@code .minecraft/config/musicsubtitles.json}. Создаётся автоматически при первом запуске.
 * Поля публичные, чтобы Gson мог их (де)сериализовать без лишних адаптеров.
 */
@Environment(EnvType.CLIENT)
public final class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("MusicSubtitles/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile ModConfig instance = new ModConfig();

    /** Глобальный выключатель (переключается клавишей, по умолчанию F7). */
    public boolean enabled = true;

    /** Отступ строки-якоря от нижнего края экрана в GUI-пикселях (хотбар ~22, полоски здоровья ~60). */
    public int bottomOffset = 90;

    /** Сдвиг синхронизации в мс. Положительное значение — строки появляются РАНЬШЕ. */
    public int offsetMs = 0;

    /** Показывать предыдущую строку (приглушённо) над текущей. */
    public boolean showPrevious = true;

    /** Показывать следующую строку (приглушённо) под текущей. */
    public boolean showNext = true;

    /** Скрывать субтитры, когда музыка на паузе. */
    public boolean hideWhenPaused = true;

    /** Длительность плавного проявления новой строки, мс. */
    public int fadeInMs = 300;

    /** Непрозрачность тёмной подложки за текстом (0 — без подложки, 255 — чёрная). */
    public int backgroundOpacity = 110;

    /** Максимальная ширина блока субтитров в процентах от ширины экрана. */
    public int maxWidthPercent = 60;

    /** Цвет текущей строки в формате #RRGGBB. */
    public String currentColor = "#FFFFFF";

    /** Прозрачность приглушённых строк (0.0 – 1.0). */
    public double dimAlpha = 0.45;

    /** Желаемое расстояние от глаз игрока до текста в блоках (2–6); в тесных местах текст ближе, но не меньше 2. */
    public double textDistance = 4.0;

    /** Не используется (место текста подбирается автоматически); оставлено для совместимости со старыми конфигами. */
    public double heightOffset = -0.6;

    /** Размер текста в мире: блоков на один пиксель шрифта (у ников над головой — 0.025). */
    public double worldTextScale = 0.02;

    /** Ширина переноса строк в пикселях шрифта (до масштабирования). */
    public int worldWrapWidth = 220;

    /** Скорость «догоняния» текстом взгляда игрока (1/сек). Меньше — плавнее и ленивее, больше — жёстче. */
    public double followSpeed = 5.0;

    /** Базовый адрес LrcLib (можно указать свой self-hosted инстанс). */
    public String lrclibUrl = "https://lrclib.net";

    /**
     * Фильтр источников: список подстрок, которые должны содержаться в идентификаторе приложения
     * (AppUserModelId в Windows / имя плеера в MPRIS), например ["spotify", "yandex", "chrome"].
     * Пустой список — принимать любой плеер.
     */
    public List<String> sourceFilter = new ArrayList<>();

    public static ModConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("musicsubtitles.json");
    }

    /** Загружает конфиг с диска; если файла нет — создаёт его со значениями по умолчанию. */
    public static void load() {
        Path file = path();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Не удалось прочитать конфиг, используются значения по умолчанию", e);
            }
        }
        instance.sanitize();
        save(); // перезаписываем, чтобы в файле появились новые поля после обновлений
    }

    public static void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Не удалось сохранить конфиг", e);
        }
    }

    /** Приводит значения к допустимым диапазонам (на случай ручной правки файла). */
    private void sanitize() {
        bottomOffset = Math.max(0, Math.min(bottomOffset, 2000));
        offsetMs = Math.max(-30_000, Math.min(offsetMs, 30_000));
        fadeInMs = Math.max(0, Math.min(fadeInMs, 5_000));
        backgroundOpacity = Math.max(0, Math.min(backgroundOpacity, 255));
        maxWidthPercent = Math.max(20, Math.min(maxWidthPercent, 100));
        dimAlpha = Math.max(0.0, Math.min(dimAlpha, 1.0));
        textDistance = Math.max(2.0, Math.min(textDistance, 6.0));
        heightOffset = Math.max(-3.0, Math.min(heightOffset, 3.0));
        worldTextScale = Math.max(0.005, Math.min(worldTextScale, 0.1));
        worldWrapWidth = Math.max(80, Math.min(worldWrapWidth, 600));
        followSpeed = Math.max(0.5, Math.min(followSpeed, 30.0));
        if (sourceFilter == null) {
            sourceFilter = new ArrayList<>();
        }
        if (lrclibUrl == null || lrclibUrl.isBlank()) {
            lrclibUrl = "https://lrclib.net";
        }
        while (lrclibUrl.endsWith("/")) {
            lrclibUrl = lrclibUrl.substring(0, lrclibUrl.length() - 1);
        }
        if (currentColor == null) {
            currentColor = "#FFFFFF";
        }
    }

    /** RGB-значение цвета текущей строки (без альфа-канала). */
    public int colorRgb() {
        try {
            return Integer.parseInt(currentColor.replace("#", "").strip(), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }
}
