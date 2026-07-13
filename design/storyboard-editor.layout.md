---
screen: storyboardEditor
page: Montage
sourceLocale: ru-RU
targetLocales: [ru-RU]
theme: dark
density: compact
platform: desktop
frame: { width: 1600, height: 1000 }
---

# Prototype Variables

String selectedFrameId default «frame_0142»
Boolean showOnlyImportant default false
Boolean allFramesIncluded default false
Boolean isPlaying default false
Boolean importantFrameAdded default false
Boolean frame01Included default true
Boolean frame02Included default false
Boolean frame03Included default true
Boolean frame04Included default false
Boolean frame05Included default false
String exportMode default «separatePng»

# Storyboard Editor

## Frame: Desktop Workspace id desktop_workspace 1600 by 1000 position 0 0 clip overflow (x hidden y hidden) color #111519

### Frame: Editor Header id editor_header 1600 by 72 position 0 0 color #1A2026 stroke #37414B 1 center

Button id back_to_recorder «Запись» key editor.header.back font «Sans Serif» semibold size 13 line-height 18 width 108 height 36 position 24 18 maxLines 1 text-align right text-valign center color #A8B2BA radius 8 onClick back
Icon id back_icon 18 by 18 position 34 27 svg design/res/icons/arrow_back.svg color #A8B2BA
Text id editor_title «Монтаж · Космодром 12 июля» key editor.header.title font «Sans Serif» semibold size 18 line-height 24 width 420 height 24 position 156 16 maxLines 1 color #E7ECF0
Text id editor_project_meta «38:24 · 1920×1080 · 60 FPS» key editor.header.meta font «Monospace» size 12 line-height 16 width 420 height 16 position 156 42 maxLines 1 color #A8B2BA
Button id undo_action «Отменить» key editor.header.undo font «Sans Serif» size 12 line-height 18 width 44 height 36 position 1372 18 maxLines 1 text-valign center opacity 0 radius 8
Icon id undo_icon 20 by 20 position 1384 26 svg design/res/icons/undo.svg color #A8B2BA
Button id redo_action «Повторить» key editor.header.redo font «Sans Serif» size 12 line-height 18 width 44 height 36 position 1424 18 maxLines 1 text-valign center opacity 0 radius 8
Icon id redo_icon 20 by 20 position 1436 26 svg design/res/icons/redo.svg color #A8B2BA
Button id add_media_action «Медиа» key editor.header.addMedia font «Sans Serif» semibold size 13 line-height 18 width 100 height 36 position 1476 18 maxLines 1 text-align right text-valign center color #4C8CCA radius 8
Icon id add_media_icon 18 by 18 position 1486 27 svg design/res/icons/add_circle.svg color #4C8CCA

### Frame: Video Review Workspace id video_review_workspace 1112 by 880 position 24 96 clip color #1A2026 stroke #37414B 1 center radius 12

Text id video_review_title «Видеоряд» key editor.video.title font «Sans Serif» semibold size 24 line-height 32 width 260 height 32 position 24 20 maxLines 1 color #E7ECF0

#### Frame: Video Preview id video_preview 1064 by 598 position 24 88 clip color #0C1116 stroke #37414B 1 center radius 8

Rectangle id preview_background 1064 by 598 position 0 0 gradient (linear from (0 0) to (1 1) stops (#263B4B at 0) (#11191F at 1))

##### Group: id group_3 name «Group» 780 by 539.3681163787842 position 0 0 absolute

###### Rectangle: id preview_content 1064 by 597.9999980926514 position 0 0 absolute color #314F63 radius 5 constraints (horizontal left-right vertical top-bottom)

#### Frame: Playback Transport id playback_transport 1064 by 52 position 24 702 color #161C21 stroke #37414B 1 center radius 8

Text id transport_time «07:32.450» key editor.transport.time font «Monospace» bold size 12 line-height 18 width 96 height 36 position 16 8 maxLines 1 text-valign center color #E7ECF0

##### Image: id image_2 name «play_arrow.svg» 24 by 24 position 520 14 absolute color #8E99A7 blend screen media (asset res/play_arrow-1.svg)


##### Image: id image_6 name «skip_previous.svg» 24 by 24 position 473.33333333333326 14 absolute media (asset res/skip_previous-1.svg)

##### Image: id image_3 name «skip_next.svg» 24 by 24 position 566.6666666666667 14 absolute media (asset res/skip_next-1.svg)

##### Group: id group_6 name «Group» 119.61516106273882 by 36 position 641.4343083699544 8 absolute

###### Button: id mark_important characters «+ важный кадр» name «Важный кадр» 89.35389709472656 by 36 position 30.26126396801226 0 absolute color #F3C64D radius 8 size 12 semibold font «Sans Serif» line-height 16 text-align left text-valign center maxLines 1 onClick setVariable (importantFrameAdded) to (true)

###### Image: id image_5 name «video_file.svg» 24 by 24 position 0 6 absolute media (asset res/video_file.svg)

#### Frame: Playback Timeline id playback_timeline 1064 by 90 position 24 766 clip color #161C21 stroke #37414B 1 center radius 8

Ellipse id timeline_playhead_handle 12 by 12 position 367 2 color #FF5B64

##### Group: id group_4 name «Group» 1032 by 57.35795372724533 position 16 16 absolute

###### Rectangle: id timeline_track 1032 by 48 position 0 5 absolute color #20272D radius 7

###### Rectangle: id timeline_segment_01 142 by 36 position 12 11 absolute color #344A5C stroke #4C8CCA 1 center radius 4

###### Rectangle: id timeline_segment_02 126 by 36 position 164 11 absolute color #273C4B radius 4

###### Rectangle: id timeline_segment_03 164 by 36 position 300 11 absolute color #5A3040 radius 4

###### Rectangle: id timeline_segment_04 132 by 36 position 474 11 absolute color #2F4B44 radius 4

###### Rectangle: id timeline_segment_05 194 by 36 position 616 11 absolute color #3C3658 radius 4

###### Rectangle: id timeline_segment_06 200 by 36 position 820 11 absolute color #36526A radius 4

###### Rectangle: id timeline_marker_01 4 by 48 position 180 5 absolute color #F3C64D radius 2

###### Rectangle: id timeline_marker_02 4 by 48 position 476 5 absolute color #F3C64D radius 2

###### Rectangle: id timeline_marker_03 4 by 48 position 862 5 absolute color #F3C64D radius 2

###### Rectangle: id timeline_playhead 2 by 62.32659149169922 position 355.89753361046314 -9.254506947185632 absolute color #FF5B64 radius 1

##### Group: id group_5 name «Group» 1032 by 12 position 16 74 absolute

###### Text: id timeline_start characters «00:00» name «00:00» 48 by 12 position 0 0 absolute color #7F8A93 size 9 key editor.timeline.start font «Monospace» line-height 12 maxLines 1

###### Text: id timeline_quarter characters «09:36» name «09:36» 48 by 12 position 254 0 absolute color #7F8A93 size 9 key editor.timeline.quarter font «Monospace» line-height 12 maxLines 1

###### Text: id timeline_middle characters «19:12» name «19:12» 48 by 12 position 508 0 absolute color #7F8A93 size 9 key editor.timeline.middle font «Monospace» line-height 12 maxLines 1

###### Text: id timeline_three_quarters characters «28:48» name «28:48» 48 by 12 position 762 0 absolute color #7F8A93 size 9 key editor.timeline.threeQuarters font «Monospace» line-height 12 maxLines 1

###### Text: id timeline_end characters «38:24» name «38:24» 48 by 12 position 984 0 absolute color #7F8A93 size 9 key editor.timeline.end font «Monospace» line-height 12 maxLines 1

### Frame: id storyboard_export_panel name «Storyboard And Export Panel» 416 by 866.2420167922974 position 1160 96 color #1A2026 stroke #37414B 1 center radius 12 clip

Text id storyboard_title «Раскадровка» key editor.storyboard.title font «Sans Serif» semibold size 18 line-height 24 width 220 height 24 position 24 20 maxLines 1 text-valign center color #E7ECF0
Text id storyboard_count «7 из 18 выбрано» key editor.storyboard.count font «Sans Serif» size 12 line-height 18 width 140 height 24 position 252 20 maxLines 1 text-align right text-valign center color #A8B2BA

#### Frame: Storyboard Footer id storyboard_footer 368 by 264 position 24 577.4946778615315 color #1A2026

Text id export_title «Экспорт» key editor.export.title font «Sans Serif» semibold size 18 line-height 24 width 160 height 24 position 0 76 maxLines 1 color #E7ECF0
Text id export_summary «Выбрано 7 кадров» key editor.export.summary font «Sans Serif» size 12 line-height 18 width 160 height 24 position 208 76 maxLines 1 text-align right text-valign center color #A8B2BA
Button id export_single_file «Экспорт 1 файлом» key editor.export.singleFile font «Sans Serif» bold size 12 line-height 16 width 180 height 48 position 0 112 maxLines 2 text-align center text-valign center color #4C8CCA radius 8 onClick setVariable (exportMode) to («contactSheet»)
Button id copy_storyboard_to_clipboard «Сохранить раскадровку в буфер обмена» key editor.export.copyToClipboard font «Sans Serif» semibold size 11 line-height 14 width 180 height 48 position 188 112 maxLines 2 text-align center text-valign center color #A8B2BA stroke #4C8CCA 1 center radius 8 onClick setVariable (exportMode) to («clipboard»)
Button id export_separate_files «Экспорт нескольких файлов» key editor.export.separateFiles font «Sans Serif» semibold size 13 line-height 18 width 368 height 48 position 0 168 maxLines 1 text-valign center color #A8B2BA stroke #4C8CCA 1 center radius 8 onClick setVariable (exportMode) to («separatePng»)
Text id export_destination «Исходный размер · Записи / Кадры» key editor.export.destination font «Sans Serif» size 11 line-height 16 width 368 height 16 position 0 232 maxLines 1 text-align center color #A8B2BA


#### Frame: Storyboard Frame List id storyboard_frame_list 368 by 588.2491035461426 position 24 51.36700886089693 clip overflow (y auto) scroll (direction vertical) color #161C21 stroke #37414B 1 center radius 8

##### Frame: Storyboard Row 01 id storyboard_row_01 352 by 88 position 8 8 color #242B32 stroke #4C8CCA 2 center radius 7

Rectangle id row_01_thumbnail 124 by 70 position 8 9 radius 5 gradient (linear from (0 0) to (1 1) stops (#344A5C at 0) (#17222B at 1))
Text id row_01_time «07:32.450» key editor.storyboard.row01.time font «Monospace» bold size 11 line-height 16 width 84 height 16 position 144 12 maxLines 1 color #E7ECF0
Text id row_01_status «★ Важный» key editor.storyboard.row01.status font «Sans Serif» semibold size 10 line-height 14 width 96 height 14 position 144 34 maxLines 1 color #F3C64D
Button id row_01_include «В раскадровке» key editor.storyboard.row01.include font «Sans Serif» semibold size 10 line-height 14 width 132 height 24 position 168 55 maxLines 1 color #E7ECF0 onClick setVariable (frame01Included) to (false) text-valign center

###### Group: id group_1 name «Group» 18 by 18 position 144 58 absolute

Rectangle id row_01_checkbox 18 by 18 position 0 0 absolute color #4C8CCA stroke #79A4CC 1 center radius 4

Text id row_01_checkbox_mark «✓» name «✓» 14 by 16 position 2 0 absolute color #FFFFFF size 12 key editor.storyboard.row01.checked bold font «Sans Serif» line-height 16 text-align center maxLines 1

##### Frame: Storyboard Row 02 id storyboard_row_02 352 by 88 position 8 104 color #242B32 stroke #37414B 1 center radius 7

Rectangle id row_02_thumbnail 124 by 70 position 8 9 radius 5 gradient (linear from (0 0) to (1 1) stops (#273C4B at 0) (#12191F at 1))
Text id row_02_time «09:18.233» key editor.storyboard.row02.time font «Monospace» bold size 11 line-height 16 width 84 height 16 position 144 12 maxLines 1 color #E7ECF0
Text id row_02_status «Обычный кадр» key editor.storyboard.row02.status font «Sans Serif» size 10 line-height 14 width 96 height 14 position 144 34 maxLines 1 color #7F8A93
Rectangle id row_02_checkbox 18 by 18 position 144 58 radius 4 color #161C21 stroke #7F8A93 1 center
Button id row_02_include «В раскадровке» key editor.storyboard.row02.include font «Sans Serif» size 10 line-height 14 width 132 height 24 position 168 55 maxLines 1 text-valign center color #A8B2BA onClick setVariable (frame02Included) to (true)

##### Frame: Storyboard Row 03 id storyboard_row_03 352 by 88 position 8 200 color #242B32 stroke #37414B 1 center radius 7

Rectangle id row_03_thumbnail 124 by 70 position 8 9 radius 5 gradient (linear from (0 0) to (1 1) stops (#5A3040 at 0) (#23171D at 1))
Text id row_03_time «12:04.817» key editor.storyboard.row03.time font «Monospace» bold size 11 line-height 16 width 84 height 16 position 144 12 maxLines 1 color #E7ECF0
Text id row_03_status «★ Важный» key editor.storyboard.row03.status font «Sans Serif» semibold size 10 line-height 14 width 96 height 14 position 144 34 maxLines 1 color #F3C64D
Rectangle id row_03_checkbox 18 by 18 position 144 58 radius 4 color #4C8CCA stroke #79A4CC 1 center
Text id row_03_checkbox_mark «✓» key editor.storyboard.row03.checked font «Sans Serif» bold size 12 line-height 16 width 14 height 16 position 146 58 maxLines 1 text-align center color #FFFFFF
Button id row_03_include «В раскадровке» key editor.storyboard.row03.include font «Sans Serif» semibold size 10 line-height 14 width 132 height 24 position 168 55 maxLines 1 text-valign center color #E7ECF0 onClick setVariable (frame03Included) to (false)

##### Frame: Storyboard Row 04 id storyboard_row_04 352 by 88 position 8 296 color #242B32 stroke #37414B 1 center radius 7

Rectangle id row_04_thumbnail 124 by 70 position 8 9 radius 5 gradient (linear from (0 0) to (1 1) stops (#2F4B44 at 0) (#14221E at 1))
Text id row_04_time «16:41.100» key editor.storyboard.row04.time font «Monospace» bold size 11 line-height 16 width 84 height 16 position 144 12 maxLines 1 color #E7ECF0
Text id row_04_status «Обычный кадр» key editor.storyboard.row04.status font «Sans Serif» size 10 line-height 14 width 96 height 14 position 144 34 maxLines 1 color #7F8A93
Rectangle id row_04_checkbox 18 by 18 position 144 58 radius 4 color #161C21 stroke #7F8A93 1 center
Button id row_04_include «В раскадровке» key editor.storyboard.row04.include font «Sans Serif» size 10 line-height 14 width 132 height 24 position 168 55 maxLines 1 text-valign center color #A8B2BA onClick setVariable (frame04Included) to (true)

##### Frame: Storyboard Row 05 id storyboard_row_05 352 by 88 position 8 392 color #242B32 stroke #37414B 1 center radius 7

Rectangle id row_05_thumbnail 124 by 70 position 8 9 radius 5 gradient (linear from (0 0) to (1 1) stops (#3C3658 at 0) (#191626 at 1))
Text id row_05_time «23:08.650» key editor.storyboard.row05.time font «Monospace» bold size 11 line-height 16 width 84 height 16 position 144 12 maxLines 1 color #E7ECF0
Text id row_05_status «Обычный кадр» key editor.storyboard.row05.status font «Sans Serif» size 10 line-height 14 width 96 height 14 position 144 34 maxLines 1 color #7F8A93
Rectangle id row_05_checkbox 18 by 18 position 144 58 radius 4 color #161C21 stroke #7F8A93 1 center
Button id row_05_include «В раскадровке» key editor.storyboard.row05.include font «Sans Serif» size 10 line-height 14 width 132 height 24 position 168 55 maxLines 1 text-valign center color #A8B2BA onClick setVariable (frame05Included) to (true)

#### Group: id group_2 name «Group copy» 18 by 18 position 278.7098693847656 23 absolute

Rectangle id row_01_1 18 by 18 position 0 0 absolute color #4C8CCA stroke #79A4CC 1 center radius 4

Text id row_01_checkbox_1 «✓» name «✓» 14 by 16 position 2 0 absolute color #FFFFFF size 12 key editor.storyboard.row01.checked bold font «Sans Serif» line-height 16 text-align center maxLines 1

## Image: id image_1 name «folder_open.svg» 24 by 24 position 170.71238708496094 289.15679931640625 absolute media (asset res/folder_open.svg)

## Image: id image_4 name «upload_file.svg» 24 by 24 position 652.7841796875 227.85289001464844 absolute media (asset res/upload_file.svg)

















