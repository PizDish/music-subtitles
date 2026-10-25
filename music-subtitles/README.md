# Music Subtitles (Lyrics HUD) — Fabric 1.21.7, client-only

Показывает синхронизированный текст текущей песни (LrcLib) над хотбаром.

## Инструкция по сборке

1. Установите **JDK 21** (Temurin / Microsoft / Oracle) и убедитесь, что `java -version` показывает 21.
2. Нужен Gradle Wrapper. Два способа:
   - скопируйте папку `gradle/` и файлы `gradlew`, `gradlew.bat` из
     [fabric-example-mod](https://github.com/FabricMC/fabric-example-mod) (ветка 1.21.x) в корень проекта; **или**
   - если Gradle установлен глобально: `gradle wrapper --gradle-version 8.14`.
3. В корне проекта:
   ```
   ./gradlew build        # Windows: gradlew.bat build
   ```
   Готовый мод: `build/libs/music-subtitles-1.0.0.jar` (файл `-sources.jar` не нужен).
4. Отладочный запуск клиента: `./gradlew runClient`.
5. Для игры нужны **Fabric Loader ≥ 0.16.14** и **Fabric API 0.128.2+1.21.7** (или новее для 1.21.7).
   Положите оба jar в `.minecraft/mods`.

## Использование

- Запустите плеер (Spotify / Spicetify / Яндекс Музыка / SoundCloud в браузере) и включите трек.
- Клавиша **F7** — включить/выключить (меняется в настройках управления). **F1** скрывает субтитры вместе с HUD.
- Конфиг: `.minecraft/config/musicsubtitles.json` (положение, цвета, сдвиг синхронизации `offsetMs`,
  фильтр плееров `sourceFilter`, адрес LrcLib). Изменения применяются после перезапуска игры.

## Как определяется трек

| ОС | Способ |
|----|--------|
| Windows 10 1809+ / 11 | Системный Media Session (SMTC) через один скрытый процесс `powershell.exe` (Windows PowerShell 5.1) |
| Linux | MPRIS через `playerctl` (`sudo apt install playerctl`) |
| macOS | не поддерживается |

SoundCloud и веб-версия Яндекс Музыки определяются через браузер (Chrome, Edge, Firefox и др. публикуют
Media Session в ОС). Если в браузере одновременно играет несколько вкладок/плееров, используйте
`sourceFilter`, например `["spotify", "yandex"]`.

## Ограничения

- Используются только тексты **с синхронизацией** (LRC). Если у LrcLib есть лишь обычный текст, строки не показываются.
- Если плеер не сообщает позицию (редко, некоторые вкладки браузера), отсчёт ведётся от момента обнаружения
  трека — синхронизация приблизительна; подстройте `offsetMs`.
- Названия в браузерах бывают «грязными»; мод пробует несколько вариантов запроса, но 100% совпадения не гарантирует.
