#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef SourceDir
  #error SourceDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif

#define AppName "Mission Recorder"
#define AppPublisher "Mission Recorder"
#define AppExeName "Mission Recorder.exe"

[Setup]
; This AppId must stay independent from the legacy jpackage MSI UpgradeCode.
AppId={{501AA1FB-41EE-455D-939B-34C93247FB60}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL=https://github.com/aequicor/mission-recorder
AppSupportURL=https://github.com/aequicor/mission-recorder/issues
AppUpdatesURL=https://github.com/aequicor/mission-recorder/releases
DefaultDirName={localappdata}\Programs\Mission Recorder
DefaultGroupName=Mission Recorder
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir={#OutputDir}
OutputBaseFilename=Mission Recorder-{#AppVersion}-setup
SetupIconFile=..\..\resources\icons\mission-recorder.ico
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
VersionInfoVersion={#AppVersion}
VersionInfoProductVersion={#AppVersion}
VersionInfoDescription=Mission Recorder installer
VersionInfoCompany={#AppPublisher}
VersionInfoProductName={#AppName}

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Mission Recorder"; Filename: "{app}\{#AppExeName}"

[Run]
Filename: "{app}\{#AppExeName}"; Description: "Launch Mission Recorder"; Flags: nowait postinstall skipifsilent
