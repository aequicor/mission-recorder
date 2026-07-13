---
screen: recorderPip
page: Recording
sourceLocale: ru-RU
targetLocales: [ru-RU]
theme: dark
density: compact
platform: desktop
frame: { width: 344, height: 246 }
---

# Recorder PiP

## PiP Window id recorder_pip_window name «Окно управления записью» column 344 by 246 clip radius 18 color #0E1419 stroke #3A4853 1 center effect (dropShadow color #00000066 offset (0 10) blur 28 spread 0) visible {{pipVisible}}

### Preview Region id preview_region name «Предпросмотр источника» free width (fill) height 178 clip color #0E1419

Image id preview_image 344 by 178 position 0 0 media (asset {{previewFrame}} crop focus center alt «Предпросмотр выбранного источника записи») visible {{!previewLoading&&!previewUnavailable}}

#### Preview Empty State id preview_empty_state name «Источник недоступен» column 344 by 178 position 0 0 padding 56 gap 8 distribute center align (inline center) color #0E1419 visible {{previewUnavailable}}

Vector id preview_empty_icon 36 by 36 viewbox (0 0 24 24) path «M3 5H21V19H3Z M7 9H17V15H7Z» color #78B5E8
Text id preview_empty_text «Источник недоступен» key recorder.pip.preview.unavailable font «Inter» semibold size 14 line-height 20 text-align center color #F2F6F8 width (fill) height (hug) maxLines 2

#### Preview Loading State id preview_loading_state name «Подготовка предпросмотра» column 344 by 178 position 0 0 padding 56 gap 10 distribute center align (inline center) color #0E1419 visible {{previewLoading}}

Vector id preview_loading_icon 36 by 36 viewbox (0 0 24 24) path «M2 5H22V19H2Z M10 8L16 12L10 16Z» color #78B5E8
Text id preview_loading_text «Подготовка предпросмотра» key recorder.pip.preview.preparing font «Inter» semibold size 13 line-height 20 text-align center color #F2F6F8 width (fill) height (hug) maxLines 2
##### Preview Loading Progress id preview_loading_progress_bar free 180 by 4

Rectangle id preview_loading_track 180 by 4 position 0 0 radius 2 color #222D35
Rectangle id preview_loading_progress 92 by 4 position 0 0 radius 2 color #78B5E8

#### Header Surface id header_surface free 344 by 52 position 0 0 color (#171F26 opacity 0.94)

#### Recording Status id recording_status row width 200 height 44 position 12 4 gap 8 align (block center) visible {{recordingState==1&&!replayActive}}

Ellipse id recording_status_dot 8 by 8 color #FF7B84
Text id recording_status_label «Запись» key recorder.pip.status.recording font «Inter» semibold size 14 line-height 20 color #FF7B84 width (hug) height (hug) maxLines 1

#### Paused Status id paused_status row width 200 height 44 position 12 4 gap 8 align (block center) visible {{recordingState==2&&!replayActive}}

Ellipse id paused_status_dot 8 by 8 color #F3C64D
Text id paused_status_label «Пауза» key recorder.pip.status.paused font «Inter» semibold size 14 line-height 20 color #F3C64D width (hug) height (hug) maxLines 1

#### Ready Status id ready_status row width 200 height 44 position 12 4 gap 8 align (block center) visible {{recordingState==0&&!replayActive}}

Ellipse id ready_status_dot 8 by 8 color #B8C4CC
Text id ready_status_label «Готов» key recorder.pip.status.ready font «Inter» semibold size 14 line-height 20 color #B8C4CC width (hug) height (hug) maxLines 1

#### Replay Status id replay_status row width 200 height 44 position 12 4 gap 8 align (block center) visible {{replayActive}}

Ellipse id replay_status_dot 8 by 8 color #78B5E8
Text id replay_status_label «Буферизация» key recorder.pip.status.replayBuffering font «Inter» semibold size 14 line-height 20 color #78B5E8 width (hug) height (hug) maxLines 1

#### Window Actions id window_actions row width 96 height 44 position 244 4 gap 4 align (block center)

##### Expand Action id expand_action free 44 by 44

Button id expand_button «Открыть Mission Recorder» key recorder.pip.actions.expand font «Inter» size 1 line-height 1 color #FFFFFF width 44 height 44 opacity 0.01 maxLines 1 onClick setVariable (pipVisible) to (false) note «Восстанавливает главное окно Mission Recorder»
Icon id expand_icon 20 by 20 position 12 12 svg compose-ui/src/commonMain/composeResources/drawable/open_in_full.svg viewbox (0 -960 960 960) color #B8C4CC

##### Hide Action id hide_action free 44 by 44

Button id hide_button «Скрыть PiP» key recorder.pip.actions.hide font «Inter» size 1 line-height 1 color #FFFFFF width 44 height 44 opacity 0.01 maxLines 1 onClick setVariable (pipVisible) to (false)
Icon id hide_icon 20 by 20 position 12 12 svg compose-ui/src/commonMain/composeResources/drawable/close.svg viewbox (0 -960 960 960) color #B8C4CC

#### Elapsed Time id elapsed_time_surface row width (hug) height 34 position 10 134 padding 5 10 align (block center) radius 10 color (#171F26 opacity 0.94)

Text id elapsed_time «00:03:42» key recorder.pip.elapsedTime font «monospace» semibold size 16 line-height 22 color #F2F6F8 characters {{elapsedTime}} width (hug) height (hug) maxLines 1

#### Section: id important_frame_feedback name «Important Frame Feedback» 344 by 178 position 0 0 auto-layout column gap 8 distribute center color (#F3C64D opacity 0.92) align (inline center) afterDelay (300) setVariable (importantFrameFeedbackVisible) to (false)

Star id important_frame_feedback_icon 36 by 36 points 5 inner 0.45 color #292410
Text id important_frame_feedback_text «Важный кадр сохранён» key recorder.pip.importantFrame.saved font «Inter» semibold size 14 line-height 20 text-align center color #292410 width (fill) height (hug) maxLines 1

### Transport Bar id transport_bar name «Оперативное управление» free width (fill) height 68 color #171F26 stroke #3A4853 1 center

#### Recording Controls id recording_controls free 344 by 68 position 0 0 visible {{!replayActive}}

##### Pause Action id pause_action free 44 by 44 position 88 12 visible {{recordingState==1}}

Button id pause_button «Пауза» key recorder.pip.actions.pause font «Inter» size 1 line-height 1 color #FFFFFF width 44 height 44 opacity 0.01 maxLines 1 onClick setVariable (recordingState) to (2)
Vector id pause_icon 22 by 22 position 11 11 viewbox (0 0 24 24) path «M5 4H9V20H5Z M15 4H19V20H15Z» color #F2F6F8

##### Resume Action id resume_action free 44 by 44 position 88 12 visible {{recordingState==2}}

Button id resume_button «Продолжить» key recorder.pip.actions.resume font «Inter» size 1 line-height 1 color #FFFFFF width 44 height 44 opacity 0.01 maxLines 1 onClick setVariable (recordingState) to (1)
Vector id resume_icon 22 by 22 position 11 11 viewbox (0 0 24 24) path «M8 5V19L19 12Z» color #F2F6F8

##### Inactive Pause Action id inactive_pause_action free 44 by 44 position 88 12 visible {{recordingState==0}}

Vector id inactive_pause_icon 22 by 22 position 11 11 viewbox (0 0 24 24) path «M5 4H9V20H5Z M15 4H19V20H15Z» color #B8C4CC opacity 0.45

##### Stop Recording Action id stop_recording_action free 52 by 52 position 146 8 visible {{recordingState!=0}}

Ellipse id stop_recording_surface 52 by 52 color #C83F49
Button id stop_recording_button «Остановить запись» key recorder.pip.actions.stop font «Inter» size 1 line-height 1 color #FFFFFF width 52 height 52 position 0 0 opacity 0.01 maxLines 1 onClick setVariable (recordingState) to (0)
Rectangle id stop_recording_icon 20 by 20 position 16 16 radius 4 color #FFFFFF

##### Start Recording Action id start_recording_action free 52 by 52 position 146 8 visible {{recordingState==0}}

Ellipse id start_recording_surface 52 by 52 color #26619C
Button id start_recording_button «Начать запись» key recorder.pip.actions.record font «Inter» size 1 line-height 1 color #FFFFFF width 52 height 52 position 0 0 opacity 0.01 maxLines 1 onClick setVariable (recordingState) to (1)
Ellipse id start_recording_icon 20 by 20 position 16 16 color #FFFFFF

##### Important Frame Action id important_frame_action free 44 by 44 position 212 12 visible {{recordingState==1}}

Button id important_frame_button «Отметить важный кадр» key recorder.pip.actions.importantFrame font «Inter» size 1 line-height 1 color #FFFFFF width 44 height 44 opacity 0.01 maxLines 1 onClick setVariable (importantFrameFeedbackVisible) to (true)
Star id important_frame_icon 22 by 22 position 11 11 points 5 inner 0.45 color #F3C64D

##### Inactive Important Frame Action id inactive_important_frame_action free 44 by 44 position 212 12 visible {{recordingState!=1}}

Star id inactive_important_frame_icon 22 by 22 position 11 11 points 5 inner 0.45 color #B8C4CC opacity 0.45

#### Replay Controls id replay_controls free 344 by 68 position 0 0 visible {{replayActive}}

##### Save Replay Action id save_replay_action free 48 by 48 position 117 10

Button id save_replay_button «Сохранить replay» key recorder.pip.actions.saveReplay font «Inter» size 1 line-height 1 color #FFFFFF width 48 height 48 opacity 0.01 maxLines 1 onClick setVariable (replaySaved) to (true)
Vector id save_replay_icon 24 by 24 position 12 12 viewbox (0 0 24 24) path «M12 3A9 9 0 1 0 21 12H18A6 6 0 1 1 12 6V9L17 5L12 1V3Z» color #78B5E8

##### Stop Replay Action id stop_replay_action free 48 by 48 position 179 10

Ellipse id stop_replay_surface 48 by 48 color #C83F49
Button id stop_replay_button «Остановить replay buffer» key recorder.pip.actions.stopReplay font «Inter» size 1 line-height 1 color #FFFFFF width 48 height 48 position 0 0 opacity 0.01 maxLines 1 onClick setVariable (replayActive) to (false)
Rectangle id stop_replay_icon 18 by 18 position 15 15 radius 4 color #FFFFFF

# Prototype Variables

Boolean pipVisible default true
Number recordingState default 1
Boolean replayActive default false
Boolean replaySaved default false
Boolean previewLoading default false
Boolean previewUnavailable default false
Boolean importantFrameFeedbackVisible default false
String previewFrame default «capture://selected-source»
String elapsedTime default «00:03:42»
