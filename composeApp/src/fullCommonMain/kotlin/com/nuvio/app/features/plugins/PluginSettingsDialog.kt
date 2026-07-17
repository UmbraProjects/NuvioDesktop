package com.nuvio.app.features.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.action_save
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PluginSettingsDialog(
    scraperId: String,
    scraperName: String,
    layoutJson: String,
    onDismiss: () -> Unit,
) {
    val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }
    val layout = remember(layoutJson) {
        runCatching { json.parseToJsonElement(layoutJson).jsonArray }.getOrDefault(JsonArray(emptyList()))
    }
    val savedSettings = remember(scraperId) {
        val raw = PluginStorage.loadScraperSettings(scraperId) ?: "{}"
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }
    val currentSettings = remember(scraperId) {
        mutableStateMapOf<String, JsonElement>().apply { putAll(savedSettings) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .width(560.dp)
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "$scraperName settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                layout.forEach { element ->
                    val field = element as? JsonObject ?: return@forEach
                    val type = field["type"]?.jsonPrimitive?.contentOrNull ?: "info"
                    val key = field["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val label = field["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val description = field["description"]?.jsonPrimitive?.contentOrNull

                    when (type) {
                        "header" -> Text(label, style = MaterialTheme.typography.titleMedium)
                        "info" -> Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        "text" -> PluginTextSetting(field, key, label, description, currentSettings)
                        "select" -> PluginSelectSetting(field, key, label, description, currentSettings)
                        "toggle" -> PluginToggleSetting(field, key, label, description, currentSettings)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                    NuvioPrimaryButton(
                        text = stringResource(Res.string.action_save),
                        modifier = Modifier.width(112.dp),
                        onClick = {
                            PluginStorage.saveScraperSettings(scraperId, JsonObject(currentSettings.toMap()).toString())
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginTextSetting(
    field: JsonObject,
    key: String,
    label: String,
    description: String?,
    settings: MutableMap<String, JsonElement>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        NuvioInputField(
            value = settings[key]?.jsonPrimitive?.contentOrNull.orEmpty(),
            onValueChange = { settings[key] = JsonPrimitive(it) },
            placeholder = field["placeholder"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        PluginSettingDescription(description)
    }
}

@Composable
private fun PluginSelectSetting(
    field: JsonObject,
    key: String,
    label: String,
    description: String?,
    settings: MutableMap<String, JsonElement>,
) {
    val options = field["options"] as? JsonArray ?: JsonArray(emptyList())
    val defaultValue = field["defaultValue"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val currentValue = settings[key]?.jsonPrimitive?.contentOrNull ?: defaultValue
    var expanded by remember(key) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val selectedLabel = options.firstNotNullOfOrNull { option ->
                    val objectValue = option as? JsonObject ?: return@firstNotNullOfOrNull null
                    if (objectValue["value"]?.jsonPrimitive?.contentOrNull == currentValue) {
                        objectValue["label"]?.jsonPrimitive?.contentOrNull
                    } else {
                        null
                    }
                } ?: currentValue.ifBlank { "Select option" }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(selectedLabel)
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    val objectValue = option as? JsonObject ?: return@forEach
                    val optionValue = objectValue["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val optionLabel = objectValue["label"]?.jsonPrimitive?.contentOrNull ?: optionValue
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            settings[key] = JsonPrimitive(optionValue)
                            expanded = false
                        },
                    )
                }
            }
        }
        PluginSettingDescription(description)
    }
}

@Composable
private fun PluginToggleSetting(
    field: JsonObject,
    key: String,
    label: String,
    description: String?,
    settings: MutableMap<String, JsonElement>,
) {
    val defaultValue = field["defaultValue"]?.jsonPrimitive?.booleanOrNull ?: false
    val value = settings[key]?.jsonPrimitive?.booleanOrNull ?: defaultValue
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            PluginSettingDescription(description)
        }
        Switch(checked = value, onCheckedChange = { settings[key] = JsonPrimitive(it) })
    }
}

@Composable
private fun PluginSettingDescription(description: String?) {
    if (!description.isNullOrBlank()) {
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
