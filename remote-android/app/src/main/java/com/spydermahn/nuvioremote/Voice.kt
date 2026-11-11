package com.spydermahn.nuvioremote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Voice search: the phone's own speech input (Google's, the same as the keyboard's mic), so the app needs no microphone
 * permission. Returns a function that starts listening; [onText] gets what was said, [onProblem] why nothing came.
 */
@Composable
fun rememberVoiceInput(prompt: String, onText: (String) -> Unit, onProblem: (String) -> Unit = {}): () -> Unit {
    val heard by rememberUpdatedState(onText)
    val problem by rememberUpdatedState(onProblem)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) heard(text)
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            problem("This phone has no speech input. Install or turn on Google's voice typing.")
        }
    }
}
