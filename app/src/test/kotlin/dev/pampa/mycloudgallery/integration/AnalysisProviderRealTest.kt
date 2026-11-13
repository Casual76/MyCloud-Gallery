package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.ai.shownIds
import dev.pampa.mycloudgallery.core.ai.compute.AiRunControl
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisLiveSource
import dev.pampa.mycloudgallery.di.AiModule
import dev.pampa.mycloudgallery.di.AlbumsModule
import dev.pampa.mycloudgallery.di.GalleryModule
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * XS2-1, il **fornitore vero** del nastro: la prova prende `AiModule.analysisLive(...)` (la funzione che Hilt chiama) con lo stack vero (`AiStack`, `AppGraph`,
 * `GalleryGraph` montati come in produzione, motori finti) e con le porte che Hilt le da' (`AlbumsModule.visibleMedia`, `GalleryModule.hiddenMask`), non una
 * ricostruzione della sua topologia (`AnalysisVisibilityIntegrationTest` monta i pezzi a mano). Una foto calcolata davvero dal motore compare sul nastro; un
 * "Nascondi" fatto dalla Libreria vera (`gallery.actions.setHidden`, che avvisa anche `analysisVisibility`) la toglie e la generazione di visibilita' sale. Non copre
 * la revoca dei permessi (non c'e' un gancio per il permesso nel montaggio vero): quella e' in `AnalysisVisibilityIntegrationTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class AnalysisProviderRealTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private lateinit var rig: AiRig

  @Before
  fun setUp() {
    rig = AiRig(app, temp.root)
  }

  @After
  fun tearDown() = rig.close()

  @Test
  fun ilFornitoreVeroMostraLaFotoCalcolataEUnNascondiDellaLibreriaLaTogliSubito() = runBlocking<Unit> {
    val store = NasGalleries.newStore()
    val bytes = AiRig.jpeg(60)
    store.put(AiRig.photoRow(1, "DCIM/mcg-prova/", "IMG_0001.jpg", bytes, rig.time), bytes)
    val phone = rig.founder(store)
    rig.enableReading(phone)
    // Il motore vero (con le porte finte dei modelli) calcola la foto: `ai.live` ha la sua chiave fra le ultime finite.
    checkNotNull(phone.stack.ai.runStage(5.minutes, AiRunControl.ALWAYS))
    // La ricerca aperta tiene aggiornate le chiavi delle celle (`CellKeys.sync`), come in produzione quando la pagina e' aperta.
    assertThat(phone.stack.search.open()).isTrue()
    // Le chiavi della cella devono essere pronte quando il nastro risolve la prima volta: la sincronizzazione delle chiavi non e' un ingresso del nastro (non si
    // ricalcola da sola quando arrivano; si ricalcola al prossimo avanzamento del motore o evento di visibilita').
    val computed = phone.stack.ai.live.value.recent.single()
    val computedId = checkNotNull(phone.stack.ai.parts.facts[computed]).mediaId
    phone.stack.search.keys.sync()
    assertThat(phone.stack.search.keys[computedId]).isNotNull()

    val source = AiModule.analysisLive(
      phone.stack, phone.test.graph, phone.gallery,
      AlbumsModule.visibleMedia(phone.gallery), GalleryModule.hiddenMask(phone.gallery),
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val listenersBefore = phone.gallery.analysisVisibility.listenerCount
      val watching = scope.launch { source.live.collect {} }
      val before = try {
        withTimeout(30_000) { source.live.first { it.shownIds().isNotEmpty() } }
      } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        val engine = phone.stack.ai.live.value
        val facts = phone.stack.ai.parts.facts
        val key = engine.recent.firstOrNull()
        val id = key?.let { facts[it]?.mediaId }
        val snap = phone.gallery.visibleTimeline.snapshot.value
        val visibleIds = id?.let { AlbumsModule.visibleMedia(phone.gallery).visible(setOf(it)) }
        val detail = "id=$id posizione=${id?.let { snap?.indexOf(it) }} cella=${id?.let { phone.stack.search.keys[it] }?.let { c -> key in c.keys }} " +
          "visibile=${visibleIds?.isNotEmpty()}"
        throw AssertionError(
          "nessuna anteprima sul nastro: motore(adesso=${engine.current != null}, ultime=${engine.recent.size}, " +
            "fatti noti=${(listOfNotNull(engine.current) + engine.recent).count { facts[it] != null }}), " +
            "$detail, nastro=${source.live.value}, vista=${phone.gallery.visibleTimeline.snapshot.value?.javaClass?.simpleName}",
          e,
        )
      }
      val id = before.shownIds().distinct().single()
      // Il fornitore ascolta i "Nascondi" della Libreria vera mentre il nastro e' guardato (`analysisVisibility`): senza questo, un "Nascondi" che non cambia l'istantanea
      // non rifarebbe il nastro. Mutazione: il fornitore che passa un flusso di eventi vuoto.
      assertThat(phone.gallery.analysisVisibility.listenerCount).isEqualTo(listenersBefore + 1)

      // Il "Nascondi" vero della Libreria: la foto esce dal nastro e la generazione sale (chi disegna chiude cio' che aveva).
      phone.gallery.actions.setHidden(setOf(id), true)
      val after = withTimeout(30_000) { source.live.first { id !in it.shownIds() && it.visibilityGeneration > before.visibilityGeneration } }
      assertThat(after.shownIds()).doesNotContain(id)
      assertThat(after.visibilityGeneration).isGreaterThan(before.visibilityGeneration)
      watching.cancel()
    } finally {
      scope.cancel()
    }
  }
  @Test
  @Config(sdk = [34])
  fun ilProviderRealeNeutralizzaFullParzialeAlRitornoDelRefresh() = realPermissionRefresh(true)

  @Test
  @Config(sdk = [34])
  fun ilProviderRealeNeutralizzaFullNessunoAlRitornoDelRefresh() = realPermissionRefresh(false)

  private fun realPermissionRefresh(partial: Boolean) = runBlocking<Unit> {
    val shadow = org.robolectric.Shadows.shadowOf(app)
    val images = "android.permission.READ_MEDIA_IMAGES"
    val videos = "android.permission.READ_MEDIA_VIDEO"
    shadow.grantPermissions(images, videos, "android.permission.ACCESS_MEDIA_LOCATION")
    val access = dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneAccessReader(app, volumeNames = { setOf("external_primary") })
    val store = NasGalleries.newStore()
    val bytes = AiRig.jpeg(60)
    store.put(AiRig.photoRow(1, "DCIM/mcg-prova/", "IMG_0001.jpg", bytes, rig.time), bytes)
    val phone = rig.startMounted(rig.mount(rig.foundedDevice(), TestNas.ADMIN_USER, store, accessOverride = access))
    rig.enableReading(phone)
    checkNotNull(phone.stack.ai.runStage(5.minutes, AiRunControl.ALWAYS))
    assertThat(phone.stack.search.open()).isTrue()
    val computed = phone.stack.ai.live.value.recent.single()
    val computedId = checkNotNull(phone.stack.ai.parts.facts[computed]).mediaId
    phone.stack.search.keys.sync()
    assertThat(phone.stack.search.keys[computedId]).isNotNull()
    val source = AiModule.analysisLive(phone.stack, phone.test.graph, phone.gallery,
      AlbumsModule.visibleMedia(phone.gallery), GalleryModule.hiddenMask(phone.gallery))
    val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      observer.launch { source.live.collect {} }
      val before = withTimeout(30_000) { source.live.first { it.shownIds().isNotEmpty() } }
      shadow.denyPermissions(images, videos)
      if (partial) shadow.grantPermissions("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
      phone.gallery.refreshAccess()
      val immediate = source.live.value
      assertThat(immediate.visibilityGeneration).isGreaterThan(before.visibilityGeneration)
      assertThat(immediate.shownIds()).isEmpty()
    } finally { observer.cancel() }
  }

  // ---- XL: il nastro con il collegamento vero (galleria, sincronizzazione del telefono, catalogo, istantanea) ------------------------------------------

  /** Il fornitore vero con **due** foto calcolate sul nastro (le loro identita' in [ids]). */
  private class Strip(val phone: AiRig.Mounted, val store: FakeMediaStore, val source: AnalysisLiveSource, val ids: List<MediaId>)

  private suspend fun twoPhotosOnTheStrip(scope: CoroutineScope): Strip {
    val store = NasGalleries.newStore()
    val first = AiRig.jpeg(60)
    val second = AiRig.jpeg(150)
    store.put(AiRig.photoRow(1, "DCIM/mcg-prova/", "IMG_0001.jpg", first, rig.time), first)
    store.put(AiRig.photoRow(2, "DCIM/mcg-prova/", "IMG_0002.jpg", second, rig.time), second)
    val phone = rig.founder(store)
    rig.enableReading(phone)
    checkNotNull(phone.stack.ai.runStage(5.minutes, AiRunControl.ALWAYS))
    assertThat(phone.stack.search.open()).isTrue()
    assertThat(phone.stack.ai.live.value.recent).hasSize(2)
    phone.stack.search.keys.sync()
    val source = AiModule.analysisLive(
      phone.stack, phone.test.graph, phone.gallery,
      AlbumsModule.visibleMedia(phone.gallery), GalleryModule.hiddenMask(phone.gallery),
    )
    scope.launch { source.live.collect {} }
    val before = withTimeout(30_000) { source.live.first { it.shownIds().distinct().size == 2 } }
    return Strip(phone, store, source, before.shownIds().distinct())
  }

  /**
   * XS5-2, con il collegamento vero: un avviso di Android **ridondante** (niente e' cambiato) chiude il nastro nello stesso istante; la rilettura **vera** del telefono
   * (`DefaultPhoneLibrarySync` sul MediaStore finto e il catalogo vero) finisce senza cambiare niente, dichiara se stessa alla barriera di `GalleryGraph`, e il nastro **riapre con le
   * stesse due foto** senza che arrivi nessuna istantanea nuova. Mutazioni: la sincronizzazione che non dichiara (collegamento tolto); la barriera che non avvisa chi ascolta.
   */
  @Test
  fun unAvvisoRidondanteDiAndroidRiapreIlNastroQuandoLaRiletturaVeraFinisce() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val strip = twoPhotosOnTheStrip(scope)
      val snapshot = strip.phone.gallery.visibleTimeline.snapshot.value
      val before = strip.source.live.value

      strip.phone.gallery.analysisVisibility.contentChanged()
      assertThat(strip.source.live.value.shownIds()).isEmpty()
      assertThat(strip.source.live.value.visibilityGeneration).isGreaterThan(before.visibilityGeneration)

      val report = strip.phone.gallery.core().phone.sync.syncNow(SyncReason.CONTENT_CHANGE)
      assertThat(report.completed).isTrue()
      assertThat(report.added + report.updated + report.vanished).isEqualTo(0)
      val back = withTimeout(30_000) { strip.source.live.first { it.shownIds().distinct().size == 2 } }
      assertThat(back.shownIds().distinct()).containsExactlyElementsIn(strip.ids)
      // Nessuna istantanea nuova: la riapertura viene dalla rilettura finita.
      assertThat(strip.phone.gallery.visibleTimeline.snapshot.value).isSameInstanceAs(snapshot)
    } finally {
      scope.cancel()
    }
  }

  /**
   * XS5-1, con il collegamento vero: una delle due foto e' tolta dal telefono; l'avviso chiude il nastro subito; la rilettura vera la segna assente nel catalogo, il repository
   * pubblica l'istantanea senza di lei, e solo allora il nastro riapre: con l'altra foto, mai con quella tolta (nessuna emissione dopo l'avviso la mostra).
   */
  @Test
  fun unaFotoToltaDalTelefonoNonRicompareSulNastroDopoLaRilettura() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      val strip = twoPhotosOnTheStrip(scope)
      val log = java.util.concurrent.CopyOnWriteArrayList<AnalysisFlow>()
      scope.launch { strip.source.live.collect { log += it } }
      strip.store.remove("external_primary", 2)

      val noticed = log.size
      strip.phone.gallery.analysisVisibility.contentChanged()
      assertThat(strip.source.live.value.shownIds()).isEmpty()

      val report = strip.phone.gallery.core().phone.sync.syncNow(SyncReason.CONTENT_CHANGE)
      assertThat(report.vanished).isEqualTo(1)
      val back = withTimeout(30_000) { strip.source.live.first { it.shownIds().isNotEmpty() } }
      val kept = back.shownIds().distinct()
      assertThat(kept).hasSize(1)
      val gone = (strip.ids - kept.toSet()).single()
      assertThat(log.drop(noticed).filter { gone in it.shownIds() }).isEmpty()
      assertThat(strip.source.live.value.shownIds()).doesNotContain(gone)
    } finally {
      scope.cancel()
    }
  }

  /**
   * XS5-3, con il collegamento vero: «Nascondi» con la scrittura **lenta** (il gancio di prova ferma l'azione dentro `writing`, prima che la foto esca dalla Libreria e prima del catalogo).
   * Il nastro e' **gia' neutro** dall'inizio dell'azione, con la foto ancora nella Libreria; finita l'azione riapre, con l'altra foto, senza quella nascosta. Mutazione: «Nascondi» senza la
   * transizione (il nastro si azzera solo a cose fatte).
   */
  @Test
  fun nascondiConLaScritturaLentaTieneIlNastroNeutroDallInizioAllaFine() = runBlocking<Unit> {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val strip = twoPhotosOnTheStrip(scope)
      val hidden = strip.ids.first()
      val kept = strip.ids.last()
      val before = strip.source.live.value
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      strip.phone.gallery.hiddenWriteHook = {
        entered.complete(Unit)
        release.await()
      }
      val action = work.launch { strip.phone.gallery.actions.setHidden(setOf(hidden), true) }
      withTimeout(30_000) { entered.await() }

      // La scrittura e' lenta e non e' ancora cominciata: la foto e' ancora nella Libreria, ma il nastro e' gia' neutro e chiuso.
      assertThat(strip.phone.gallery.visibleTimeline.snapshot.value!!.indexOf(hidden)).isAtLeast(0)
      assertThat(strip.phone.gallery.analysisVisibility.canResolve()).isFalse()
      assertThat(strip.source.live.value.shownIds()).isEmpty()
      assertThat(strip.source.live.value.visibilityGeneration).isGreaterThan(before.visibilityGeneration)

      release.complete(Unit)
      withTimeout(30_000) { action.join() }
      val after = withTimeout(30_000) { strip.source.live.first { it.shownIds().isNotEmpty() } }
      assertThat(after.shownIds().distinct()).containsExactly(kept)
      assertThat(strip.phone.gallery.analysisVisibility.canResolve()).isTrue()
    } finally {
      work.cancel()
      scope.cancel()
    }
  }

}
