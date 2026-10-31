package com.nuvio.app.core.ui

import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusTargetModifierNode

// The window's focus manager is Compose's focus owner, whose active node is public in the bytecode but internal to
// Kotlin. Read reflectively, once looked up; anything unexpected (a different owner, a renamed member) gives null and
// the caller falls back to the page's first control. Native image: registered in native-image/compose-focus/.
private val activeNodeGetter by lazy {
    runCatching {
        val focusOwner = Class.forName("androidx.compose.ui.focus.FocusOwnerImpl")
        focusOwner.getMethod("getActiveFocusTargetNode")
    }.getOrNull()
}

actual fun FocusManager.focusedControl(): FocusTargetModifierNode? = runCatching {
    val getter = activeNodeGetter ?: return null
    if (!getter.declaringClass.isInstance(this)) return null
    getter.invoke(this) as? FocusTargetModifierNode
}.getOrNull()
