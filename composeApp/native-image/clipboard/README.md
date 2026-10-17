# clipboard

`java.awt.datatransfer.*`, `sun.datatransfer.*`, `sun.awt.datatransfer.*`,
`java.rmi.MarshalledObject`, and the `java.datatransfer` module's `sun/datatransfer/resources/**`
(`flavormap.properties`).

Why: paste froze the app. `sun.datatransfer.DataFlavorUtil$RMI.<clinit>` reflectively looks up
`java.rmi.MarshalledObject(Object)`; without it the class fails to initialise with an
AssertionError, every clipboard read fails, and the error dialog then fails too (see `swing/`).
`NUVIO_NATIVE_SELFTEST=1` checks the clipboard at startup without reading its contents.
