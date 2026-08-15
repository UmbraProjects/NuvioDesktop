#Requires AutoHotkey v2.0
#SingleInstance Force

; Media-key test harness for NuvioDesktop.
;
; Synthesises the four dedicated multimedia virtual keys on a keyboard that has none.
; Nuvio answers them through the Windows System Media Transport Controls session created in
; player_bridge.cpp (initializeSystemMediaControls), so the keys work whether or not Nuvio has
; focus — that is the whole point of the feature and the main thing to test here.
;
; Expected behaviour, with a video playing:
;
;   Media_Play_Pause  -> resumes or pauses (SMTC sends discrete Play/Pause based on the
;                        PlaybackStatus the player last reported)
;   Media_Stop        -> pauses
;   Media_Next        -> next episode
;   Media_Prev        -> previous episode
;
; Hotkeys:
;
;   Ctrl+Alt+P  Play/Pause      Ctrl+Alt+W  Background test: focus Notepad (or the desktop),
;   Ctrl+Alt+S  Stop                        wait 1s, then send Play/Pause. This is the case
;   Ctrl+Alt+N  Next                        the old focus-only implementation could not do.
;   Ctrl+Alt+B  Previous        Ctrl+Alt+H  Show this list
;                               Ctrl+Alt+X  Exit
;
; Also worth checking by eye: press the volume keys (or Win+G's audio panel) while playing and
; confirm Nuvio appears in the media flyout with the show title and episode text. If it does,
; the session is registered and the keys will route to it.
;
; Run it:  double-click the file, or
;   & "C:\Program Files\AutoHotkey\v2\AutoHotkey64.exe" scripts\media-key-test.ahk

TargetExe := "Nuvio.exe"

Send1(key, label) {
    if WinExist("ahk_exe " TargetExe) {
        state := WinActive("ahk_exe " TargetExe) ? "focused" : "BACKGROUND"
        Flash("sent " label " — Nuvio is " state)
    } else {
        Flash("sent " label " — Nuvio is not running")
    }
    Send "{" key "}"
}

; The regression test for the original bug: Nuvio must react while another app owns focus.
BackgroundTest() {
    if !WinExist("ahk_exe " TargetExe) {
        MsgBox("Nuvio is not running.", "Nuvio media-key test")
        return
    }
    if WinExist("ahk_exe notepad.exe") {
        WinActivate "ahk_exe notepad.exe"
    } else {
        Run "notepad.exe"
        WinWait "ahk_exe notepad.exe", , 5
        WinActivate "ahk_exe notepad.exe"
    }
    Sleep 1000
    if WinActive("ahk_exe " TargetExe) {
        Flash("could not move focus off Nuvio — test inconclusive")
        return
    }
    Flash("Nuvio is in the background — sending Media_Play_Pause")
    Send "{Media_Play_Pause}"
}

Flash(text) {
    ToolTip text
    SetTimer () => ToolTip(), -2200
}

^!p:: Send1("Media_Play_Pause", "Media_Play_Pause")
^!s:: Send1("Media_Stop", "Media_Stop")
^!n:: Send1("Media_Next", "Media_Next")
^!b:: Send1("Media_Prev", "Media_Prev")
^!w:: BackgroundTest()

^!h:: MsgBox(
    "Ctrl+Alt+P`tPlay/Pause`n"
    "Ctrl+Alt+S`tStop`n"
    "Ctrl+Alt+N`tNext episode`n"
    "Ctrl+Alt+B`tPrevious episode`n`n"
    "Ctrl+Alt+W`tBackground test — focus Notepad, then send Play/Pause`n`n"
    "Ctrl+Alt+X`tExit", "Nuvio media-key test")

^!x:: ExitApp
