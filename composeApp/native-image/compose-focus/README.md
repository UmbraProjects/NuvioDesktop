# compose-focus

`core/ui/FocusedControl.desktop.kt` reads Compose's focused node through
`FocusOwnerImpl.getActiveFocusTargetNode()` (public in the bytecode, internal to Kotlin), so the
music player shortcut (`MusicKeyboard.togglePlayerFocus`) can put focus back on exactly the control
it left. Without this entry the native exe's lookup finds nothing and the shortcut falls back to
the page's first control. `FocusedControlTest` fails if a Compose upgrade renames the member.
