# История изменений

Значимые пользовательские изменения Mission Recorder фиксируются в этом файле. Версии следуют [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Удалено

- Консольный интерфейс, JVM CLI composition root и переносимый CLI ZIP; Mission Recorder распространяется только как desktop-приложение.

## [0.1.1] - 2026-07-14

### Исправлено

- Release workflow явно выбирает нативные JavaCV/FFmpeg-библиотеки целевой платформы, исключая несуществующий Windows ARM classifier на macOS Apple Silicon.
- macOS application bundle получает стабильный идентификатор `io.aequicor.missionrecorder` и допустимую для jpackage внутреннюю версию с ненулевым major.
- Release workflow можно запустить вручную как dry run без публикации; при сбое macOS packaging он выводит диагностические логи jpackage.

## [0.1.0] - 2026-07-14

### Добавлено

- Устанавливаемое Compose Desktop приложение для Windows, macOS и Linux с локальной записью MP4.
- Захват screen, monitor, region, window и application через платформенные adapters; Windows использует DXGI/GDI, macOS — CoreGraphics, Linux — X11.
- Запись микрофона и системного звука, runtime mute/solo/gain, синхронизация PCM и AAC-кодирование.
- Replay buffer последних минут, global hotkeys, tray/PiP управление, recording border и screenshots.
- Редактор раскадровки с preview, важными кадрами, undo/redo, экспортом PNG/contact sheet и копированием в clipboard.
- Опциональные input overlays с double/long/drag жестами и приватный по умолчанию mouse-trail sidecar для раскадровки.
- Автоматическая сборка Windows MSI/EXE, macOS DMG для Intel и Apple Silicon и Linux DEB с SHA-256 checksums.

### Ограничения

- Wayland capture не включается без portal adapter; Linux desktop package рассчитан на X11.
- System audio на macOS пока не поддержан.
- Android application packaging ещё не реализован.
- Desktop-пакеты пока не подписаны и не notarized.

[0.1.1]: https://github.com/aequicor/mission-recorder/releases/tag/v0.1.1
[0.1.0]: https://github.com/aequicor/mission-recorder/releases/tag/v0.1.0
