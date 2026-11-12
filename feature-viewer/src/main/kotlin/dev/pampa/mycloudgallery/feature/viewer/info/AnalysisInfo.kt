package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterNote
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryFooterTone
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysis
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysisHold
import dev.pampa.mycloudgallery.feature.viewer.menu.MediaAnalysisKind

// Pacchetto X (12/11/2026): lo stato dell'analisi del media aperto nel pannello informazioni, come la capsula del backup: un'icona e due
// parole ("Pronta per la ricerca", "Da analizzare", "Prima in coda"), il perche' solo quando una richiesta non parte subito. Si vede
// **prima** del tocco: gia' pronta, niente tasto inutile; in coda, la capsula e' il tasto che la fa passare davanti (e la stessa voce sta
// nel menu). Un caso del contratto ha sempre la sua capsula (`when` esaustivo); "non si sa ancora" non mostra niente.

/** La capsula dell'analisi per [media], o null se non c'e' niente da dire. [video] sceglie "pronta"/"pronto". */
internal fun analysisNote(format: ViewerFormat, media: MediaAnalysis, video: Boolean): GalleryFooterNote? {
  val res = format.resources
  fun note(tone: GalleryFooterTone, label: Int, glyph: ImageVector?, busy: Boolean = false): GalleryFooterNote {
    val text = res.getString(label)
    val detail = media.hold?.let { res.getString(holdRes(it)) }
    val spoken = listOfNotNull(res.getString(R.string.analysis_badge_spoken, text), detail).joinToString(". ")
    return GalleryFooterNote(tone = tone, label = text, glyph = glyph, detail = detail, busy = busy, description = spoken)
  }
  return when (media.kind) {
    MediaAnalysisKind.UNKNOWN -> null
    MediaAnalysisKind.READY -> note(
      GalleryFooterTone.Calm,
      if (video) R.string.analysis_badge_ready_video else R.string.analysis_badge_ready_photo,
      Icons.Rounded.CheckCircle,
    )
    MediaAnalysisKind.QUEUED -> note(GalleryFooterTone.Waiting, R.string.analysis_badge_queued, Icons.Rounded.Schedule)
    MediaAnalysisKind.FIRST -> note(
      GalleryFooterTone.Working,
      if (video) R.string.analysis_badge_first_video else R.string.analysis_badge_first_photo,
      Icons.Rounded.AutoAwesome,
    )
    MediaAnalysisKind.ANALYZING -> note(GalleryFooterTone.Working, R.string.analysis_badge_analyzing, Icons.Rounded.Timelapse, busy = true)
    MediaAnalysisKind.UNSUPPORTED -> note(GalleryFooterTone.Waiting, R.string.analysis_badge_unsupported, Icons.Rounded.BrokenImage)
    MediaAnalysisKind.MODEL_MISSING -> note(GalleryFooterTone.Attention, R.string.analysis_badge_model_missing, Icons.Rounded.Download)
    MediaAnalysisKind.NOT_HERE -> note(GalleryFooterTone.Waiting, R.string.analysis_badge_not_here, Icons.Rounded.PhoneAndroid)
  }
}

private fun holdRes(hold: MediaAnalysisHold): Int = when (hold) {
  MediaAnalysisHold.HOT -> R.string.analysis_hold_hot
  MediaAnalysisHold.NAS -> R.string.analysis_hold_nas
  MediaAnalysisHold.PREPARATION -> R.string.analysis_hold_preparation
  MediaAnalysisHold.RULES -> R.string.analysis_hold_rules
  MediaAnalysisHold.WIFI -> R.string.analysis_hold_wifi
}
