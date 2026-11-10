package dev.pampa.mycloudgallery.core.library.membership

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.FilterExtras
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.similar.Fx
import dev.pampa.mycloudgallery.core.library.similar.SimilarCatalogTestBase
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * G2b: le correzioni di Fotocamera e Messaggistica lette dal catalogo vero (Room in memoria). Fotocamera: il telefono e il backup di questo
 * dispositivo si', gli altri dispositivi e le cartelle `DCIM/Camera` copiate a mano sul NAS no. Messaggistica: Telegram e Signal dalle cartelle,
 * sul telefono e nel backup; WhatsApp lo dice gia' la cella.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogFilterMembershipTest : SimilarCatalogTestBase() {

  private var msId = 9_000L
  private var n = 90_000

  private fun fingerprint(): Fingerprint = Fingerprint.ofContent(Random(++n).nextBytes(1_200))

  private suspend fun phone(folder: String, source: MediaSource, kind: MediaKind = MediaKind.PHOTO): MediaId {
    val fp = fingerprint()
    val location = Location.Phone(Fx.VOLUME, folder, "F_$n.jpg", ++msId, Fx.STORE)
    return catalog.record(Observation(location, FileStamp(fp.size, Fx.T0, 7), fp, kind, source, Fx.SECOND0)).mediaId!!
  }

  private suspend fun nas(path: String, source: MediaSource, share: String = "alessio"): MediaId {
    val fp = fingerprint()
    val observation = Observation(Location.Nas(Fx.ME, share, NasPath.of(path)), FileStamp(fp.size, Fx.T0), fp, MediaKind.PHOTO, source)
    return catalog.recordAll(listOf(observation), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = Fx.T0).single().mediaId!!
  }

  private fun membership(folder: String? = "S25") =
    CatalogFilterMembership(db, MutableStateFlow<TimelineSnapshot?>(SyntheticLibrary(count = 1).snapshot), flowOf(folder), io = Dispatchers.Unconfined, settleMs = 0)

  @Test
  fun fotocameraTieneIlTelefonoEIlBackupDiQuestoDispositivo() = runBlocking<Unit> {
    val onPhone = phone("DCIM/Camera/", MediaSource.CAMERA)
    // Dopo "Libera spazio" il media resta `camera` e ha solo la copia nel backup di questo dispositivo.
    val freed = nas("Backup/S25/2025/05/IMG_1.jpg", MediaSource.CAMERA)
    // Una cartella DCIM/Camera copiata a mano sul NAS, e la fotocamera di un altro mio dispositivo.
    val copiedByHand = nas("DCIM/Camera/IMG_2.jpg", MediaSource.CAMERA)
    val myTablet = nas("Backup/Tablet/2025/05/IMG_3.jpg", MediaSource.CAMERA)
    // Il backup di un altro dispositivo, come lo scrive davvero il backup (anno e mese): per il catalogo non e' `camera`.
    val otherBackup = nas("Backup/Pixel/2025/05/IMG_4.jpg", MediaSource.OTHER)
    val screenshot = phone("Pictures/Screenshots/", MediaSource.SCREENSHOT)

    val minus = membership().cameraNotFromHere(CatalogFilterMembership.originLabel("S25"))
    assertThat(minus).containsExactly(copiedByHand, myTablet)
    assertThat(minus).containsNoneOf(onPhone, freed, otherBackup, screenshot)
  }

  @Test
  fun senzaLaCartellaDiQuestoDispositivoUnMediaSoloSulNasNonSiAttribuisce() = runBlocking<Unit> {
    val onPhone = phone("DCIM/Camera/", MediaSource.CAMERA)
    val freed = nas("Backup/S25/2025/05/IMG_1.jpg", MediaSource.CAMERA)
    val minus = membership(folder = null).cameraNotFromHere(null)
    assertThat(minus).containsExactly(freed)
    assertThat(minus).doesNotContain(onPhone)
  }

  @Test
  fun messaggisticaTrovaTelegramESignalSulTelefonoENelBackup() = runBlocking<Unit> {
    val whatsapp = phone("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", MediaSource.WHATSAPP)
    val telegram = phone("Pictures/Telegram/", MediaSource.OTHER)
    val telegramOld = phone("Telegram/Telegram Video/", MediaSource.OTHER, MediaKind.VIDEO)
    val telegramScoped = phone("Android/media/org.telegram.messenger/Telegram/Telegram Images/", MediaSource.OTHER)
    val signal = phone("Pictures/Signal/", MediaSource.OTHER)
    val backedUp = nas("Backup/S25/Telegram/2025/05/IMG_5.jpg", MediaSource.OTHER)
    val camera = phone("DCIM/Camera/", MediaSource.CAMERA)
    val lookalike = phone("Pictures/Signals/", MediaSource.OTHER)
    val sticker = phone("Telegram/Telegram Stickers/", MediaSource.OTHER)
    val userFolder = nas("Foto/Telegram/IMG_6.jpg", MediaSource.OTHER)

    val plus = membership().messagingMedia()
    assertThat(plus).containsExactly(telegram, telegramOld, telegramScoped, signal, backedUp)
    assertThat(plus).containsNoneOf(whatsapp, camera, lookalike, sticker, userFolder)
  }

  @Test
  fun iFlussiDannoLeCorrezioniGiusteEGliAltriFiltriNessuna() = runBlocking<Unit> {
    val telegram = phone("Pictures/Telegram/", MediaSource.OTHER)
    val copiedByHand = nas("DCIM/Camera/IMG_2.jpg", MediaSource.CAMERA)
    val m = membership()
    assertThat(m.extras(LibraryFilter.MESSAGING).first()).isEqualTo(FilterExtras(plus = setOf(telegram)))
    assertThat(m.extras(LibraryFilter.CAMERA).first()).isEqualTo(FilterExtras(minus = setOf(copiedByHand)))
    assertThat(m.extras(LibraryFilter.VIDEOS).first()).isEqualTo(FilterExtras.NONE)
  }

  /**
   * FAB-11: la prima correzione arriva **subito** (il commento e i documenti dicevano cosi'; il ritardo cadeva prima della prima emissione) e poi al piu' una ogni
   * `settleMs`. Tempo virtuale: a 0 ms c'e' la prima, a 2.499 ms ancora quella sola, a 2.501 ms la seconda.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun laPrimaCorrezioneArrivaSubitoELeAltreAlPiuUnaOgniSettleMs() = runTest {
    val base = SyntheticLibrary(count = 1).snapshot
    fun snapshotWith(number: Long): TimelineSnapshot = object : TimelineSnapshot by base {
      override val seq: Long = number
    }
    val snapshots = MutableStateFlow<TimelineSnapshot?>(snapshotWith(1))
    val membership = CatalogFilterMembership(db, snapshots, flowOf("S25"), io = UnconfinedTestDispatcher(testScheduler), settleMs = 2_500)
    val seen = ArrayList<FilterExtras>()
    backgroundScope.launch { membership.extras(LibraryFilter.MESSAGING).collect { seen += it } }

    runCurrent()
    assertThat(currentTime).isEqualTo(0L)
    assertThat(seen).hasSize(1)

    // L'indice cambia subito dopo: la seconda lettura aspetta.
    snapshots.value = snapshotWith(2)
    runCurrent()
    assertThat(seen).hasSize(1)
    advanceTimeBy(2_499)
    runCurrent()
    assertThat(seen).hasSize(1)
    advanceTimeBy(2)
    runCurrent()
    assertThat(seen).hasSize(2)
  }
}
