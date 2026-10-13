package dev.pampa.mycloudgallery.core.places.dataset

import android.content.Context
import dev.pampa.mycloudgallery.core.places.contract.ReverseGeocoder
import dev.pampa.mycloudgallery.core.places.noop.NoReverseGeocoder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** L'esito dell'apertura del dataset dei luoghi (per la Diagnostica: solo enum, mai percorsi o dati). */
sealed interface PlacesOpenResult {

  /** Aperto e pronto. */
  class Opened(val data: PlacesData) : PlacesOpenResult

  /** Questa build non contiene un dataset reale (l'impronta in `PlacesDatasetPin` e' vuota): si usano i null-object. */
  data object NotBundled : PlacesOpenResult

  /** C'e' un dataset ma non e' utilizzabile. */
  class Failed(val reason: Reason) : PlacesOpenResult

  enum class Reason {
    /** L'asset non c'e' nell'APK. */
    ASSET_MISSING,

    /** L'asset non e' quello fissato nel codice (SHA-256 o dimensione diversi). */
    HASH_MISMATCH,

    /** Il disco non si e' lasciato leggere o scrivere. */
    IO_ERROR,

    /** Il file ha l'impronta giusta ma non e' un MCGP1 valido. */
    INVALID_FORMAT,
  }
}

/**
 * Il dataset dei luoghi offline (docs/fase3-progetto.md D8, §2.3; docs/luoghi.md).
 *
 * ```
 * val data = (PlacesDataset.openResult(context) as? PlacesOpenResult.Opened)?.data
 * val geocoder: ReverseGeocoder = data ?: NoReverseGeocoder
 * val zones: TimeZoneLookup = data ?: NoTimeZoneLookup
 * val names: PlaceNames = data ?: NoPlaceNames
 * ```
 *
 * L'asset e' `assets/places/places.mcgp1`; si estrae alla prima apertura in `noBackupFilesDir/places/` e si mappa in
 * memoria. Finche' l'asset reale non e' stato generato (docs/luoghi.md) tutto ritorna i null-object.
 */
object PlacesDataset {

  /** Il geocoder del dataset dell'app, o [NoReverseGeocoder] se non c'e' o non e' integro. Bloccante: non sul thread principale. */
  fun open(context: Context): ReverseGeocoder = (openResult(context) as? PlacesOpenResult.Opened)?.data ?: NoReverseGeocoder

  /** Come [open] ma con l'esito (per sapere perche' manca). Bloccante: non sul thread principale. */
  fun openResult(context: Context): PlacesOpenResult {
    val app = context.applicationContext ?: context
    return PlacesStore.open(
      pin = currentPin(),
      openAsset = {
        try {
          app.assets.open(PlacesDatasetPin.ASSET_PATH)
        } catch (_: IOException) {
          null
        }
      },
      dir = File(app.noBackupFilesDir, "places"),
    )
  }

  /** Apre un dataset MCGP1 dai [bytes] (test e strumenti): controlla struttura e CRC-32, non l'impronta fissata nel codice. */
  fun openForTest(bytes: ByteArray): PlacesData = PlacesData(Mcgp1.open(ByteBuffer.wrap(bytes), verifyChecksum = true))

  /** L'impronta fissata nel codice, o null se non c'e' un dataset reale. */
  internal fun currentPin(): PlacesPin? =
    if (PlacesDatasetPin.SHA256.isEmpty()) null else PlacesPin(PlacesDatasetPin.VERSION, PlacesDatasetPin.SHA256, PlacesDatasetPin.SIZE_BYTES)
}
