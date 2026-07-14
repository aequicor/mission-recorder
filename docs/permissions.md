# Разрешения Mission Recorder

Mission Recorder проверяет разрешения перед каждым запуском обычной записи и replay buffer. Проверка выполняется до открытия video/audio capture adapter и до создания выходного файла. Набор разрешений формируется из фактически выбранных источников:

- `ScreenRecording` нужен для любого screen/monitor/region/window/application capture;
- `Microphone` добавляется только при выбранном микрофоне;
- `SystemAudio` добавляется только при включенном системном звуке.

GUI выполняет preflight после явного нажатия кнопки записи или запуска replay buffer, а также перед автоматическим preview выбранного источника. Preview захватывает выбранный video source в исходном разрешении с частотой до 5 FPS, не включает audio и не создаёт output; скрытие или сворачивание основного окна отменяет capture flow до возвращения окна. Кнопка «Сделать скриншот» доступна только для активного preview, сохраняет уже полученный кадр и не открывает отдельный capture flow. Регистрация глобальных hotkeys, открытие мини-панели или каталога записей, discovery источников, экспорт кадров и просмотр настроек сами по себе capture не запускают и разрешения не запрашивают.

`PermissionGateway` обязан вернуть статус для каждого запрошенного разрешения. Неполный отчет считается отказом; recording/replay engine не запускается. Причина выводится пользователю, а повторный запуск снова выполняет проверку.

## Windows

Текущие AWT, User32/GDI, Java Sound и WASAPI adapters не имеют отдельного app-level runtime prompt. Preflight разрешает переход к adapter-у, а фактический запрет Windows Privacy Settings или недоступность устройства возвращается как ошибка открытия capture API. Mission Recorder не пытается обходить системную политику, elevation или protected content.

Для микрофона доступ должен быть включен в `Settings > Privacy & security > Microphone`. System audio использует явно выбранный active render endpoint через WASAPI loopback.

## Linux

Текущие JVM/AWT и JNA/Xlib backends поддерживают screen/monitor/region и window/application capture только в подтверждённой X11-сессии и не имеют отдельного permission prompt. `JvmDesktopPermissionGateway` проверяет `XDG_SESSION_TYPE`, `WAYLAND_DISPLAY` и `DISPLAY`: Wayland или неизвестная сессия получают `Unsupported` до открытия capture adapter и создания output. Наличие XWayland `DISPLAY` не считается согласием и не заменяет portal consent фиктивным разрешением.

Глобальные hotkeys на Linux по умолчанию используют `XGrabKey` adapter в подтвержденной X11-сессии; переключатель остается недоступным на Wayland/XWayland. Регистрация клавиш не запрашивает screen/audio permissions и не запускает capture.

Микрофон в Linux использует Java Sound и разрешается preflight-ом, после чего недоступность устройства возвращается самим adapter-ом. System audio разрешается только когда production wiring обнаружил `pactl` и `parec`; GUI показывает только реальные PulseAudio monitor sources. Это также работает с PipeWire при активном `pipewire-pulse` compatibility server. Включение остаётся явным через toggle, а отсутствие команд или monitor source не маскируется. Для Wayland захват экрана по-прежнему требует будущий xdg-desktop-portal и PipeWire flow с явным пользовательским выбором источника.

## macOS

Production wiring использует native `CGPreflightScreenCaptureAccess` для проверки Screen Recording и вызывает `CGRequestScreenCaptureAccess` перед Start или автоматическим preview выбранного источника. Отказ возвращает инструкции для `System Settings > Privacy & Security > Screen Recording` до открытия output. Для микрофона AVFoundation TCC status проверяется заранее: denied/restricted блокируются, authorized разрешается, а not-determined передается Java Sound только после явного включения микрофона и запуска записи, чтобы системный prompt не появлялся при старте приложения. System-audio capture текущим macOS backend-ом не поддерживается.

Native app bundle содержит `NSScreenCaptureUsageDescription` и `NSMicrophoneUsageDescription`. Текущий window/application image path основан на deprecated CoreGraphics API и должен быть заменен ScreenCaptureKit; до hardware smoke на поддерживаемых macOS версиях нельзя считать этот backend полностью проверенным.

Включенные по умолчанию global hotkeys используют Carbon `RegisterEventHotKey` и не требуют Screen Recording, Accessibility или Microphone permission. Их регистрация не открывает capture source и не создает output.

## Android

Android target пока не подключен. Целевой flow должен использовать MediaProjection consent для экрана, runtime permission для микрофона и AudioPlaybackCapture только для разрешенных приложений. Foreground service и постоянное системное уведомление обязательны во время записи.

## Инварианты безопасности

Permission preflight дополняется, но не заменяется контрактом владения и cleanup нативных ресурсов из [native-ownership.md](native-ownership.md).

- разрешение не подменяет явную команду пользователя на запуск записи;
- отказ или отсутствующий статус блокирует сессию до открытия устройств;
- выбор микрофона и system audio всегда виден в настройках сессии;
- приложение не повышает привилегии и не обходит DRM/protected surfaces;
- все файлы остаются локальными, если пользователь сам не перемещает их во внешнее хранилище.
