package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.index.contract.QualityLevel
import dev.pampa.mycloudgallery.core.index.contract.ReplicaQualityState
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewEstimate
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewLevel
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualitySettings
import dev.pampa.mycloudgallery.feature.settings.quality.PreviewQualityState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * "Anteprime del NAS" nelle Impostazioni (Fase 4A, P11): la qualita' della replica (`ReplicaQualityController` di core-index,
 * D16, E-L6) e "Mostra le copertine degli album" (D11, E-N14) dietro la porta di feature-settings. Null finche' il NAS
 * nella Libreria non e' partito.
 */
class NasPreviewQuality(private val nas: NasGraph, scope: CoroutineScope) : PreviewQualitySettings {

  override val state: StateFlow<PreviewQualityState?> =
    combine(nas.quality, flow { emitAll(nas.albumCovers) }.flowOn(Dispatchers.IO)) { quality, covers -> quality?.let { toSettings(it, covers) } }
      .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

  override suspend fun choose(level: PreviewLevel) = nas.setQuality(toLevel(level))

  override suspend fun showAlbumCovers(show: Boolean) = withContext(Dispatchers.IO) { nas.showAlbumCovers = show }

  companion object {
    private const val STOP_TIMEOUT_MS = 5_000L

    fun toLevel(level: PreviewLevel): QualityLevel = when (level) {
      PreviewLevel.SAVER -> QualityLevel.SAVER
      PreviewLevel.STANDARD -> QualityLevel.STANDARD
      PreviewLevel.HIGH -> QualityLevel.HIGH
    }

    fun toPreview(level: QualityLevel): PreviewLevel = when (level) {
      QualityLevel.SAVER -> PreviewLevel.SAVER
      QualityLevel.STANDARD -> PreviewLevel.STANDARD
      QualityLevel.HIGH -> PreviewLevel.HIGH
    }

    fun toSettings(quality: ReplicaQualityState, covers: Boolean): PreviewQualityState = PreviewQualityState(
      level = toPreview(quality.level),
      estimates = quality.estimates.entries.associate { (level, e) -> toPreview(level) to PreviewEstimate(e.bytes, e.items) },
      currentBytes = quality.currentBytes,
      converging = quality.converging,
      showAlbumCovers = covers,
    )
  }
}

/**
 * Un interruttore che sopravvive al riavvio: un file vuoto in `noBackupFilesDir` (c'e' = acceso). Letto pigramente, al
 * primo uso: nessun I/O quando il grafo si costruisce. L'azzeramento lo cancella con gli altri file del NAS.
 */
class FileSwitch(private val file: File) {
  private val state: MutableStateFlow<Boolean> by lazy { MutableStateFlow(file.exists()) }

  val flow: StateFlow<Boolean> get() = state

  val on: Boolean get() = state.value

  fun set(on: Boolean) {
    val saved = try {
      if (on) {
        StateFiles.mark(file)
        true
      } else {
        StateFiles.delete(file)
      }
    } catch (e: IOException) {
      false
    }
    if (!saved) GalleryLog.w("FileSwitch") { "interruttore non salvato" }
    // Spento a parole ma il file c'e' ancora: allo stato diciamo la verita' (acceso), non quello che ci aspettavamo.
    state.value = on || !saved
  }
}
