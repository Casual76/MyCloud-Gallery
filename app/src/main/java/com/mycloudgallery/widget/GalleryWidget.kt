package com.mycloudgallery.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mycloudgallery.MainActivity

/**
 * Widget homescreen 4×1.
 * Mostra:
 * - Ultima sincronizzazione
 * - N foto in attesa di upload
 * - Pulsante sync manuale
 *
 * Aggiornato ogni 15 minuti via [GalleryWidgetReceiver] e dopo ogni sync completata.
 */
class GalleryWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Legge lo stato serializzato da DataStore Glance
        val state = GalleryWidgetStateRepository.getState(context)

        provideContent {
            GlanceTheme {
                WidgetContent(state = state)
            }
        }
    }

    @Composable
    private fun WidgetContent(state: WidgetState) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(GlanceTheme.colors.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Testo informativo
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "MyCloud Gallery",
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurface,
                    ),
                )
                Text(
                    text = state.lastSyncText,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                )
                if (state.pendingUploads > 0) {
                    Text(
                        text = "${state.pendingUploads} foto in attesa",
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = GlanceTheme.colors.primary,
                        ),
                    )
                }
            }

            Spacer(modifier = GlanceModifier.width(8.dp))

            // Pulsante sync — lancia un broadcast che avvia WorkManager
            Text(
                text = "↻",
                style = TextStyle(
                    fontSize = 22.sp,
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = GlanceModifier.clickable(
                    actionStartActivity<MainActivity>()
                ),
            )
        }
    }
}

/** Stato serializzato del widget, letto da DataStore. */
data class WidgetState(
    val lastSyncText: String = "Sincronizzazione in corso…",
    val pendingUploads: Int = 0,
    val isOffline: Boolean = false,
)
