package com.partflasher.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.partflasher.app.R
import com.partflasher.app.core.model.DeviceInfo
import com.partflasher.app.ui.util.activeSlotLabel
import com.partflasher.app.ui.util.Formats

/**
 * Device identity, Android version, ABI (arm64 highlighted), active slot and
 * dynamic-partition support. Values that the platform cannot report are shown
 * as a localized "unknown" instead of being silently omitted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceInfoCard(
    deviceInfo: DeviceInfo?,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.device_card_title),
        modifier = modifier,
        icon = Icons.Filled.PhoneAndroid,
        iconContentDescription = stringResource(R.string.device_icon_desc),
    ) {
        if (deviceInfo == null && loading) {
            InlineLoading(text = stringResource(R.string.device_loading))
            return@SectionCard
        }
        if (deviceInfo == null) {
            MessageBlock(
                text = error ?: stringResource(R.string.device_unknown_value),
                icon = Icons.Filled.ErrorOutline,
                iconContentDescription = stringResource(R.string.status_icon_error),
            )
            return@SectionCard
        }

        val unknown = stringResource(R.string.device_unknown_value)
        LabelValueRow(
            label = stringResource(R.string.device_model_label),
            value = deviceInfo.model.ifBlank { unknown },
        )
        LabelValueRow(
            label = stringResource(R.string.device_manufacturer_label),
            value = deviceInfo.manufacturer.ifBlank { unknown },
        )
        LabelValueRow(
            label = stringResource(R.string.device_android_label),
            value = stringResource(
                R.string.device_android_value,
                deviceInfo.androidRelease.ifBlank { unknown },
                deviceInfo.sdkInt,
            ),
        )
        LabelValueRow(
            label = stringResource(R.string.device_slot_label),
            value = activeSlotLabel(deviceInfo.activeSlot),
        )
        LabelValueRow(
            label = stringResource(R.string.device_dynamic_label),
            value = stringResource(
                if (deviceInfo.dynamicPartitions) {
                    R.string.device_dynamic_supported
                } else {
                    R.string.device_dynamic_unsupported
                },
            ),
        )

        Spacer(Modifier.size(2.dp))
        Text(
            text = stringResource(R.string.device_abi_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val abis = deviceInfo.supportedAbis.ifEmpty { listOf(unknown) }
            abis.forEach { abi ->
                val isArm64 = abi == "arm64-v8a"
                StatusBadge(
                    text = abi,
                    containerColor = if (isArm64) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (isArm64) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        val securityPatch = deviceInfo.securityPatch?.takeIf { it.isNotBlank() }
        val kernelVersion = deviceInfo.kernelVersion?.takeIf { it.isNotBlank() }
        if (securityPatch != null || kernelVersion != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (securityPatch != null) {
                LabelValueRow(
                    label = stringResource(R.string.device_security_patch_label),
                    value = securityPatch,
                )
            }
            if (kernelVersion != null) {
                LabelValueRow(
                    label = stringResource(R.string.device_kernel_label),
                    value = kernelVersion,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.device_emulator_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(
                text = stringResource(
                    if (deviceInfo.isEmulator) R.string.device_emulator_value else R.string.device_real_value,
                ),
                containerColor = if (deviceInfo.isEmulator) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (deviceInfo.isEmulator) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }
        if (deviceInfo.isEmulator) {
            Text(
                text = stringResource(R.string.device_emulator_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!error.isNullOrBlank()) {
            MessageBlock(
                text = error,
                icon = Icons.Filled.ErrorOutline,
                iconContentDescription = stringResource(R.string.status_icon_error),
            )
        }
    }
}
