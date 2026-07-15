# История изменений

Значимые пользовательские изменения Mission Recorder фиксируются в этом файле. Версии следуют [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.5] - 2026-07-15

### Добавлено

- Контекстный macOS-флоу разрешений для записи экрана и микрофона: тихий preflight для preview, по одному системному запросу после явного действия пользователя, переход в нужную панель System Settings и ручная повторная проверка.
- Фирменная иконка тома в macOS DMG без служебных каталогов, случайно создаваемых Finder.

### Изменено

- Относительные каталоги записи теперь разрешаются от домашнего каталога пользователя, поэтому профиль `recordings` сохраняет файлы в `~/recordings` независимо от рабочего каталога приложения.
- Purpose strings macOS конкретно описывают запись выбранного экрана и голосового комментария.

### Исправлено

- JavaCPP native classifier в Windows capture-модуле теперь учитывает фактические ОС и архитектуру build host, не ломая общие проверки и packaging на macOS и Linux.
- Автоматический preview больше не расходует системный permission prompt, а отклонённый доступ не запускает capture adapters и не приводит к серии запросов разрешений.

## [0.1.4] - 2026-07-15

### Изменено

- Windows MSI/EXE заменены одним пользовательским Inno Setup EXE, который устанавливает приложение в `%LOCALAPPDATA%\Programs\Mission Recorder`.

### Исправлено

- Новый Windows-установщик больше не пытается обновить или удалить старую системную MSI-установку и поэтому не вызывает UAC.

## [0.1.3] - 2026-07-15

### Изменено

- Windows MSI/EXE перенастроены на установку для текущего пользователя; при обновлении системной v0.1.2 удаление прежней установки всё ещё могло вызвать UAC.

### Исправлено

- Windows release-приложение больше не завершается при запуске из-за повреждённых ProGuard-обфускацией JNA-привязок Win32 и WASAPI.

## [0.1.2] - 2026-07-14

### Добавлено

- Опциональный двухсекундный хвост мыши в live preview, обычной записи и replay buffer с затуханием по времени и ограничением видимой длины маршрута.
- Скорости предпросмотра редактора 0,5×, 1×, 1,5× и 2× с синхронным ускорением или замедлением звука и продолжением из текущей позиции.

### Изменено

- Важные кадры раскадровки независимо показывают последние две секунды записанного маршрута мыши; след плавно затухает и больше не сбрасывается соседней важной отметкой.
- Оверлей ввода остаётся видимым во время удержания и кратко после отпускания; Windows также сохраняет короткие клики между соседними видеокадрами.
- Preview редактора переиспользует последовательную сессию декодирования между seek, паузой и продолжением воспроизведения.

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

[0.1.5]: https://github.com/aequicor/mission-recorder/releases/tag/v0.1.5
[0.1.4]: https://github.com/aequicor/mission-recorder/releases/tag/v0.1.4
[0.1.3]: https://github.com/aequicor/mission-recorder/releases/tag/v0.1.3
[0.1.2]: https://github.com/aequicor/mission-recorder/releases/tag/v0.1.2
[0.1.1]: https://github.com/aequicor/mission-recorder/releases/tag/v0.1.1
[0.1.0]: https://github.com/aequicor/mission-recorder/releases/tag/v0.1.0
