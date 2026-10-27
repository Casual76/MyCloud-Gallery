package dev.pampa.mycloudgallery.utility

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.duplicates.AlbumMembership
import dev.pampa.mycloudgallery.core.library.duplicates.ShotVectorSource
import dev.pampa.mycloudgallery.core.library.similar.Raster
import dev.pampa.mycloudgallery.core.library.similar.VisualSignatures
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.EmbeddingSpace
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.SpaceCode
import dev.pampa.mycloudgallery.core.search.contract.SearchData
import dev.pampa.mycloudgallery.core.search.testing.FakeSearchData
import dev.pampa.mycloudgallery.hidden.BuiltWorld
import dev.pampa.mycloudgallery.hidden.HiddenWorldBuilder
import dev.pampa.mycloudgallery.hidden.MediaSpec
import dev.pampa.mycloudgallery.hidden.RepoFiles
import dev.pampa.mycloudgallery.hidden.Where
import dev.pampa.mycloudgallery.hidden.WorldSpec
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Debito del 27/10 (Fase 7, P8, D25): i Doppioni simili giudicano con i **vettori veri della ricerca** (`SearchData.vectors`, Fase 6) e ripiegano sulla
 * firma visiva dove un media non ha il vettore. Sul grafo vero dell'Utility (`UtilityGraph` sul catalogo vero in memoria): prima `ShotVectorSource` non era
 * legata in produzione e la pagina vedeva solo la firma.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class UtilitySimilarVectorsTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val day: LocalDate = LocalDate.of(2026, 5, 3)

  private fun photo(index: Int, minute: Int) = MediaSpec(index, MediaKind.PHOTO, MediaSource.CAMERA, day, minute, Where.PHONE)

  /** Un mondo di sole foto del telefono, una per minuto indicato (minuti dalla mezzanotte dello stesso giorno), tutte non nascoste. */
  private suspend fun world(vararg minutes: Int): BuiltWorld =
    HiddenWorldBuilder.build(context, WorldSpec(77, minutes.mapIndexed { i, m -> photo(i, m) }, emptyList(), emptySet(), emptySet(), emptySet()))

  private fun graph(world: BuiltWorld, vectors: ShotVectorSource): UtilityGraph = UtilityGraph(
    catalog = { world.catalog },
    visible = CatalogVisibleMedia(world.db, MutableStateFlow(world.snapshot)),
    hidden = CatalogHiddenMask(world.db),
    albums = AlbumMembership { emptySet() },
    vectors = vectors,
    signatures = { null },
    fullHashOn = { null },
  )

  /** I gruppi che la pagina dei Doppioni simili trova, tutti i giorni (come la pagina: un lotto per volta). */
  private suspend fun clusters(graph: UtilityGraph): List<Set<MediaId>> {
    val out = ArrayList<Set<MediaId>>()
    var before: Int? = null
    do {
      val batch = graph.similarShots.scan(before, 1.hours)
      batch.clusters.forEach { out += it.media.toSet() }
      before = batch.nextBeforeDay
    } while (before != null)
    return out
  }

  private fun unit(angle: Double): FloatArray = FloatArray(768).also {
    it[0] = cos(angle).toFloat()
    it[1] = sin(angle).toFloat()
  }

  private suspend fun sign(world: BuiltWorld, index: Int, raster: Raster) {
    val version = checkNotNull(world.catalog.media(world.ids[index])).currentVersionId
    world.catalog.saveSignature(version, VisualSignatures.of(raster))
  }

  @Test
  fun dueScattiConVettoriViciniEFirmeDiverse_FinisconoInsieme_ConLaPortaLegata() = runBlocking<Unit> {
    val w = world(12 * 60, 12 * 60 + 1, 18 * 60)
    try {
      sign(w, 0, scene(0))
      sign(w, 1, other())
      val search = FakeSearchData(w.snapshot).apply {
        vectors[w.ids[0]] = listOf(unit(0.0))
        vectors[w.ids[1]] = listOf(unit(0.02))
        vectors[w.ids[2]] = listOf(FloatArray(768).also { it[200] = 1f })
      }

      // Come prima del debito: nessuna porta, solo la firma. Le due firme sono diverse e la pagina non li raggruppa.
      assertThat(clusters(graph(w, ShotVectorSource { emptyMap() }))).isEmpty()

      // Con la porta della ricerca i due scatti (vettori vicini) stanno insieme; il terzo, lontano nel tempo e nel vettore, resta fuori.
      val wired = graph(w, SearchShotVectors(data = { search }))
      assertThat(clusters(wired)).containsExactly(setOf(w.ids[0], w.ids[1]))
    } finally {
      w.close()
    }
  }

  @Test
  fun senzaVettoriVaLaFirmaComeOggi() = runBlocking<Unit> {
    val w = world(12 * 60, 12 * 60 + 1, 12 * 60 + 2)
    try {
      sign(w, 0, scene(0))
      sign(w, 1, scene(2))
      sign(w, 2, other())
      val noVectors = FakeSearchData(w.snapshot)

      val found = clusters(graph(w, SearchShotVectors(data = { noVectors })))

      // La stessa scena (il soggetto si e' mosso) e' un gruppo; un'altra scena no: come con la sola firma.
      assertThat(found).containsExactly(setOf(w.ids[0], w.ids[1]))
    } finally {
      w.close()
    }
  }

  @Test
  fun doveUnMediaNonHaIlVettoreSiRipiegaSullaFirma_ChiLoHaVaAVettori() = runBlocking<Unit> {
    val w = world(12 * 60, 12 * 60 + 1, 15 * 60, 15 * 60 + 1)
    try {
      // A e B hanno il vettore (vicini) e firme diverse: vince il vettore. C e D non lo hanno: vale la firma (la stessa scena).
      sign(w, 0, scene(0))
      sign(w, 1, other())
      sign(w, 2, scene(0))
      sign(w, 3, scene(3))
      val search = FakeSearchData(w.snapshot).apply {
        vectors[w.ids[0]] = listOf(unit(0.0))
        vectors[w.ids[1]] = listOf(unit(0.02))
      }

      val found = clusters(graph(w, SearchShotVectors(data = { search })))

      assertThat(found).containsExactly(setOf(w.ids[0], w.ids[1]), setOf(w.ids[2], w.ids[3]))
    } finally {
      w.close()
    }
  }

  @Test
  fun unErroreDellaRicercaValeNessunVettore_ESiRipiegaSullaFirma() = runBlocking<Unit> {
    val w = world(12 * 60, 12 * 60 + 1)
    try {
      sign(w, 0, scene(0))
      sign(w, 1, scene(2))
      val broken = object : SearchData by FakeSearchData(w.snapshot) {
        override suspend fun vectors(ids: Collection<MediaId>, space: EmbeddingSpace): Map<MediaId, List<FloatArray>> =
          throw IllegalStateException("indice non leggibile")
      }

      assertThat(clusters(graph(w, SearchShotVectors(data = { broken })))).containsExactly(setOf(w.ids[0], w.ids[1]))
    } finally {
      w.close()
    }
  }

  @Test
  fun laRicercaSiChiedeSoloQuandoServe_EMaiSulThreadPrincipale() = runBlocking<Unit> {
    val w = world(12 * 60, 12 * 60 + 1)
    try {
      val main = Looper.getMainLooper().thread
      val threads = ArrayList<Thread>()
      var built = 0
      val search = FakeSearchData(w.snapshot).apply {
        vectors[w.ids[0]] = listOf(unit(0.0))
        vectors[w.ids[1]] = listOf(unit(0.02))
      }
      val spy = object : SearchData by search {
        override suspend fun vectors(ids: Collection<MediaId>, space: EmbeddingSpace): Map<MediaId, List<FloatArray>> {
          threads += Thread.currentThread()
          return search.vectors(ids, space)
        }
      }
      val vectors = SearchShotVectors(data = {
        // La costruzione tardiva della ricerca (Hilt la crea al primo uso) non blocca la pagina.
        threads += Thread.currentThread()
        built++
        spy
      })
      val utility = graph(w, vectors)
      assertThat(built).isEqualTo(0)

      assertThat(clusters(utility)).hasSize(1)

      assertThat(built).isGreaterThan(0)
      assertThat(threads).isNotEmpty()
      assertThat(threads.none { it === main }).isTrue()
      // Il chiamante e' proprio il thread principale: la prova non passa a vuoto.
      assertThat(Thread.currentThread()).isSameInstanceAs(main)
    } finally {
      w.close()
    }
  }

  @Test
  fun unaCellaConPiuVettoriNonHaUnVettore_EIlSuoSpazioEQuelloCorrente() = runBlocking<Unit> {
    val a = MediaId(java.util.UUID.randomUUID().toString())
    val b = MediaId(java.util.UUID.randomUUID().toString())
    val askedSpaces = ArrayList<EmbeddingSpace>()
    val search = object : SearchData by FakeSearchData() {
      override suspend fun vectors(ids: Collection<MediaId>, space: EmbeddingSpace): Map<MediaId, List<FloatArray>> {
        askedSpaces += space
        return mapOf(a to listOf(unit(0.0)), b to listOf(unit(0.0), unit(1.0)))
      }
    }

    val got = SearchShotVectors(data = { search }).vectorsOf(listOf(a, b, MediaId(java.util.UUID.randomUUID().toString())))

    assertThat(got.keys).containsExactly(a)
    assertThat(askedSpaces).containsExactly(SpaceCode.CURRENT.space)
    assertThat(SearchShotVectors(data = { null }).vectorsOf(listOf(a))).isEmpty()
    assertThat(SearchShotVectors(data = { error("non si chiede") }).vectorsOf(emptyList())).isEmpty()
  }

  /**
   * La guardia sul legame di produzione: i test sopra costruiscono il grafo da soli, e questo e' l'unico posto che lo costruisce davvero. Se il modulo
   * Hilt dell'Utility smette di passare i vettori della ricerca (o li prende da una ricerca vuota), i Doppioni tornano alla sola firma in silenzio.
   */
  @Test
  fun ilModuloDellUtilityLegaiVettoriDellaRicercaVera() {
    val module = RepoFiles.code(java.io.File(RepoFiles.root, "app/src/main/kotlin/dev/pampa/mycloudgallery/di/UtilityModule.kt"))
    val build = module.substringAfter("fun utilityGraph(").substringBefore("private fun sharedMembers")
    assertThat(build).contains("search: Provider<SearchData>")
    assertThat(build).contains("vectors = SearchShotVectors(data = { search.get() })")
    // Il costruttore non ha un valore di serie per `vectors`: una porta dimenticata non compila.
    val graph = RepoFiles.code(java.io.File(RepoFiles.root, "app/src/main/kotlin/dev/pampa/mycloudgallery/utility/UtilityGraph.kt"))
    assertThat(graph).contains("private val vectors: ShotVectorSource,")
    assertThat(graph).contains("CatalogSimilarShots(it, visible, hidden, vectors = vectors,")
  }

  @Test
  fun iNascostiNonEscono_NemmenoSeLaRicercaHaIVettoriDiTutti() = runBlocking<Unit> {
    var clustersSeen = 0
    var hiddenWorlds = 0
    for (seed in 1L..SEEDS) {
      val w = HiddenWorldBuilder.build(context, WorldSpec.random(seed))
      try {
        if (w.liveMustNeverShow.isNotEmpty()) hiddenWorlds++
        // Lo stesso vettore per ogni media, nascosti compresi: il filtro finale e' di `CatalogSimilarShots` (D22), non della porta dei vettori.
        val search = FakeSearchData(w.snapshot).apply { w.ids.forEach { vectors[it] = listOf(floatArrayOf(1f, 0f, 0f, 0f)) } }
        val utility = graph(w, SearchShotVectors(data = { search }))
        val shown = clusters(utility).flatten()
        clustersSeen += shown.size
        assertThat(shown.filter { it in w.liveMustNeverShow }).isEmpty()
        assertThat(utility.similarShots.placesOf(w.ids).media).isAtMost(w.liveShowable.size)
      } finally {
        w.close()
      }
    }
    assertThat(hiddenWorlds).isGreaterThan(0)
    assertThat(clustersSeen).isGreaterThan(0)
  }

  private companion object {
    const val SEEDS = 8L

    /** Una scena con struttura; [shift] sposta il riquadro del soggetto (la disposizione resta quella). */
    fun scene(shift: Int): Raster {
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

    fun other(): Raster {
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
}
