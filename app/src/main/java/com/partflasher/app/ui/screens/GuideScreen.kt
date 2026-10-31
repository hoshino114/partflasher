package com.partflasher.app.ui.screens

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.partflasher.app.BuildConfig
import com.partflasher.app.R
import com.partflasher.app.ui.components.IconBubble
import com.partflasher.app.ui.components.LabelValueRow
import com.partflasher.app.ui.components.MessageBlock
import com.partflasher.app.ui.components.SectionCard
import com.partflasher.app.ui.components.SectionHeader
import com.partflasher.app.ui.theme.statusColors

/**
 * "指南" destination: safety rules, recommended workflow, root manager notes,
 * supported formats, troubleshooting and legal disclaimer.
 */
@Composable
fun GuideScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appInfo = remember(context) { context.applicationInfo }
    val versionName = BuildConfig.VERSION_NAME
    val supportedAbis = remember { Build.SUPPORTED_ABIS.joinToString(", ") }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "guide_header") {
                SectionHeader(
                    title = stringResource(R.string.guide_title),
                    subtitle = stringResource(R.string.guide_about_brand),
                )
            }

            item(key = "guide_safety") {
                SectionCard(
                    title = stringResource(R.string.guide_safety_title),
                    icon = Icons.Filled.Shield,
                    iconContentDescription = stringResource(R.string.safety_banner_icon_desc),
                    iconTint = MaterialTheme.colorScheme.onErrorContainer,
                    iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                ) {
                    listOf(
                        R.string.guide_safety_1,
                        R.string.guide_safety_2,
                        R.string.guide_safety_3,
                        R.string.guide_safety_4,
                        R.string.guide_safety_5,
                    ).forEachIndexed { index, textRes ->
                        BulletRow(index = index + 1, text = stringResource(textRes))
                    }
                }
            }

            item(key = "guide_workflow") {
                SectionCard(
                    title = stringResource(R.string.guide_workflow_title),
                    icon = Icons.Filled.CheckCircle,
                    iconTint = MaterialTheme.statusColors.onSuccessContainer,
                    iconContainerColor = MaterialTheme.statusColors.successContainer,
                ) {
                    listOf(
                        R.string.guide_workflow_1,
                        R.string.guide_workflow_2,
                        R.string.guide_workflow_3,
                        R.string.guide_workflow_4,
                        R.string.guide_workflow_5,
                        R.string.guide_workflow_6,
                    ).forEachIndexed { index, textRes ->
                        BulletRow(index = index + 1, text = stringResource(textRes))
                    }
                }
            }

            item(key = "guide_root") {
                SectionCard(
                    title = stringResource(R.string.guide_root_title),
                    icon = Icons.Filled.Security,
                ) {
                    RootManagerEntry(
                        title = stringResource(R.string.guide_root_magisk_title),
                        body = stringResource(R.string.guide_root_magisk_body),
                    )
                    RootManagerEntry(
                        title = stringResource(R.string.guide_root_kernelsu_title),
                        body = stringResource(R.string.guide_root_kernelsu_body),
                    )
                    RootManagerEntry(
                        title = stringResource(R.string.guide_root_apatch_title),
                        body = stringResource(R.string.guide_root_apatch_body),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RootManagerEntry(
                        title = stringResource(R.string.guide_root_common_title),
                        body = stringResource(R.string.guide_root_common_body),
                    )
                }
            }

            item(key = "guide_formats") {
                SectionCard(
                    title = stringResource(R.string.guide_formats_title),
                    icon = Icons.Filled.Info,
                ) {
                    MessageBlock(
                        text = stringResource(R.string.guide_formats_supported_body),
                        title = stringResource(R.string.guide_formats_supported_title),
                        icon = Icons.Filled.CheckCircle,
                        containerColor = MaterialTheme.statusColors.successContainer,
                        contentColor = MaterialTheme.statusColors.onSuccessContainer,
                    )
                    MessageBlock(
                        text = stringResource(R.string.guide_formats_unsupported_body),
                        title = stringResource(R.string.guide_formats_unsupported_title),
                        icon = Icons.Filled.ErrorOutline,
                    )
                    Text(
                        text = stringResource(R.string.guide_format_limit_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "guide_faq") {
                SectionCard(
                    title = stringResource(R.string.guide_faq_title),
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                ) {
                    FaqEntry(
                        question = stringResource(R.string.guide_faq_denied_q),
                        answer = stringResource(R.string.guide_faq_denied_a),
                    )
                    FaqEntry(
                        question = stringResource(R.string.guide_faq_nopartition_q),
                        answer = stringResource(R.string.guide_faq_nopartition_a),
                    )
                    FaqEntry(
                        question = stringResource(R.string.guide_faq_verify_q),
                        answer = stringResource(R.string.guide_faq_verify_a),
                    )
                    FaqEntry(
                        question = stringResource(R.string.guide_faq_brick_q),
                        answer = stringResource(R.string.guide_faq_brick_a),
                    )
                    FaqEntry(
                        question = stringResource(R.string.guide_faq_data_q),
                        answer = stringResource(R.string.guide_faq_data_a),
                    )
                }
            }

            item(key = "guide_about") {
                SectionCard(
                    title = stringResource(R.string.guide_about_title),
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                ) {
                    LabelValueRow(
                        label = stringResource(R.string.guide_about_version),
                        value = versionName,
                    )
                    LabelValueRow(
                        label = stringResource(R.string.guide_about_build),
                        value = BuildConfig.VERSION_CODE.toString(),
                    )
                    LabelValueRow(
                        label = stringResource(R.string.guide_about_target),
                        value = appInfo.targetSdkVersion.toString(),
                    )
                    LabelValueRow(
                        label = stringResource(R.string.guide_about_abi),
                        value = supportedAbis,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = stringResource(R.string.guide_about_brand),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.guide_about_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BulletRow(index: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RootManagerEntry(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FaqEntry(question: String, answer: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.action_collapse else R.string.common_details,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = answer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, bottom = 6.dp),
            )
        }
    }
}
