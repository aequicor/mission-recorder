# Локальное управление CLI-записью и replay buffer

Обычная CLI-запись и foreground replay buffer могут явно открыть локальный control endpoint. Это позволяет другому процессу автоматизации управлять активной сессией без скрытого захвата или облачного сервиса.

## Запуск

В первом терминале:

```powershell
.\gradlew.bat run --args="record screen --output build\demo.mp4 --control-endpoint build\demo.control.json"
```

Параметр `--control-endpoint` является opt-in. Без него сетевой listener и descriptor-файл не создаются.

Во втором терминале или из automation script:

```powershell
.\gradlew.bat run --args="control status --endpoint build\demo.control.json --json"
.\gradlew.bat run --args="control pause --endpoint build\demo.control.json --json"
.\gradlew.bat run --args="control resume --endpoint build\demo.control.json --json"
.\gradlew.bat run --args="control stop --endpoint build\demo.control.json --json"
```

Для установленного приложения вместо Gradle используется исполняемый файл `mission-recorder` с теми же аргументами.

### Replay buffer

В первом терминале:

```powershell
.\gradlew.bat run --args="replay run screen --buffer 5m --output build\replay-final.mp4 --control-endpoint build\replay.control.json"
```

Во втором терминале:

```powershell
.\gradlew.bat run --args="control status --endpoint build\replay.control.json --json"
.\gradlew.bat run --args="control save --endpoint build\replay.control.json --output build\replay-snapshot.mp4 --json"
.\gradlew.bat run --args="control stop --endpoint build\replay.control.json --json"
```

## Семантика

- `status` возвращает state, output path, записанную длительность, счетчики video/audio frames, средний `effectiveFramesPerSecond` и оценку `droppedFrames` относительно целевого FPS;
- для replay `effectiveFramesPerSecond` вычисляется по удержанному интервалу, а `droppedFrames` пока равен нулю до подключения отдельного replay drop detector;
- `pause` оставляет capture devices открытыми, не кодирует кадры во время паузы и сохраняет непрерывную media timeline;
- `resume` продолжает ту же сессию без создания второго output;
- `stop` подтверждает принятие запроса, после чего исходный процесс финализирует MP4 и печатает итоговый результат записи;
- для replay команда `save` сохраняет отдельный MP4 snapshot и оставляет буфер активным; snapshot path должен отличаться от финального `replay run --output`;
- для replay команда `stop` сохраняет актуальный буфер в финальный `--output`, останавливает capture и удаляет descriptor;
- replay не поддерживает `pause` и `resume`, поэтому такие запросы явно отклоняются;
- повторный `pause` идемпотентен, а неверный `resume` и недоступный endpoint возвращают ненулевой exit code и понятную ошибку;
- descriptor удаляется при штатном завершении, cancellation и ошибке записи или replay buffer.

## Локальность и безопасность

Endpoint слушает только `127.0.0.1`. Descriptor содержит версию протокола, случайный токен, порт и PID процесса; запросы передаются как локальный JSON protocol, а на POSIX для descriptor запрашиваются права только владельца. Не публикуйте descriptor и не размещайте его в общем каталоге.

Mission Recorder не перезаписывает уже существующий descriptor. Если процесс аварийно завершился и оставил stale-файл, сначала убедитесь, что указанный PID больше не работает, затем удалите файл вручную или выберите новый путь. Control-команды не запускают capture, не запрашивают permissions и не открывают output.
