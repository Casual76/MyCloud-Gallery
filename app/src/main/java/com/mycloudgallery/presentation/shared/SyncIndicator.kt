package com.mycloudgallery.presentation.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mycloudgallery.domain.model.NetworkMode

@Composable
fun SyncIndicator(
    networkMode: NetworkMode,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint, label) = when (networkMode) {
            NetworkMode.LOCAL -> Triple(
                Icons.Default.Cloud,
                MaterialTheme.colorScheme.primary,
                "Locale",
            )
            NetworkMode.RELAY -> Triple(
                Icons.Default.CloudSync,
                MaterialTheme.colorScheme.tertiary,
                "Relay",
            )
            NetworkMode.OFFLINE -> Triple(
                Icons.Default.CloudOff,
                MaterialTheme.colorScheme.error,
                "Offline",
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
