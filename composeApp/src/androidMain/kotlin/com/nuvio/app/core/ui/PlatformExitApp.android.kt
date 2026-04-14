package com.nuvio.app.core.ui

import android.os.Process
import kotlin.system.exitProcess

actual fun platformExitApp() {
    Process.killProcess(Process.myPid())
    exitProcess(0)
}
