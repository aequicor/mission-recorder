# Релизы

Этот документ — источник истины для выпуска Mission Recorder. Версия задаётся один раз в `missionRecorderVersion` внутри `gradle.properties`; native packages и Git tag обязаны использовать то же значение.

## Артефакты

Tag `vMAJOR.MINOR.PATCH` запускает `.github/workflows/release.yml`. Тот же workflow можно запустить вручную с ветки как dry run: он проверит сборку всех пакетов, но не создаст GitHub Release. Workflow сначала выполняет чистые `check` и `build`, затем собирает:

- Windows x64: пользовательский Inno Setup EXE;
- macOS Intel: DMG с суффиксом `macos-x64`;
- macOS Apple Silicon: DMG с суффиксом `macos-arm64`;
- Ubuntu/Debian x64: DEB.

После успешной сборки workflow извлекает секцию версии из `CHANGELOG.md`, создаёт `SHA256SUMS.txt` и публикует GitHub Release. Исходные архивы GitHub добавляет автоматически.

JDK jpackage не принимает macOS app version с нулевым major. Для pre-1.0 релиза `0.MINOR.PATCH` build script использует внутри bundle `1.MINOR.PATCH`, а workflow переименовывает DMG обратно в соответствии с `missionRecorderVersion`; Git tag и пользовательская версия проекта не меняются.

## Проверка перед тегом

1. Обновить `missionRecorderVersion` и добавить датированную секцию той же версии в `CHANGELOG.md`.
2. Проверить, что README описывает фактически поддерживаемые платформы и известные ограничения.
3. Из чистого checkout выполнить:

   ```powershell
   .\gradlew.bat clean check build
   ```

4. Собрать пакет текущей платформы и установить его:

   ```powershell
   .\gradlew.bat :desktop-app:packageReleaseWindowsInstaller
   ```

   Для локальной Windows-сборки нужен Inno Setup 6.7.1; путь к `ISCC.exe` можно передать через `-PinnoSetupCompiler=C:\path\to\ISCC.exe` или переменную `INNO_SETUP_COMPILER`. На macOS используются `packageReleaseDmg`, на Linux — `packageReleaseDeb`.

5. На Windows проверить, что EXE устанавливается без UAC в `%LOCALAPPDATA%\Programs\Mission Recorder` и не удаляет старые MSI-установки. На macOS проверить, что у смонтированного DMG фирменная иконка Mission Recorder, а не стандартная Java-иконка, и что в корне отсутствуют служебные каталоги `.background` и `.fseventsd`. При включённом показе скрытых файлов Finder может показать `.VolumeIcon.icns`: этот файл необходим для фирменной иконки тома. В установленном приложении вручную проверить запуск, preview, короткую запись с остановкой, открытие MP4 и выход через tray. Проверки устройств и разрешений выполняются на реальном целевом хосте.
6. Просмотреть staged diff и убедиться, что в нём нет секретов, локальных recordings, settings и временных design-файлов.
7. Запустить `Release` вручную на release commit и дождаться успешной сборки пакетов всех платформ.
8. После публикации проверить все assets и `SHA256SUMS.txt`, установить хотя бы пакет основной платформы и убедиться, что release workflow завершился успешно.

## Публикация

Release commit сначала попадает в `master`. Затем создаётся annotated tag на этом commit и отправляется отдельно:

```powershell
git tag -a v0.1.5 -m "Mission Recorder 0.1.5"
git push origin master
git push origin v0.1.5
```

Tag нельзя перемещать или переиспользовать. Если workflow упал, исправление выпускается новым patch-релизом. Публикацию нельзя считать завершённой, пока GitHub Release не содержит четыре desktop assets и checksum-файл.

## Подпись пакетов

Пока signing credentials не настроены, workflow выпускает unsigned Windows installer и unsigned/unnotarized macOS DMG. Сертификаты и пароли должны передаваться только через GitHub Actions secrets; добавлять их в Gradle, repository files или logs запрещено.
