package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.SimilarCandidate
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.similar.Fx
import dev.pampa.mycloudgallery.core.library.similar.Raster
import dev.pampa.mycloudgallery.core.library.similar.SignaturePixels
import dev.pampa.mycloudgallery.core.library.similar.SignatureSource
import dev.pampa.mycloudgallery.core.library.similar.SimilarCatalogTestBase
import dev.pampa.mycloudgallery.core.library.similar.VisualSignatures
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * I doppioni simili sul catalogo vero (Room in memoria, Fase 7, P8, D25, §2.1.1; E-U3): candidati dall'istantanea della Libreria, vettori finti
 * o firma visiva, la migliore da tenere, preferite e foto in un album protette, i nascosti che non escono, le firme che mancano.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogSimilarShotsTest : SimilarCatalogTestBase() {

  private val second = 1_000L
  private val day = 24L * 3_600 * 1_000

  private fun unit(angleToFirst: Double): FloatArray = FloatArray(768).also {
    it[0] = kotlin.math.cos(angleToFirst).toFloat()
    it[1] = kotlin.math.sin(angleToFirst).toFloat()
  }

  /** Un vettore per ogni media: [near] vicini fra loro (coseno ~0,99), gli altri lontani. */
  private fun vectorsFor(near: Collection<MediaId>, far: Collection<MediaId> = emptyList()): ShotVectorSource {
    val map = HashMap<MediaId, FloatArray>()
    near.forEachIndexed { i, id -> map[id] = unit(0.02 * i) }
    far.forEachIndexed { i, id -> map[id] = FloatArray(768).also { v -> v[100 + i] = 1f } }
    return ShotVectorSource { ids -> ids.filter { it in map }.associateWith { map.getValue(it) } }
  }

  private suspend fun snapshot(): MutableStateFlow<TimelineSnapshot?> = MutableStateFlow(CatalogTimelineSource(db, catalog).buildIndex())

  private suspend fun finder(
    vectors: ShotVectorSource = ShotVectorSource { emptyMap() },
    albums: AlbumMembership = AlbumMembership { emptySet() },
    signatures: SignatureSource? = null,
    hidden: HiddenMask = CatalogHiddenMask(db),
  ): CatalogSimilarShots {
    val flow = snapshot()
    return CatalogSimilarShots(catalog, CatalogVisibleMedia(db, flow), hidden, vectors, albums, signatures)
  }

  private suspend fun scanAll(finder: CatalogSimilarShots): List<SimilarCluster> {
    val out = ArrayList<SimilarCluster>()
    var before: Int? = null
    do {
      val batch = finder.scan(before, Duration.ZERO)
      out += batch.clusters
      before = batch.nextBeforeDay
    } while (before != null)
    return out
  }

  @Test
  fun treScattiDiUnaRaffica_ConVettoriVicini_FormanoUnGruppo_ELaMiglioreEQuellaConPiuPixel() = runBlocking<Unit> {
    val a = phoneShot(takenAt = Fx.SECOND0, width = 4000, height = 3000)
    val b = phoneShot(takenAt = Fx.SECOND0 + 2 * second, width = 4000, height = 3000)
    val c = phoneShot(takenAt = Fx.SECOND0 + 5 * second, width = 6000, height = 4500)
    val lonely = phoneShot(takenAt = Fx.SECOND0 + 3 * 3_600 * second)

    val clusters = scanAll(finder(vectorsFor(listOf(a, b, c), far = listOf(lonely))))

    assertThat(clusters).hasSize(1)
    val cluster = clusters.single()
    assertThat(cluster.media).containsExactly(a, b, c).inOrder()
    assertThat(cluster.best).isEqualTo(c)
    assertThat(cluster.keepByDefault).containsExactly(c)
    assertThat(cluster.removeByDefault).containsExactly(a, b)
  }

  @Test
  fun scattiFuoriDallaFinestraODiGiorniDiversiNonSiRaggruppano() = runBlocking<Unit> {
    val a = phoneShot(takenAt = Fx.SECOND0)
    val later = phoneShot(takenAt = Fx.SECOND0 + 11 * 60 * second)
    val nextDay = phoneShot(takenAt = Fx.SECOND0 + day)

    assertThat(scanAll(finder(vectorsFor(listOf(a, later, nextDay))))).isEmpty()
  }

  @Test
  fun e_u3_UnaPreferitaEUnaFotoInUnAlbumSonoDaTenere_MaiPreselezionatePerLEliminazione() = runBlocking<Unit> {
    val plain = phoneShot(takenAt = Fx.SECOND0, width = 6000, height = 4500)
    val favorite = phoneShot(takenAt = Fx.SECOND0 + second)
    val inAlbum = phoneShot(takenAt = Fx.SECOND0 + 2 * second)
    val other = phoneShot(takenAt = Fx.SECOND0 + 3 * second)
    catalog.setPersonalFlags(listOf(favorite)) { it.copy(favorite = true) }

    val clusters = scanAll(finder(vectorsFor(listOf(plain, favorite, inAlbum, other)), albums = AlbumMembership { setOf(inAlbum) }))

    val cluster = clusters.single()
    assertThat(cluster.best).isEqualTo(favorite)
    assertThat(cluster.keepByDefault).containsExactly(favorite, inAlbum)
    assertThat(cluster.removeByDefault).containsExactly(plain, other)
    assertThat(cluster.details.getValue(favorite).favorite).isTrue()
    assertThat(cluster.details.getValue(inAlbum).inAlbum).isTrue()
  }

  @Test
  fun iNascostiNonEscono_NemmenoQuelliDiUnAttimoFa() = runBlocking<Unit> {
    val a = phoneShot(takenAt = Fx.SECOND0)
    val b = phoneShot(takenAt = Fx.SECOND0 + second)
    val c = phoneShot(takenAt = Fx.SECOND0 + 2 * second)
    val vectors = vectorsFor(listOf(a, b, c))

    // Nascosto prima: non e' un candidato (l'istantanea non lo ha), il gruppo e' degli altri due.
    catalog.setPersonalFlags(listOf(c)) { it.copy(hidden = true) }
    assertThat(scanAll(finder(vectors)).single().media).containsExactly(a, b)

    // Nascosto dopo che l'istantanea e' stata letta: il filtro finale (HiddenMask) lo toglie, e un gruppo con meno di due scompare.
    val late = HiddenMask { ids -> ids.filterTo(HashSet()) { it == b || it == c } }
    assertThat(scanAll(finder(vectors, hidden = late))).isEmpty()
  }

  @Test
  fun leCelleChePorganoGiaUnGruppoNonSiRaggruppano() = runBlocking<Unit> {
    val a = phoneShot(takenAt = Fx.SECOND0)
    val b = phoneShot(takenAt = Fx.SECOND0 + second)
    val c = phoneShot(takenAt = Fx.SECOND0 + 2 * second)
    val d = phoneShot(takenAt = Fx.SECOND0 + 3 * second)
    // a e b sono gia' una raffica: la loro cella e' una sola, e non si rimette in un altro gruppo.
    catalog.group(MediaGroupKind.BURST, listOf(a, b), cover = a)

    val clusters = scanAll(finder(vectorsFor(listOf(a, b, c, d))))

    assertThat(clusters.single().media).containsExactly(c, d)
  }

  @Test
  fun senzaVettoriSiUsaLaFirmaVisivaDellaFase5() = runBlocking<Unit> {
    val a = phoneShot(takenAt = Fx.SECOND0)
    val b = phoneShot(takenAt = Fx.SECOND0 + second)
    val c = phoneShot(takenAt = Fx.SECOND0 + 2 * second)
    catalog.saveSignature(versionOf(a), VisualSignatures.of(scene(0)))
    catalog.saveSignature(versionOf(b), VisualSignatures.of(scene(2)))
    catalog.saveSignature(versionOf(c), VisualSignatures.of(other()))

    val clusters = scanAll(finder())

    // a e b: la stessa scena (il soggetto si e' mosso); c: un'altra scena.
    assertThat(clusters.single().media).containsExactly(a, b)
  }

  @Test
  fun senzaNeVettoriNeFirme_NonSiDiceNiente_ESiConta() = runBlocking<Unit> {
    phoneShot(takenAt = Fx.SECOND0)
    phoneShot(takenAt = Fx.SECOND0 + second)

    val batch = finder().scan(null, 10.seconds)

    assertThat(batch.clusters).isEmpty()
    assertThat(batch.undecided).isEqualTo(2)
    assertThat(batch.nextBeforeDay).isNull()
  }

  @Test
  fun unLottoPerGiorno_RipresaDaDoveSiEraFermi() = runBlocking<Unit> {
    val d1 = listOf(phoneShot(takenAt = Fx.SECOND0), phoneShot(takenAt = Fx.SECOND0 + second))
    val d2 = listOf(phoneShot(takenAt = Fx.SECOND0 - day), phoneShot(takenAt = Fx.SECOND0 - day + second))
    val d3 = listOf(phoneShot(takenAt = Fx.SECOND0 - 2 * day), phoneShot(takenAt = Fx.SECOND0 - 2 * day + second))
    val finder = finder(vectorsFor(d1 + d2 + d3))

    val first = finder.scan(null, Duration.ZERO)
    assertThat(first.clusters.single().media).containsExactlyElementsIn(d1)
    assertThat(first.nextBeforeDay).isNotNull()
    val next = finder.scan(first.nextBeforeDay, Duration.ZERO)
    assertThat(next.clusters.single().media).containsExactlyElementsIn(d2)
    val third = finder.scan(next.nextBeforeDay, Duration.ZERO)
    assertThat(third.clusters.single().media).containsExactlyElementsIn(d3)
    assertThat(third.nextBeforeDay).isNull()
  }

  // --- La manutenzione delle firme --------------------------------------------------------------------------------------------

  private class Pixels : SignatureSource {
    val rasters = HashMap<MediaId, Raster>()
    val asked = ArrayList<MediaId>()

    override suspend fun pixelsOf(candidate: SimilarCandidate): SignaturePixels {
      asked += candidate.mediaId
      return rasters[candidate.mediaId]?.let { SignaturePixels.Ready(listOf(it)) } ?: SignaturePixels.Later()
    }
  }

  @Test
  fun laManutenzioneFirmaSoloChiHaUnCompagnoENonHaNienteGia() = runBlocking<Unit> {
    val a = phoneShot(takenAt = Fx.SECOND0)
    val b = phoneShot(takenAt = Fx.SECOND0 + second)
    val alone = phoneShot(takenAt = Fx.SECOND0 + 3 * 3_600 * second)
    val withVector = phoneShot(takenAt = Fx.SECOND0 + 2 * second)
    val source = Pixels().apply {
      rasters[a] = scene(0)
      rasters[b] = scene(3)
      rasters[alone] = scene(0)
      rasters[withVector] = scene(0)
    }
    val finder = finder(vectors = vectorsFor(listOf(withVector)), signatures = source)

    val report = finder.sign(10.seconds)

    // a e b hanno un compagno (e withVector, ma ha gia' il vettore): quindi a e b si firmano; alone e withVector no.
    assertThat(report.signed).isEqualTo(2)
    assertThat(source.asked).containsExactly(a, b)
    assertThat(report.finished).isTrue()
    assertThat(catalog.signatureOf(versionOf(a))).isNotNull()
    assertThat(catalog.signatureOf(versionOf(alone))).isNull()

    // Un secondo giro non rifa niente.
    source.asked.clear()
    assertThat(finder.sign(10.seconds).signed).isEqualTo(0)
    assertThat(source.asked).isEmpty()
    // E adesso il gruppo c'e', con la firma (a e b, senza vettori).
    assertThat(scanAll(finder(signatures = source)).single().media).containsExactly(a, b)
  }

  @Test
  fun unaSorgenteCheNonHaIPixelLasciaIlCandidatoInAttesa() = runBlocking<Unit> {
    phoneShot(takenAt = Fx.SECOND0)
    phoneShot(takenAt = Fx.SECOND0 + second)
    val finder = finder(signatures = Pixels())

    val report = finder.sign(10.seconds)

    assertThat(report.signed).isEqualTo(0)
    assertThat(report.waiting).isEqualTo(2)
  }

  @Test
  fun senzaSorgenteLaManutenzioneNonFaNiente() = runBlocking<Unit> {
    phoneShot(takenAt = Fx.SECOND0)
    phoneShot(takenAt = Fx.SECOND0 + second)
    assertThat(finder().sign(10.seconds).signed).isEqualTo(0)
    assertThat(NoSimilarShots.sign(10.seconds).finished).isTrue()
    assertThat(NoSimilarShots.scan(null, 10.seconds).clusters).isEmpty()
  }

  // --- Dove stanno i media da eliminare ---------------------------------------------------------------------------------------

  @Test
  fun placesOfDividePerPostoEDaiPianiDiSoloTelefonoEOvunque() = runBlocking<Unit> {
    val onPhone = phoneShot(takenAt = Fx.SECOND0)
    val onNas = nasShot("Backup/pixel/2026/x.jpg", takenAt = Fx.SECOND0 + second)
    val hidden = phoneShot(takenAt = Fx.SECOND0 + 2 * second)
    catalog.setPersonalFlags(listOf(hidden)) { it.copy(hidden = true) }

    val places = finder().placesOf(listOf(onPhone, onNas, hidden))

    assertThat(places.media).isEqualTo(2)
    assertThat(places.phone).hasSize(1)
    assertThat(places.nasPrivate.map { it.path }).containsExactly("Backup/pixel/2026/x.jpg")
    assertThat(places.nasCommon).isEmpty()
    assertThat(places.phoneOnly.phone).hasSize(1)
    assertThat(places.phoneOnly.nas).isEmpty()
    assertThat(places.everywhere.copies).isEqualTo(2)
    // Il media del telefono non ha nessuna copia al sicuro (E-D14).
    assertThat(places.unsafePhoneMedia).isEqualTo(1)
  }

  // --- Scene ------------------------------------------------------------------------------------------------------------------

  /** Una scena con struttura; [shift] sposta il riquadro del soggetto (la disposizione resta quella). */
  private fun scene(shift: Int): Raster {
    val w = 256
    val h = 192
    return Raster.of(
      w, h,
      IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var r = 40 + (x * 150 / w)
        var g = 60 + (y * 120 / h)
        var b = 90 + ((x + y) % 64)
        if (x in (100 + shift)..(150 + shift) && y in 60..120) {
          r = 220
          g = 40
          b = 40
        }
        (r shl 16) or (g shl 8) or b
      },
    )
  }

  private fun other(): Raster {
    val w = 256
    val h = 192
    return Raster.of(
      w, h,
      IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val inBox = x in 20..90 && y in 110..180
        val r = if (inBox) 30 else 200 - (x * 120 / w)
        val g = if (inBox) 160 else 180 - (y * 100 / h)
        val b = if (inBox) 220 else 60 + ((x * 3 + y) % 50)
        (r shl 16) or (g shl 8) or b
      },
    )
  }
}
