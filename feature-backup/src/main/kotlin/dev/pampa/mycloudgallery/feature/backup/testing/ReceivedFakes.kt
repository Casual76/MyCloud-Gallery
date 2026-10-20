package dev.pampa.mycloudgallery.feature.backup.testing

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedDiscard
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedId
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedItem
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedQueue
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedRetry
import dev.pampa.mycloudgallery.feature.backup.received.ReceivedStatus
import kotlinx.coroutines.flow.MutableStateFlow

// Correzioni 5B (IN-7): il finto di "Ricevuti in coda" per i test delle schermate e gli screenshot (package `testing`: mai nella release,
// `tools/check.ps1` lo cerca nel dex). Gli stati e gli esiti li sceglie il test; niente NAS, niente file.

/** La coda dei ricevuti decisa dal test: registra i tocchi e risponde con gli esiti scelti. */
class FakeReceivedQueue(items: List<ReceivedItem> = emptyList()) : ReceivedQueue {

  override val items: MutableStateFlow<List<ReceivedItem>> = MutableStateFlow(items)

  /** I file su cui l'utente ha toccato "Riprova" / "Scarta", nell'ordine. */
  val retried: MutableList<ReceivedId> = ArrayList()
  val discarded: MutableList<ReceivedId> = ArrayList()

  /** Gli esiti che la porta restituisce al prossimo tocco. */
  var retryResult: ReceivedRetry = ReceivedRetry.Queued
  var discardResult: ReceivedDiscard = ReceivedDiscard.Discarded

  /** La miniatura che la porta restituisce (null = nessuna: il segnaposto). */
  var thumbnailImage: ImageBitmap? = null

  override suspend fun thumbnail(id: ReceivedId, sidePx: Int): ImageBitmap? = thumbnailImage

  override suspend fun retry(id: ReceivedId): ReceivedRetry {
    retried += id
    return retryResult
  }

  override suspend fun discard(id: ReceivedId): ReceivedDiscard {
    discarded += id
    val outcome = discardResult
    if (outcome == ReceivedDiscard.Discarded) items.value = items.value.filterNot { it.id == id }
    return outcome
  }
}

/** I file ricevuti di esempio: uno per ogni stato, con cartelle e pesi realistici, per i test e gli screenshot. */
object ReceivedSamples {

  fun item(n: Int, status: ReceivedStatus, folder: String = "Viaggi/Estate", video: Boolean = false, size: Long = 3_200_000L): ReceivedItem =
    ReceivedItem(id = ReceivedId(n.toLong()), status = status, folder = folder, isVideo = video, size = size)

  /** Un file per ogni stato, nell'ordine dell'enumerazione. */
  val everyStatus: List<ReceivedItem> = ReceivedStatus.entries.mapIndexed { index, status ->
    item(
      n = index + 1,
      status = status,
      folder = if (index % 3 == 0) "Ricevute" else "Viaggi/Estate 2026",
      video = index % 4 == 1,
      size = if (index % 4 == 1) 612_000_000L else 3_200_000L,
    )
  }

  /** Una coda breve e verosimile: una foto in attesa del Wi-Fi, un video che sale, una foto con un problema. */
  val few: List<ReceivedItem> = listOf(
    item(1, ReceivedStatus.COPY_CHANGED, folder = "Ricevute"),
    item(2, ReceivedStatus.UPLOADING, folder = "Viaggi/Estate 2026", video = true, size = 612_000_000L),
    item(3, ReceivedStatus.NETWORK, folder = "Ricevute"),
  )
}
