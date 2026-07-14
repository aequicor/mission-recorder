# Mission Recorder

![Mission Recorder README header](docs/assets/promo/readme-header.png)

**Mission Recorder** — Kotlin-утилита для захвата экрана, звука и важных моментов на Windows, macOS и Linux (Ubuntu). Проект объединяет CLI для быстрых сценариев и Compose Multiplatform GUI для повседневной работы; Android остаётся запланированной платформой.

Главный фокус: простота, удобство, функциональность, безопасность, открытость и полностью бесплатное использование.

> Текущий релиз: [v0.1.0](https://github.com/aequicor/mission-recorder/releases/tag/v0.1.0) — первый устанавливаемый desktop-релиз. Проект остаётся pre-1.0 и активно развивается.

## Установка

Готовые пакеты публикуются в [GitHub Releases](https://github.com/aequicor/mission-recorder/releases/latest):

| Платформа | Артефакт | Поддержка в v0.1.0 |
| --- | --- | --- |
| Windows x64 | `.msi` или `.exe` | Основная desktop-платформа |
| macOS Intel | `-macos-x64.dmg` | Screen/window capture; system audio пока не поддержан |
| macOS Apple Silicon | `-macos-arm64.dmg` | Screen/window capture; system audio пока не поддержан |
| Ubuntu/Debian x64 | `.deb` | Только X11; Wayland блокируется до появления portal adapter |
| CLI | `mission-recorder-cli-<version>.zip` | Требует установленный JDK 21 |

Desktop-пакеты содержат собственный Java runtime. Пакеты v0.1.0 пока не подписаны и не notarized, поэтому ОС может показать предупреждение издателя. Перед запуском сверяйте файл с `SHA256SUMS.txt` из того же релиза. История изменений находится в [CHANGELOG.md](CHANGELOG.md), а процесс публикации — в [docs/releasing.md](docs/releasing.md).

## Текущий статус реализации

- Сборка стабилизирована под локальный JDK 21.
- `:capture-core` переведен в KMP `commonMain/commonTest` и содержит platform-neutral модели и state machine записи.
- `AudioFrame` поддерживает PCM payload и sample format для текущих и будущих microphone/system audio adapters.
- `:audio-core` переведен в KMP `commonMain/commonTest` и содержит PCM mixer, level metering и runtime mute gate для микрофона/system audio.
- Добавлен `:audio-desktop-javasound` с JVM discovery микрофонов через Java Sound и PCM S16LE capture adapter.
- Добавлен `:audio-windows-wasapi` с discovery активных render endpoints, чтением их Windows friendly names и shared-mode WASAPI loopback capture выбранного устройства на выделенном COM-потоке.
- `:audio-core` нормализует и смешивает microphone + system loopback в один 48 kHz stereo PCM поток для AAC-дорожки.
- Перед mixing каждый физический audio route проходит KMP drift correction: минимальная transport latency оценивается окнами по 5 секунд, отклонения до 10 мс игнорируются, а более крупный clock drift компенсируется не более чем 5 мс PCM за окно с непрерывными timestamps. Счетчики вставленных/удаленных samples доступны через `AudioDriftCorrectionMonitor`.
- Compose GUI показывает throttled peak meters для выбранного микрофона и системного звука, измеряя только уже записываемые PCM frames без отдельного скрытого capture.
- Реализован первый `RecordingController` со `StateFlow<RecordingState>`.
- Добавлены fake video/audio adapters и unit-тесты lifecycle: start, stop, cancel, failure и validation.
- `:capture-platform-api` собирает из KMP `commonMain` контракты discovery источников, audio sources, permissions и routing.
- GUI и CLI выполняют общий permission preflight перед каждой записью/replay: незаявленный статус считается отказом, а capture adapters и output не открываются до успешной проверки. Платформенная семантика описана в [docs/permissions.md](docs/permissions.md).
- Контракт владения GDI, COM/WASAPI, global hotkey и будущими platform resources описан в [docs/native-ownership.md](docs/native-ownership.md); native handles не выходят в common/KMP API.
- Добавлен `:cli` с parser-ом команд, стабильными exit codes, help, `list-sources`, `list-audio`, записью и экспортом раскадровки.
- Добавлен `:media-desktop-ffmpeg`: локальный MP4 с H.264-видео и AAC-аудио, атомарное завершение файла и декодирование раскадровки.
- Desktop encoder и replay передают RGBA кадры в FFmpeg с явным pixel format; regression-тесты проверяют порядок RGB-каналов в MP4 и экспортированных PNG без красного оттенка.
- Desktop GUI использует единый Mission Recorder visual style: фирменный знак экрана с индикатором записи, лапис-лазурный primary, графитовые нейтрали, зелёные успешные состояния и отдельный красный recording accent. Иконки `ICO`, `ICNS` и `PNG` подключены к окнам и нативным пакетам Windows, macOS и Linux.
- `:encoder` сохраняет `.mrec` frame sequence как внутренний совместимый формат для тестов и старых артефактов.
- Добавлен `:export` с экспортом кадров из `.mrec` в PNG/JPEG, поддержкой `--fps`, `--interval` и `--overwrite`.
- `:replay-buffer` собирает platform-neutral lifecycle controller из KMP `commonMain`, а `:media-desktop-ffmpeg` хранит последние `N` минут в ограниченном кольце H.264/AAC сегментов на локальном диске.
- `:settings` разделяет KMP common schema/validation профилей и JVM atomic JSON migration/store.
- Добавлен `:capture-desktop-awt` с JVM desktop discovery и capture adapter для screen/monitor/region; указатель мыши рисуется в видеокадре.
- В Compose GUI область выбирается только явной кнопкой: AWT overlay поддерживает virtual desktop, отрицательные координаты, отмену через Escape/правую кнопку и не запускает запись сам.
- Центральная панель GUI автоматически показывает live preview выбранного screen/monitor/region/window/application после permission preflight; preview сохраняет исходное разрешение при частоте до 5 FPS, не включает audio, не создаёт файл, останавливается перед recording/replay и пока основное окно скрыто или свёрнуто. Кнопка «Сделать скриншот» сохраняет текущий кадр активного preview в PNG рядом с выбранным выходным видео и показывает итоговый путь.
- Добавлен `:capture-windows-jna`: Windows discovery видимых окон/приложений, screen/monitor/region capture через DXGI Desktop Duplication и isolated window capture через `PrintWindow`; при недоступности ускоренного screen capture используется GDI fallback, а нативные handles остаются внутри adapter-а.
- На Windows CLI и GUI направляют screen/monitor/region/window/application в native adapter; курсор при необходимости накладывается поверх полученного BGRA-кадра.
- Добавлен `:capture-linux-x11`: EWMH/Xlib discovery окон и приложений по opaque Window/PID ids, прямой `XGetImage` в RGBA, cursor overlay и отдельный single-thread native dispatcher. Adapter включается только в подтверждённой X11-сессии и не используется как неявный XWayland fallback.
- Добавлен `:capture-macos-coregraphics`: CoreGraphics discovery и window/application capture с рендерингом `CGImage` в контролируемый Retina RGBA context. Native gateway использует `CGPreflightScreenCaptureAccess`/`CGRequestScreenCaptureAccess` и AVFoundation TCC status; app bundle содержит явные Screen Capture и Microphone usage descriptions.
- Курсор с контрастным полупрозрачным ореолом включен по умолчанию, но его можно явно отключить через Video switch в Compose GUI, `--no-cursor` в CLI или `video.captureCursor=false` в локальном profile JSON; то же значение применяется к replay buffer.
- Нативные Windows, macOS и Linux/X11 adapters могут кратко показывать рядом с курсором полный набор новых нажатий клавиатуры и кнопок мыши (`Ctrl + Shift + B`, `ЛКМ`). Второй клик той же кнопкой рядом с первым в течение 500 мс отображается как `(double) ЛКМ`, удержание без перетаскивания от 500 мс — как `(long) ЛКМ`, а перемещение с зажатой кнопкой добавляет `drag` (`ЛКМ + drag`, `Ctrl + ЛКМ + drag`). Функция отключена по умолчанию, поскольку записывает чувствительный ввод; включение доступно отдельным Video switch, CLI-флагом `--show-input` или `video.showInputOverlay=true` в profile JSON и применяется также к replay buffer. Нажатия, распознанные жесты и drag не создают важные кадры автоматически.
- Отдельный Video switch «Записывать путь мыши для раскадровки» сохраняет координаты курсора в sidecar-файле рядом с MP4, независимо от отображения самого курсора в видео. Редактор использует весь накопленный путь, чтобы на каждом важном кадре нарисовать светящийся кометный след от предыдущего важного кадра; после добавления кадра путь автоматически делится на корректные отрезки. Функция выключена по умолчанию из-за чувствительности данных и доступна в profile JSON через `video.recordMouseTrail=true`; для раскадровки sidecar-файл должен оставаться рядом с видео.
- Окна Mission Recorder по умолчанию исключены из записи на Windows. Переключатель «Показывать Mission Recorder в видео» в секции «Видео» сразу включает их в захват и сохраняет выбор между запусками.
- Добавлены `:compose-ui` с общим Compose Multiplatform UI и `:desktop-app` с JVM desktop wiring.
- `:app` больше не зависит от демонстрационного `Printer`.
- `:app` подключен к CLI runner и desktop backend для AWT video capture с опциональным Java Sound microphone capture.
- `list-audio` в desktop app wiring использует Java Sound и показывает доступные микрофонные входы, если они видны JVM.
- `record ... --mic ID` и `--mic default` кодируют выбранный микрофон в AAC-дорожку MP4; выбор устройства происходит явно до старта записи.
- На Windows `--system-audio` и GUI toggle явно включают WASAPI loopback; активный output endpoint выбирается в GUI или через `--system-audio-endpoint ID`, а микрофон и системный звук можно записывать отдельно или вместе.
- На Linux `:audio-linux-pulse` обнаруживает monitor sources через `pactl --format=json` и записывает выбранный источник через cancellable `parec` PCM stream. Adapter включается только при наличии обеих команд и работает с PulseAudio либо PipeWire через `pipewire-pulse` compatibility layer.
- `export-frames` читает MP4 и создает отдельные PNG либо один контактный лист; соседние визуально одинаковые кадры отбрасываются до экспорта, а legacy-экспорт `.mrec` сохранен без изменений.
- GUI всегда записывает MP4. Кнопка «Раскадровка» открывает отдельный экран с непрерывным video preview, транспортом, обзорным таймлайном, undo/redo и автосохранением sidecar-проекта. Постоянная панель раскадровки объединяет автоматически выбранные позиции и важные кадры записи или монтажа: их можно фильтровать, включать по одному либо все сразу, экспортировать отдельными PNG или одним контактным листом и копировать контактный лист в системный буфер обмена. Подробный сценарий и ограничения описаны в [docs/video-editor.md](docs/video-editor.md).
- Windows-захват переиспользует bounded-пул BGRA-буферов и очередь на три кадра: producer сглаживает краткие задержки кодера без неограниченного роста памяти, а отмена гарантированно возвращает недоставленные кадры в пул. Планировщик учитывает время самого захвата, AAC получает укрупнённые пакеты по 1024 samples, а программный H.264 не выбрасывает кадры для удержания битрейта. При записи целого monitor/output через доступный NVIDIA NVENC кадр конвертируется в NV12 на GPU и передаётся из D3D11 в NVENC без промежуточного BGRA-копирования в RAM; курсор и отметки важных кадров сохраняются. Оверлей нажатий, region/window capture и области на нескольких outputs используют CPU fallback, потому что требуют доступа к пикселям.
- При доступном NVIDIA NVENC desktop MP4 использует совместимый с системными Windows-плеерами H.264 High YUV 4:2:0 и quality-target CQ 14; остальные H.264 backend-ы сохраняют тот же широко поддерживаемый chroma format.
- Путь MP4 можно вводить вручную или выбрать явной кнопкой через нативный desktop save dialog; диалог не запускает запись и добавляет `.mp4`, если расширение не указано.
- В Output есть отдельная кнопка открытия каталога текущего `outputPath` в Проводнике/Finder/file manager; отсутствующий локальный каталог создаётся перед открытием.
- GUI явно запускает replay buffer на 1-60 минут, показывает накопленную длительность и сохраняет последние минуты в MP4 без остановки буферизации.
- После фактического старта записи desktop GUI автоматически сворачивает главное окно в компактное always-on-top PiP-окно. Узкая вертикальная PiP-панель показывает состояние, позволяет сделать скриншот экрана, выделить область и сразу сохранить её скриншот, запустить или остановить запись, завершить запись с переходом в монтаж и управлять replay buffer. Подсказка при наведении показывается только у действий с global hotkey и содержит только само сочетание клавиш. Яркая синяя рамка со свечением и усиленными углами показывает и отслеживает записываемую область для screen/monitor/region; на Windows она исключена из захвата и также поддерживает window/application. Toggle «Показывать рамку области записи» сразу скрывает или возвращает индикатор и сохраняется между запусками. Выбранное Windows-окно или главное окно выбранного приложения восстанавливается и выводится вперед. После завершения записи главное окно возвращается автоматически.
- При переключении во внешнее приложение главное окно также сворачивается в always-on-top PiP; открытие собственного диалога Mission Recorder не вызывает сворачивание. PiP не перехватывает фокус и не запрашивает внимание в панели задач. Кнопка разворачивания, значок приложения на панели задач или системный трей восстанавливают главное окно и скрывают PiP.
- Крестик главного окна или PiP скрывает Mission Recorder в системный трей без открытия PiP и не прерывает запись, replay buffer и глобальные hotkeys. Клик по значку или пункт «Открыть Mission Recorder» возвращает главное окно; полное завершение выполняется отдельным пунктом «Выход». Если системный трей недоступен, крестик сворачивает главное окно в панель задач.
- Микрофон и системный звук можно mute/unmute из главного окна и мини-панели во время записи: audio gate передает PCM silence с исходными timestamps, не закрывая устройство и не разрывая AAC timeline.
- Для микрофона и системного звука доступны независимые gain 0-200% в GUI/profile и CLI (`--mic-gain`, `--system-audio-gain`); коэффициенты применяются KMP PCM pipeline одинаково для single-source, mixed recording и replay до кодирования AAC.
- В основном Audio-разделе runtime solo позволяет временно оставить только микрофон или системный звук во время recording/replay; solo не изменяет сохранённые mute-флаги и очищается при смене источника.
- Позиция мини-панели сохраняется в локальном settings JSON при закрытии и при следующем явном открытии зажимается внутри ближайшего доступного монитора, включая конфигурации с отрицательными координатами.
- Запись можно приостановить и продолжить из главного окна или мини-панели: capture devices остаются открытыми, кадры во время паузы не кодируются, а общая pause duration вычитается из video/audio timestamps и длительности MP4. При наведении кнопки записи/остановки и паузы/продолжения показывают соответствующие global hotkeys.
- На Windows, macOS и Linux/X11 глобальные hotkeys включены по умолчанию. Кнопка с клавиатурой в header открывает их настройку, где регистрацию можно явно выключить. Прокручиваемый список показывает текущее сочетание для каждого действия; после нажатия «Изменить» следующее введённое сочетание заменяет его. `Ctrl+Shift+F7` по умолчанию открывает выбор области и запускает запись сразу после подтверждения; отдельное сочетание `Ctrl+Shift+F8` только выбирает область. Можно использовать сочетания с буквами, цифрами, F1–F12, навигационными, знаковыми и цифровыми клавишами, с любым набором `Ctrl`/`Alt`/`Shift`/`Meta` или без модификаторов. Сочетания должны быть уникальными; «Сбросить» в строке возвращает значение только этого действия, а общий сброс — все значения по умолчанию. Успешная отметка важного кадра во время записи кратко подтверждается в мини-панели и preview, сохраняется в MP4 и автоматически появляется в видеоредакторе; поверх такого кадра рисуется путь курсора с предыдущей важной отметки. Она работает независимо от показа нажатий в видео. Регистрация сама по себе не запускает capture и полностью снимается при выключении или закрытии приложения; Wayland не подменяется XWayland-регистрацией.
- CLI поддерживает foreground replay: `replay run ... --buffer N --output replay.mp4` работает до `Ctrl+C` и сохраняет накопленный snapshot; опциональный `--run-for N` задает дедлайн, а `--control-endpoint PATH` включает локальные `status`, отдельный `save` без остановки и финальный `stop` для automation.
- `replay start` запускает тот же pipeline отдельным локальным процессом, требует явный control endpoint, проверяет состояние `replay-buffering` и возвращает PID; `replay save --endpoint ...` сохраняет snapshot без остановки daemon-а.
- CLI-запись может работать без `--duration` до `Ctrl+C`; JVM shutdown hook запрашивает stop и ждёт финализации локального MP4.
- CLI-запись поддерживает явный локальный `--control-endpoint`: отдельные команды `control status|pause|resume|stop` управляют той же сессией через loopback и одноразовый токен. Сценарий описан в [docs/cli-control.md](docs/cli-control.md).
- `settings init/validate/show` подключены к CLI и работают с локальным JSON-файлом настроек.
- `record profile --settings ...` использует локальный профиль настроек для source/video/audio/encoder/replay defaults.
- Desktop GUI атомарно сохраняет source, audio, output, FPS, cursor visibility, H.264 video bitrate, replay duration, storyboard layout и полный encoder snapshot в локальных настройках, а также восстанавливает состояние и выбранные сочетания global hotkeys; mini-window position хранится в том же локальном JSON. Выбор, создание и удаление именованных профилей в GUI не показываются.
- После завершения MP4 GUI сохраняет фактический файл в `lastOutputPath`, автоматически открывает его из экрана «Монтаж» и сразу вычисляет следующий output из `directory/fileNamePattern` локальных настроек, поэтому последовательные записи не пытаются перезаписать предыдущую.
- В Output доступен отдельный редактор каталога и `.mp4`-шаблона имени с подстановкой `{timestamp}`; Apply обновляет предпросмотр следующего пути и атомарно сохраняет policy в локальных настройках.
- Явный Output toggle управляет `output.overwrite` в локальных настройках и общими `RecordingSettings`: без opt-in существующий target отклоняется, а при включении старый MP4 заменяется только после успешного завершения нового временного файла; cancel/failure сохраняет прежний файл.
- Прямой CLI record имеет тот же явный opt-in через `--overwrite`; отсутствие флага сохраняет profile policy и никогда не включает замену неявно.

Текущие ограничения backend-а:

- system audio реализован для активных Windows output devices через WASAPI и Linux PulseAudio/PipeWire monitor sources через `pactl`/`parec`; app/PID filtering пока не подключен;
- protected/DRM audio не обходится и может отсутствовать в loopback потоке;
- window/application capture реализован на Windows и Linux/X11; на X11 obscured regions без backing store могут быть неопределёнными, а protected/elevated/hardware surfaces на Windows могут возвращать пустой кадр;
- DXGI Desktop Duplication ускоряет Windows screen/monitor/region capture только в пределах одного output; область, пересекающая несколько мониторов, использует GDI fallback;
- Windows Graphics Capture backend, Wayland portal capture, ScreenCaptureKit replacement для deprecated CoreGraphics image API и Android adapters еще не подключены;
- GUI region overlay покрыт автоматическими тестами геометрии, но live-сценарии с несколькими мониторами и разным DPI еще требуют проверки на реальном оборудовании;
- текущий production output desktop backend-а - MP4; WebM пока не подключен;
- GUI позволяет выбирать video bitrate 2-30 Mbps, но codec/container selector не показывается: production backend пока честно поддерживает только MP4/H.264 и AAC.
- bounded A/V drift correction покрыта детерминированными slow/fast-clock тестами, но многочасовая проверка на реальных Java Sound/WASAPI устройствах еще не выполнена;
- CLI replay daemon запускается явной командой `replay start ... --control-endpoint ...`, подтверждает готовность через локальный tokenized protocol и затем управляется командами `control status`, `replay save`/`control save` и `control stop`; скрытый автозапуск отсутствует.
- Pause/resume доступны в Compose GUI, доменном controller и opt-in локальном CLI control channel. Тот же versioned loopback protocol управляет foreground replay через `status`, `control save --output ...` и `stop`; replay pause/resume явно отклоняются.
- Compose transport bar и `control status --json` показывают средний фактический FPS и оценку пропущенных видеокадров; паузы исключаются из media timeline и drop-расчета, а replay-метрики относятся к текущему окну последних `N` минут.
- профили настроек можно создавать, проверять и использовать из CLI; Compose GUI работает с текущими локальными настройками без выбора именованных профилей, а скрытые codec/container поля пока остаются доступны только через локальный JSON.
- Always-on-top, восстановление окна после live-смены monitor layout/DPI и compact layout требуют ручного visual smoke на целевых desktop-платформах; автоматические geometry-тесты покрывают reopen после отключения монитора, отрицательные координаты и промежутки между экранами.
- macOS Carbon hotkeys покрыты fake-native lifecycle тестами, но фактическую доставку событий через Carbon Event Dispatcher еще нужно проверить на реальном Mac вместе с DMG.
- macOS production wiring использует native screen preflight/request и microphone TCC status; system audio пока unsupported. Linux portal gateway еще не реализован: JVM gateway работает fail-closed на Wayland и неизвестной session, разрешая AWT/Xlib capture только для подтверждённого X11.

## Возможности

- Запись всего экрана или выбранного монитора.
- Запись выделенной области экрана.
- Захват конкретного приложения без лишнего содержимого вокруг.
- Запись микрофона и системного звука.
- Фильтрация системного звука по приложениям.
- Фоновая запись с сохранением последних `N` минут по кнопке или горячей клавише.
- Экран монтажа с навигацией по видеоряду и управляемым экспортом раскадровки.
- CLI-режим для автоматизации и GUI-режим для удобного управления.

## Почему Mission Recorder

Mission Recorder создается для ситуаций, где запись должна быть быстрой, понятной и подконтрольной пользователю. Инструмент не требует сложной настройки, не прячет базовые возможности за платными уровнями и не делает облако обязательной частью рабочего процесса.

Проект ориентирован на локальную работу: пользователь контролирует, что записывается, где хранится и как экспортируется. Открытая Kotlin-архитектура упрощает аудит, расширение и адаптацию под разные платформы.

## Для кого

- Разработчики, которым нужно быстро записывать баги, демо и воспроизведения проблем.
- Пользователи, которым нужен простой экранный рекордер без подписок.
- Команды, которым важны прозрачность, локальное хранение и кроссплатформенность.
- Создатели гайдов, видеоинструкций и технических материалов.

## Технологии

- Kotlin
- Gradle
- CLI
- Compose Multiplatform
- Kotlin Coroutines и Flow
- JNI и Kotlin/Native interop для платформенного захвата

## Разработка

Используйте Gradle Wrapper из корня репозитория:

```bash
./gradlew run --args="help"
./gradlew runGui
./gradlew build
./gradlew check
```

На Windows:

```powershell
.\gradlew.bat run --args="help"
.\gradlew.bat runGui
.\gradlew.bat check
```

Корневой `run` запускает CLI; Compose Desktop GUI запускается только явной задачей `runGui` или `:desktop-app:run`.

Для системного звука на Linux нужны `pactl` с JSON output (PulseAudio 16+ либо совместимый `pipewire-pulse`) и `parec` из пакета PulseAudio utilities. При отсутствии команд или доступного audio server adapter не включается и Mission Recorder продолжает работать с Java Sound микрофонами.

Проверка CLI через Gradle:

```powershell
.\gradlew.bat :app:run --args="list-sources --json"
.\gradlew.bat :app:run --args="list-audio --json"
.\gradlew.bat :app:run --args="settings init --path mission-recorder.settings.json --json"
.\gradlew.bat :app:run --args="settings validate --path mission-recorder.settings.json --json"
.\gradlew.bat :app:run --args="record profile --settings mission-recorder.settings.json --duration 3s --json"
.\gradlew.bat :app:run --args="record screen --output build\record-until-ctrl-c.mp4"
.\gradlew.bat :app:run --args="record screen --output build\controlled.mp4 --control-endpoint build\controlled.control.json"
.\gradlew.bat :app:run --args="control status --endpoint build\controlled.control.json --json"
.\gradlew.bat :app:run --args="control pause --endpoint build\controlled.control.json --json"
.\gradlew.bat :app:run --args="control resume --endpoint build\controlled.control.json --json"
.\gradlew.bat :app:run --args="control stop --endpoint build\controlled.control.json --json"
.\gradlew.bat :app:run --args="record screen --output build\sample.mp4 --duration 3s --fps 30"
.\gradlew.bat :app:run --args="record screen --output build\replace-me.mp4 --duration 3s --overwrite"
.\gradlew.bat :app:run --args="record screen --output build\sample-with-mic.mp4 --duration 3s --fps 30 --mic default --json"
.\gradlew.bat :app:run --args="record screen --output build\sample-with-system-audio.mp4 --duration 3s --system-audio --json"
.\gradlew.bat :app:run --args="record screen --output build\sample-with-headset-audio.mp4 --duration 3s --system-audio-endpoint wasapi:loopback:endpoint:DEVICE_ID --json"
.\gradlew.bat :app:run --args="record screen --output build\sample-mixed.mp4 --duration 3s --mic default --system-audio --json"
.\gradlew.bat :app:run --args="record screen --output build\sample-balanced.mp4 --duration 3s --mic default --mic-gain 75 --system-audio-gain 40 --json"
.\gradlew.bat :app:run --args="record window --id window:win32:HANDLE --output build\window.mp4 --duration 3s"
.\gradlew.bat :app:run --args="record app --id application:win32:PID --output build\application.mp4 --duration 3s"
.\gradlew.bat :app:run --args="replay run screen --buffer 5m --output build\replay.mp4 --json"
.\gradlew.bat :app:run --args="replay run screen --buffer 5m --output build\replay-final.mp4 --control-endpoint build\replay.control.json"
.\gradlew.bat :app:run --args="control save --endpoint build\replay.control.json --output build\replay-snapshot.mp4 --json"
.\gradlew.bat :app:run --args="control stop --endpoint build\replay.control.json --json"

# Опциональный автоматический дедлайн:
.\gradlew.bat :app:run --args="replay run screen --buffer 5m --run-for 30m --output build\replay.mp4 --json"
.\gradlew.bat :app:run --args="export-frames --input build\sample.mp4 --output build\sample-frames --fps 1 --layout separate --json"
.\gradlew.bat :app:run --args="export-frames --input build\sample.mp4 --output build\sample-storyboard.png --interval 2s --layout sheet --json"
.\gradlew.bat :desktop-app:run
```

## Принципы продукта

- **Просто**: быстрый старт без перегруженного интерфейса.
- **Безопасно**: локальная обработка и явное управление разрешениями.
- **Открыто**: код и архитектура доступны для проверки.
- **Функционально**: запись экрана, приложений, областей, микрофона и системного звука.
- **Бесплатно**: базовая ценность продукта не должна зависеть от подписки.
