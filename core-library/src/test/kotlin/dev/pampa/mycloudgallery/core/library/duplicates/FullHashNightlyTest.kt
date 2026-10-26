package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.CatalogVerifyScope
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.similar.Fx
import dev.pampa.mycloudgallery.core.library.similar.SimilarCatalogTestBase
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * L'hash completo dei video del NAS "per tutti, di notte" (Fase 7, P8, D27, E-U4): l'interruttore si accende e si spegne. Acceso mette in coda
 * `FULL_HASH` per i video del NAS senza hash completo, con le stesse righe che l'attivita' esistente consuma; spento toglie quelle messe per
 * questo e lascia stare i gruppi "da verificare". Ogni passo e' idempotente: dopo un'interruzione si riprende senza rifare niente.
 */
@RunWith(RobolectricTestRunner::class)
class FullHashNightlyTest : SimilarCatalogTestBase() {

  private var seedCounter = 40

  private suspend fun nasVideo(path: String, hashed: Boolean): MediaId {
    val bytes = Random(++seedCounter).nextBytes(1_200)
    val fingerprint = if (hashed) Fingerprint.ofContent(bytes) else Fingerprint(bytes.size.toLong(), QuickHashAlgorithm.ofContent(bytes), fullHash = null)
    val observation = Observation(Location.Nas(Fx.ME, "alessio", NasPath.of(path)), FileStamp(fingerprint.size, Fx.T0), fingerprint, MediaKind.VIDEO, MediaSource.OTHER)
    return catalog.recordAll(listOf(observation), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = Fx.T0).single().mediaId!!
  }

  private suspend fun nasPhoto(path: String): MediaId {
    val bytes = Random(++seedCounter).nextBytes(1_200)
    val fingerprint = Fingerprint(bytes.size.toLong(), QuickHashAlgorithm.ofContent(bytes), fullHash = null)
    val observation = Observation(Location.Nas(Fx.ME, "alessio", NasPath.of(path)), FileStamp(fingerprint.size, Fx.T0), fingerprint, MediaKind.PHOTO, MediaSource.OTHER)
    return catalog.recordAll(listOf(observation), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = Fx.T0).single().mediaId!!
  }

  private suspend fun nightly(batch: Int = 100, time: TimeSource = TimeSource.Monotonic): FullHashNightly {
    val snapshot = MutableStateFlow<TimelineSnapshot?>(CatalogTimelineSource(db, catalog).buildIndex())
    return FullHashNightly(catalog, CatalogVisibleMedia(db, snapshot), time, batch)
  }

  private suspend fun statusOf(id: MediaId): WorkStatus? = catalog.workStates(id).firstOrNull { it.stage == WorkStage.FULL_HASH }?.status

  @Test
  fun spentoNonMetteInCodaNiente_AccesoSoloIVideoDelNasSenzaHashCompleto() = runBlocking<Unit> {
    val needs = nasVideo("Video/a.mp4", hashed = false)
    val alsoNeeds = nasVideo("Video/b.mp4", hashed = false)
    val known = nasVideo("Video/c.mp4", hashed = true)
    val photo = nasPhoto("Foto/p.jpg")
    val phoneVideo = phoneShot(kind = MediaKind.VIDEO)
    val finder = nightly()

    // Spento: niente in coda.
    val off = finder.apply(on = false, budget = 10.seconds)
    assertThat(off.enabled).isFalse()
    assertThat(off.queued).isEqualTo(0)
    assertThat(listOf(needs, alsoNeeds, known, photo, phoneVideo).map { statusOf(it) }).containsExactly(null, null, null, null, null)

    // Acceso: i due video del NAS senza hash completo. Non la foto, non il video che l'hash ce l'ha, non quello solo sul telefono.
    val on = finder.apply(on = true, budget = 10.seconds)
    assertThat(on.enabled).isTrue()
    assertThat(on.queued).isEqualTo(2)
    assertThat(on.finished).isTrue()
    assertThat(statusOf(needs)).isEqualTo(WorkStatus.PENDING)
    assertThat(statusOf(alsoNeeds)).isEqualTo(WorkStatus.PENDING)
    assertThat(listOf(known, photo, phoneVideo).map { statusOf(it) }).containsExactly(null, null, null)
  }

  @Test
  fun leRigheMesseInCodaSonoQuelleChePrendeLAttivitaEsistente() = runBlocking<Unit> {
    val needs = nasVideo("Video/a.mp4", hashed = false)
    val finder = nightly()

    assertThat(catalog.verifyCandidates(CatalogVerifyScope.FullHash, 10)).isEmpty()
    finder.apply(on = true, budget = 10.seconds)

    val toRead = catalog.verifyCandidates(CatalogVerifyScope.FullHash, 10)
    assertThat(toRead.map { it.copy.mediaId }).containsExactly(needs)
    // La lettura non ha un hash atteso: lo calcola (e' `FULL_HASH`, non una verifica "al sicuro").
    assertThat(toRead.single().expected).isNull()
  }

  @Test
  fun unSecondoGiroNonRifaNiente() = runBlocking<Unit> {
    nasVideo("Video/a.mp4", hashed = false)
    nasVideo("Video/b.mp4", hashed = false)
    val finder = nightly()

    assertThat(finder.apply(true, 10.seconds).queued).isEqualTo(2)
    assertThat(finder.apply(true, 10.seconds).queued).isEqualTo(0)
  }

  @Test
  fun spegnerloTogliDallaCodaQuelloCheAveva_MessoInCoda_ERiaccendendoloTorna() = runBlocking<Unit> {
    val a = nasVideo("Video/a.mp4", hashed = false)
    val b = nasVideo("Video/b.mp4", hashed = false)
    val finder = nightly()
    finder.apply(true, 10.seconds)
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.FullHash, 10)).hasSize(2)

    val off = finder.apply(false, 10.seconds)

    assertThat(off.withdrawn).isEqualTo(2)
    assertThat(statusOf(a)).isEqualTo(WorkStatus.GAVE_UP)
    assertThat(statusOf(b)).isEqualTo(WorkStatus.GAVE_UP)
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.FullHash, 10)).isEmpty()
    // Spegnerlo due volte non fa niente di piu'.
    assertThat(finder.apply(false, 10.seconds).withdrawn).isEqualTo(0)
    // Riacceso, tornano in coda.
    assertThat(finder.apply(true, 10.seconds).queued).isEqualTo(2)
    assertThat(statusOf(a)).isEqualTo(WorkStatus.PENDING)
  }

  @Test
  fun spegnerloNonToccaLaCodaDeiGruppiDaVerificare() = runBlocking<Unit> {
    // Lo stesso video sul telefono e sul NAS, con la stessa impronta rapida e senza hash completo sul NAS: nasce un gruppo "da verificare" e
    // il catalogo mette in coda `FULL_HASH` da solo (F4-D21). Lo spegnimento dell'interruttore non deve toglierlo.
    val bytes = Random(5).nextBytes(1_500)
    val phoneFingerprint = Fingerprint.ofContent(bytes)
    val phone = Location.Phone(Fx.VOLUME, "DCIM/Camera/", "V.mp4", 700L, Fx.STORE)
    catalog.record(Observation(phone, FileStamp(phoneFingerprint.size, Fx.T0, 7), phoneFingerprint, MediaKind.VIDEO, MediaSource.CAMERA, Fx.SECOND0))
    val nasFingerprint = Fingerprint(bytes.size.toLong(), phoneFingerprint.quickHash, fullHash = null)
    val nas = Observation(Location.Nas(Fx.ME, "alessio", NasPath.of("Backup/pixel/V.mp4")), FileStamp(nasFingerprint.size, Fx.T0), nasFingerprint, MediaKind.VIDEO, MediaSource.OTHER)
    val nasMedia = catalog.recordAll(listOf(nas), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = Fx.T0).single().mediaId!!
    val before = statusOf(nasMedia)
    assertThat(before).isEqualTo(WorkStatus.PENDING)

    nightly().apply(false, 10.seconds)

    assertThat(statusOf(nasMedia)).isEqualTo(before)
  }

  /** E-U4: il NAS si spegne a meta' (qui: il tempo finisce dopo pochi video). La notte dopo si riprende, e ogni video e' in coda una volta sola. */
  @Test
  fun e_u4_UnaInterruzioneAMeta_LaNotteDopoSiRiprende_SenzaDoppioni() = runBlocking<Unit> {
    val videos = (1..6).map { nasVideo("Video/v$it.mp4", hashed = false) }
    val slow = SteppingTime()
    val finder = nightly(batch = 2, time = slow)

    // Il tempo finisce dopo tre video: ne restano in coda al piu' quelli visti.
    val first = finder.enqueue(budget = 3.seconds)
    assertThat(first.finished).isFalse()
    val queuedFirst = videos.filter { statusOf(it) == WorkStatus.PENDING }
    assertThat(queuedFirst.size).isAtLeast(1)
    assertThat(queuedFirst.size).isLessThan(videos.size)

    // La notte dopo (tempo a volonta') il resto: in totale sei video, ognuno una volta.
    val second = nightly().enqueue(budget = 60.seconds)
    assertThat(second.finished).isTrue()
    assertThat(second.queued).isEqualTo(videos.size - queuedFirst.size)
    assertThat(videos.map { statusOf(it) }.distinct()).containsExactly(WorkStatus.PENDING)
    assertThat(catalog.verifyCandidates(CatalogVerifyScope.FullHash, 50).map { it.copy.mediaId }).containsExactlyElementsIn(videos)
  }

  @Test
  fun senzaIstantaneaNonFaNiente() = runBlocking<Unit> {
    val finder = FullHashNightly(catalog, CatalogVisibleMedia(db, MutableStateFlow(null)))
    assertThat(finder.apply(true, Duration.ZERO).finished).isTrue()
    assertThat(finder.apply(false, Duration.ZERO).finished).isTrue()
  }

  /** Un orologio che avanza di un secondo a ogni lettura: il "tempo" finisce dopo qualche passo, senza dormire. */
  private class SteppingTime : TimeSource {
    private var ticks = 0L

    override fun markNow(): kotlin.time.TimeMark = object : kotlin.time.TimeMark {
      override fun elapsedNow(): Duration = (ticks++).seconds
    }
  }
}
