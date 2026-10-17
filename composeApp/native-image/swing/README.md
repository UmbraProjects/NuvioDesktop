# swing

`javax.swing.*`, `com.sun.java.swing.*`, `sun.swing.*` types, plus the Swing / AWT resource bundles
(module-qualified: `com.sun.swing.internal.plaf.basic/metal.resources.*`,
`com.sun.java.swing.plaf.windows.resources.windows`, `sun.awt.resources.awt`).

Why:
- JDK 25 looks Swing bundles up by module, so the JDK 21 trace's plain bundle names do not match.
- Compose's error dialog is Swing. `Basic*UI.loadActionMap` is a non-public static method, so these
  need `allDeclaredMethods`, not `allPublicMethods`; otherwise a failure that should show a dialog
  fails again inside the dialog (seen with the paste freeze, see `clipboard/`).
- `javax.swing.Popup*` is used by the tray menu. `WindowsPopupWindow` is also JNI-accessible: AWT's
  native code looks it up for heavyweight popups (QA build, 2026-10-17).
