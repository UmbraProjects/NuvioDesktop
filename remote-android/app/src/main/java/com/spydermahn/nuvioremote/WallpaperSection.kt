package com.spydermahn.nuvioremote

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Remote › More › Lock screen: the Poster wallpaper switch and the user's normal lock-screen picture (see
 * [PosterWallpaper]). Phone only; nothing is sent to the PC.
 */
@Composable
fun LockScreenSection() {
    val g = LocalGlass.current
    val context = LocalContext.current
    val store = remember { PcStore(context) }
    var on by remember { mutableStateOf(store.posterWallpaper) }
    var hasNormal by remember { mutableStateOf(PosterWallpaper.hasNormal(context)) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PosterWallpaper.chooseNormal(context, uri) { ok ->
            main.post {
                hasNormal = PosterWallpaper.hasNormal(context)
                Toast.makeText(
                    context,
                    if (ok) "Your lock screen picture is saved" else "That picture couldn't be opened",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    GlassLabel("Lock screen")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        GlassKey(Icons.Rounded.Wallpaper, if (on) "Poster on" else "Poster off", active = on) {
            on = !on
            store.posterWallpaper = on
            if (!on) PosterWallpaper.restore(context)
        }
        GlassKey(Icons.Rounded.AddPhotoAlternate, if (hasNormal) "Change" else "My picture") {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (hasNormal) {
            GlassKey(Icons.Rounded.HideImage, "Forget") {
                PosterWallpaper.forgetNormal(context)
                hasNormal = false
            }
        } else {
            Spacer(Modifier.size(60.dp))
        }
    }
    Text(
        if (hasNormal) {
            "While the TV plays, your lock screen shows the poster or album cover. Your picture goes back when it stops."
        } else {
            "While the TV plays, your lock screen shows the poster or album cover. When it stops, the lock screen goes " +
                "back to your home screen's wallpaper; choose My picture to use a different one."
        },
        color = g.textMuted,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
