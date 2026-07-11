# Владение нативными ресурсами

Этот документ задает обязательный контракт для platform adapters Mission Recorder. Он описывает уже реализованные Windows, Linux/X11, macOS CoreGraphics и JVM backend-ы и требования к будущим Wayland, ScreenCaptureKit и Android adapter-ам.

## Общие правила

- common/KMP-код получает только stable id, значения, кадры и доменные ошибки; указатели, COM-интерфейсы, file descriptors и native handles не выходят из platform module;
- adapter, который успешно получил ресурс, отвечает за его освобождение, если API явно не обозначает ресурс как borrowed;
- thread-affine ресурсы создаются, используются и освобождаются на owning thread или queue;
- частично созданная цепочка освобождается при любой ошибке и в обратном порядке;
- cancellation обязана проходить через `finally` и закрывать устройства, leases и временные объекты;
- `close()` долгоживущего adapter-а должен быть безопасен для повторного вызова;
- финализаторы и сборщик мусора не считаются механизмом владения нативным ресурсом;
- permission preflight выполняется до открытия capture API и создания output, как описано в [permissions.md](permissions.md).

## Windows: User32 и GDI

Реализация находится в `capture-windows-jna`. Обнаруженные `HWND` принадлежат Windows или записываемому приложению. Mission Recorder хранит только opaque id, повторно проверяет окно перед кадром и никогда не вызывает для него `DestroyWindow` или `CloseHandle`.

При захвате одного кадра действуют следующие пары владения:

| Ресурс | Владение | Освобождение |
| --- | --- | --- |
| `HWND` выбранного окна | borrowed | не освобождается recorder-ом |
| source `HDC` из `GetWindowDC(HWND)` | owned на время кадра | `ReleaseDC` с тем же `HWND` |
| compatible memory `HDC` | owned на время кадра | `DeleteDC` |
| compatible `HBITMAP` | owned на время кадра | восстановить прежний GDI object, затем `DeleteObject` |
| прежний object из `SelectObject` | borrowed | повторно выбрать в memory DC перед удалением bitmap |

`PrintWindow` и GDI fallback не сохраняют DC или bitmap между кадрами. Любая ошибка после частичного выделения проходит через `finally`, который восстанавливает selection и освобождает созданные объекты. Текущий cursor overlay читает только позицию указателя и рисует собственный RGBA-образ, поэтому дополнительными Win32 cursor handles не владеет.

## Windows: WASAPI и COM

Реализация находится в `audio-windows-wasapi`. Фабрика создает один daemon dispatcher `mission-recorder-wasapi`; перечисление endpoints, открытие клиента, чтение пакетов и закрытие выполняются на этом потоке.

- успешный `CoInitializeEx` парно завершается `CoUninitialize` на том же потоке;
- `IMMDeviceEnumerator`, collection, device, `IAudioClient` и `IAudioCaptureClient` освобождаются через `Release` в обратном порядке;
- строки endpoint id и mix format, выделенные COM, освобождаются через `CoTaskMemFree`;
- `PROPVARIANT` friendly name очищается через `PropVariantClear`;
- каждый успешный `IAudioCaptureClient.GetBuffer` всегда завершается `ReleaseBuffer`, включая ошибку преобразования пакета;
- открытый loopback client владеет COM apartment и интерфейсами до `close()`; его закрытие атомарно и идемпотентно;
- при ошибке открытия освобождаются все уже полученные интерфейсы и COM apartment;
- внешний `WindowsWasapiAudioAdapters.close()` вызывается только после остановки capture flows и закрывает выделенный dispatcher.

Endpoint id остается строковым opaque идентификатором. COM pointers и HRESULT не входят в common API.

## Windows: глобальные hotkeys

`WindowsGlobalHotkeyService` владеет daemon message-loop thread и зарегистрированными hotkey ids, но не хранит внешние native handles.

- очередь сообщений создается и hotkeys регистрируются на worker thread;
- `close()` атомарно публикует `WM_QUIT` и ожидает завершения потока с ограниченным timeout;
- worker `finally` снимает регистрации в обратном порядке и завершает startup latch;
- ошибка старта закрывает уже созданную службу;
- ошибка регистрации или message loop преобразуется в типизированное событие/исключение без утечки native state.

## JVM/AWT

`capture-desktop-awt` не передает AWT/Robot/BufferedImage объекты в common API. Overlay выбора области создается и уничтожается внутри desktop adapter-а; Swing windows закрываются через `dispose` на EDT. Добавление platform handle к AWT-реализации потребует отдельной явной пары acquire/release по правилам этого документа.

## Linux X11

`capture-linux-x11` хранит X11 handles только внутри native adapter-а. Window id является borrowed opaque значением X server и никогда не уничтожается Mission Recorder. Каждая discovery/capture операция открывает собственный `Display` и закрывает его через `XCloseDisplay` на том же single-thread dispatcher-е.

| Ресурс | Владение | Освобождение |
|---|---|---|
| `Window` выбранного окна/root | borrowed | не освобождается recorder-ом |
| `Display*` | owned | `XCloseDisplay` в `finally` |
| buffers из `XGetWindowProperty` | owned | `XFree` после копирования |
| children array из `XQueryTree` | owned | `XFree` после копирования ids |
| `XImage*` из `XGetImage` | owned | `XDestroyImage` после RGBA conversion |

Process-wide Xlib error handler устанавливается только внутри синхронизированного error trap; pending requests завершаются через `XSync` до восстановления предыдущего handler-а. При shutdown сначала прекращается сбор Flow, затем закрывается dispatcher. Packed image dimensions, stride, bpp и RGB masks проверяются до чтения native buffer.

`LinuxX11GlobalHotkeyService` владеет отдельным `Display*`, passive grabs и blocking event-loop thread. Worker открывает display, регистрирует четыре lock-варианта каждого gesture и снимает их в обратном порядке перед `XCloseDisplay`. `close()` посылает `ClientMessage` через отдельное короткоживущее X11 connection, поэтому owning display не используется с двух потоков. Ошибка `XGrabKey` обнаруживается синхронным error trap и откатывает уже зарегистрированные grabs; native window/display ids не выходят в `hotkey-core`.

## macOS CoreGraphics

`capture-macos-coregraphics` копирует window dictionaries из owned `CFArrayRef` и освобождает array через `CFRelease`. Вложенные dictionary values являются borrowed и отдельно не освобождаются; временные `CFStringRef` keys создаются и освобождаются в пределах одного lookup. Каждый `CGImageRef`, `CGColorSpaceRef` и `CGContextRef` имеет парный release в `finally`; JNA `Memory` живет до завершения RGBA-копирования. Window ids являются borrowed opaque values. Все capture операции сериализованы собственным dispatcher-ом, который закрывается после остановки Flow.

`MacCarbonGlobalHotkeyService` удерживает native callback, `EventHandlerRef` и `EventHotKeyRef` только внутри `hotkey-macos-carbon`. Carbon Event Dispatcher вызывает callback, а service сериализует pressed/released state и lifecycle собственным lock. При ошибке регистрации уже созданные hotkeys снимаются в обратном порядке, затем удаляется handler; штатный `close()` выполняет тот же порядок и обнуляет сильную ссылку на callback только после успешного `RemoveEventHandler`.

## Ошибки и отмена

Platform adapters преобразуют нативные сбои в доменные категории: отказ разрешения, недоступный источник/устройство, неподдерживаемая возможность или внутренняя ошибка adapter-а. Текст может содержать безопасный диагностический код, но common слой не должен получать живой pointer/handle.

Flow capture обязан быть cancellable. Закрытие сессии выполняется в следующем порядке:

1. Запросить stop/cancel recording или replay и прекратить сбор кадров.
2. Освободить packet/frame leases и закрыть открытые capture clients.
3. Завершить jobs, которые читают устройства и audio gates.
4. Закрыть WASAPI dispatcher после завершения его клиентов.
5. Снять global hotkeys и завершить message-loop thread.
6. Уничтожить desktop windows и остальные host resources.

## Требования к будущим backend-ам

Эти пункты являются критериями реализации, а не заявлением о текущей поддержке:

- macOS: задокументировать retain/release для Core Foundation и Objective-C объектов, owning dispatch queue для ScreenCaptureKit callbacks и scoped lifetime для `CVPixelBuffer`/`CMSampleBuffer`;
- Linux: владеть portal request/session/remote objects, закрывать переданные file descriptors и выполнять PipeWire loop/thread и dequeue/queue buffer операции на корректном потоке;
- Linux PulseAudio compatibility adapter владеет дочерним `parec` process и stdout stream: cancellation закрывает stream, завершает process и только затем освобождает выделенный single-thread dispatcher при shutdown приложения;
- Android: явно закрывать `MediaProjection`, `VirtualDisplay`, `AudioRecord`, `MediaCodec`, surfaces и foreground service; cleanup должен работать при cancellation и system callback;
- каждый backend обязан иметь тесты частичной инициализации, повторного `close()`, cancellation и device/source loss;
- ручной smoke должен проверять повторные start/stop, смену устройства и завершение приложения без оставшихся потоков, handles или активного системного индикатора записи.

## Checklist ревью adapter-а

- Для каждого native значения указано `owned` или `borrowed`.
- Для каждого `owned` ресурса существует видимая парная операция освобождения.
- Ошибка на каждом шаге частичной инициализации приводит к cleanup.
- Thread/queue affinity соблюдается при использовании и закрытии.
- Cancellation и shutdown проходят через один cleanup path.
- Повторный `close()` не обращается к уже освобожденному ресурсу.
- Common API не содержит native handle, pointer или platform object.
- Permission denial и device/source loss имеют разные понятные ошибки.
