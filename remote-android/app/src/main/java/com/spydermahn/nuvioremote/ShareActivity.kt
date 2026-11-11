package com.spydermahn.nuvioremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.IOException

/**
 * "Send to Nuvio" in the phone's share menu. A YouTube or YouTube Music link, a watch-party invite or a direct stream
 * goes to the PC the remote last used and plays there. No screen of its own: it says what happened in a toast and
 * closes, leaving you in the app you shared from.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = sharedText(intent)
        val pc = PcStore(this).last()
        when {
            text == null -> done("There's no link in that to send to Nuvio.")
            pc == null -> {
                done("Open Nuvio Remote and connect to your PC first.")
                startActivity(Intent(this, MainActivity::class.java))
            }
            else -> Thread({ send(pc, text) }, "nuvio-remote-share").start()
        }
    }

    private fun send(pc: SavedPc, text: String) {
        val message = try {
            RemoteSession.open(pc.host, pc.port).use { session ->
                when (val hello = session.hello(pc.token, phoneName(this))) {
                    is HelloResult.Welcome -> when (val reply = session.open(text)) {
                        is ServerLine.Opening -> "Opening ${reply.what} on ${hello.pcName}"
                        ServerLine.CantOpen -> "Nuvio can't play that link."
                        // A PC from before Send to Nuvio ignores the request.
                        else -> "Update Nuvio on ${pc.name} to send links to it."
                    }
                    HelloResult.PinRequired -> "${pc.name} wants pairing again. Open Nuvio Remote to pair."
                    is HelloResult.Refused -> hello.reason
                }
            }
        } catch (_: IOException) {
            "Couldn't reach ${pc.name}. Is it on, with Nuvio open?"
        }
        runOnUiThread { done(message) }
    }

    private fun done(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        ).joinToString(" ")
        return text.takeIf { "://" in it }
    }
}
