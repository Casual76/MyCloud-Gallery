package dev.pampa.mycloudgallery.core.nasmedia.scan

import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaLimits
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import java.text.Normalizer
import java.util.Locale

// Pezzi piccoli e puri della scansione (docs/fase4-progetto.md §2.3).

/**
 * Le misure della scansione. I valori di default sono quelli del progetto: pagine da 1.000 voci (E-N5), lotti da 500
 * (§2.3, "Lotto"), 4 letture in parallelo come i 4 flussi di `readAll` (l'inventario: 4 flussi rendono 2,2 volte uno solo),
 * profondita' massima 64 (E-N10), assenze a tetti da 500 (§2.3, "Fine della share").
 */
class ScanConfig(
  val pageSize: Int = NasMediaLimits.DEFAULT_PAGE_SIZE,
  val batchSize: Int = 500,
  val detectConcurrency: Int = 4,
  val maxDepth: Int = 64,
  val vanishCap: Int = 500,
  /** Il cursore si salva a ogni cartella con scritture e comunque ogni tante cartelle (le altre si rifanno: sono rapide). */
  val cursorSaveEvery: Int = 25,
) {
  init {
    require(pageSize > 0 && batchSize > 0 && detectConcurrency > 0 && maxDepth > 0 && vanishCap > 0 && cursorSaveEvery > 0) {
      "Misure della scansione non valide."
    }
  }
}

/** Il codice stabile (senza dati personali) con cui la Diagnostica e `NasScanStatus.Unavailable` nominano un errore del NAS. */
internal fun scanFailureCode(error: NasError): String = when (error) {
  is NasError.ShareMissing -> "SHARE_MISSING"
  NasError.PermissionDenied -> "ACCESS_DENIED"
  is NasError.Auth, is NasError.AuthBlocked, NasError.CredentialsMissing -> "AUTH"
  is NasError.NotAllowedHere, NasError.LocalNetworkBlocked -> "NOT_ALLOWED"
  is NasError.InsecureSession, NasError.IdentityMismatch -> "INSECURE_SESSION"
  NasError.Timeout, NasError.ServiceDown, NasError.SessionExpired, NasError.Stalled -> "NAS_UNREACHABLE"
  NasError.QuotaExceeded, NasError.NasFull, NasError.FileBusy, NasError.Exists, NasError.NotFound, is NasError.Unexpected -> "NAS_ERROR"
}

/** Il confronto dei nomi di SMB: NFC e minuscolo (E-N7). */
internal fun foldName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

/** I metadati di un record dell'indice adottato (D6): gia' orientati, quindi rotazione zero. */
internal fun metadataOf(file: MetaFile): MediaMetadata = MediaMetadata(
  mimeType = file.mime,
  width = file.width,
  height = file.height,
  orientationDegrees = 0,
  durationMillis = file.durationMs,
  captureTime = file.capture,
  location = file.geo?.let { GeoPoint(it.latE7 / 1e7, it.lonE7 / 1e7) },
  mirrored = false,
  traits = MediaTrait.fromBits(file.traits),
  thumbHash = file.thumbHash,
)
