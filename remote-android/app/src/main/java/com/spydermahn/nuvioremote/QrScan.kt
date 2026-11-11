package com.spydermahn.nuvioremote

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Scans the QR code in Nuvio's Settings › Phone remote with Google's code scanner (Play services): it brings its own
 * camera screen, so the app needs no camera permission. [onResult] gets the pairing link, or a reason it could not.
 */
object QrScan {
    fun start(context: Context, onLink: (PairingLink) -> Unit, onProblem: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        runCatching {
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    val link = PairingLink.parse(barcode.rawValue)
                    if (link != null) onLink(link) else onProblem("That isn't Nuvio's pairing code. It's in Nuvio under Settings › Keyboard shortcuts › Phone remote.")
                }
                .addOnFailureListener { error ->
                    onProblem("The scanner didn't start (${error.message ?: "no reason given"}). Enter the code by hand instead.")
                }
        }.onFailure { error ->
            onProblem("The scanner isn't available on this phone (${error.message ?: "no reason given"}). Enter the code by hand instead.")
        }
        // A cancelled scan reports neither: nothing to say.
    }
}
