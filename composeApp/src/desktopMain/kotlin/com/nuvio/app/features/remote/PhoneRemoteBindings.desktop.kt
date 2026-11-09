package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.StateFlow

actual fun ensurePhoneRemoteLoaded() = PhoneRemote.ensureLoaded()

actual fun phoneRemoteState(): StateFlow<PhoneRemoteState> = PhoneRemote.state

actual fun setPhoneRemoteEnabled(enabled: Boolean) = PhoneRemote.setEnabled(enabled)

actual fun resetPhoneRemotePairing() = PhoneRemote.resetPairing()

actual fun phoneRemoteQrModules(text: String): List<BooleanArray>? = runCatching {
    val matrix = com.google.zxing.qrcode.encoder.Encoder.encode(
        text,
        com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
    ).matrix
    List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
}.getOrNull()
