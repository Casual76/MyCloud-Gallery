package com.mycloudgallery.presentation.viewer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mycloudgallery.domain.model.MediaItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExifBottomSheet(
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Informazioni",
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Data e ora
            ExifRow(
                icon = Icons.Default.CalendarToday,
                label = "Data",
                value = formatDate(mediaItem.createdAt),
            )

            // Dimensioni e file
            ExifRow(
                icon = Icons.Default.Image,
                label = "File",
                value = buildString {
                    append(mediaItem.fileName)
                    if (mediaItem.width != null && mediaItem.height != null) {
                        append(" \u2022 ${mediaItem.width}\u00D7${mediaItem.height}")
                    }
                    append(" \u2022 ${formatFileSize(mediaItem.fileSize)}")
                },
            )

            // Fotocamera
            mediaItem.exifCameraModel?.let { camera ->
                ExifRow(
                    icon = Icons.Default.Camera,
                    label = "Fotocamera",
                    value = buildString {
                        append(camera)
                        mediaItem.exifIso?.let { append(" \u2022 ISO $it") }
                        mediaItem.exifFocalLength?.let { append(" \u2022 ${it}mm") }
                    },
                )
            }

            // Posizione GPS
            if (mediaItem.hasGps) {
                ExifRow(
                    icon = Icons.Default.LocationOn,
                    label = "Posizione",
                    value = "%.4f, %.4f".format(mediaItem.exifLatitude, mediaItem.exifLongitude),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // MIME type
            Text(
                text = mediaItem.mimeType,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExifRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun formatDate(timestampMs: Long): String {
    val sdf = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestampMs))
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
