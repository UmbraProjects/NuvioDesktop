package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.features.remote.ensurePhoneRemoteLoaded
import com.nuvio.app.features.remote.phoneRemoteState
import com.nuvio.app.features.remote.resetPhoneRemotePairing
import com.nuvio.app.features.remote.setPhoneRemoteEnabled

/**
 * fork:phone-remote — the switch, the pairing code and who is connected. Literal strings, like the
 * fork's other recent settings: newly added resource keys do not always resolve (see build notes).
 */
@Composable
internal fun PhoneRemoteSection(isTablet: Boolean) {
    LaunchedEffect(Unit) { ensurePhoneRemoteLoaded() }
    val state by phoneRemoteState().collectAsState()

    SettingsSection(title = "Phone remote", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = "Allow the Nuvio Remote app",
                description = when {
                    !state.enabled ->
                        "Drive Nuvio from an Android phone on the same Wi-Fi: arrows, OK, back, play and typing into search."
                    !state.listening ->
                        "Could not open a network port for the remote. Another program may be using ports 47321 to 47330."
                    state.connectedDevices.isNotEmpty() ->
                        "Connected: ${state.connectedDevices.joinToString(", ")}"
                    else ->
                        "Waiting for a phone. Windows may ask once whether to allow Nuvio on private networks: allow it."
                },
                checked = state.enabled,
                isTablet = isTablet,
                onCheckedChange = { setPhoneRemoteEnabled(it) },
            )
            if (state.enabled) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsActionRow(
                    title = "Pairing code  ${state.pin.chunked(3).joinToString(" ")}",
                    description = buildString {
                        append("Enter this in the app the first time a phone connects.")
                        if (state.addresses.isNotEmpty() && state.port != null) {
                            append(" If the app cannot find this PC, add it by hand: ")
                            append(state.addresses.joinToString(" or ") { "$it:${state.port}" })
                            append(".")
                        }
                        if (state.pairedDevices.isNotEmpty()) {
                            append(" Paired: ${state.pairedDevices.joinToString(", ")}.")
                        }
                    },
                    isTablet = isTablet,
                ) {
                    SettingsGlassButton(
                        text = "New code",
                        onClick = { resetPhoneRemotePairing() },
                    )
                }
            }
        }
    }
}
