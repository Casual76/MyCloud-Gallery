package dev.pampa.mycloudgallery.feature.library.collections

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Map
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * H5, giri di correzione 2 e 3: le copertine delle Raccolte.
 *
 *  - **CG-5** (giro 2) e **CU-19** (giro 3): un volto tenuto dalla UI porta le sue foto d'origine. Se la foto d'origine esce dalla vista (nascosta)
 *    il volto non si disegna **subito**, anche prima che la raccolta delle viste giri, mentre il cancello ci ripensa (qui sospeso). Se la vista nuova
 *    contiene ancora la foto d'origine (un lotto del backup che cambia i flag di altre celle) il volto **resta** mentre il cancello riconferma: niente
 *    lampo. Il cancello che rifiuta lo toglie; una revoca che non passa dalla vista (cancellazione, Persone spenta) lo toglie subito, con la stessa vista;
 *  - **la dose**: mentre il catalogo cresce, il passaggio dei luoghi non si rifa' a ogni vista: il primo subito, poi al piu' uno al secondo, e
 *    l'ultima vista arriva sempre; lo stesso indice con gli stessi luoghi non si ripassa quando cambia solo l'elenco dei fornitori.
 *
 * `refresh` gira nel `backgroundScope` (non finisce mai): si avanza con `runCurrent`/`advanceTimeBy`, perche' `advanceUntilIdle` non fa girare il
 * lavoro di fondo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CollectionCoversGiro2Test {

  private val world = FakeCollectionsWorld()
  private val ids = world.visibleIds.toList()

  /** La foto da cui e' ritagliato il volto, e due foto qualsiasi che non c'entrano. */
  private val origin = ids[0]
  private val unrelated = ids[1]
  private val unrelated2 = ids[2]
  private val face = FaceLocator(ContentKey("%064x".format(1)), 0, FaceBox(10, 10, 90, 90))
  private val person = CollectionEntry("person/1", "Anna", "320 foto", Icons.Rounded.Face, FluidTone.Info, faces = listOf(face))
  private val places = CollectionsSectionSamples.PRODUCTION_PLACES.map { it.first }
  private val mapRow = CollectionEntry("map", "Mappa", "1.240 foto con la posizione", Icons.Rounded.Map, FluidTone.Info, coverPlaces = places)

  private fun permit(from: MediaId = origin) = CollectionFaceCrop(Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888), setOf(from))

  /** Il cancello di prova: risponde con [next] (una risposta pronta, o in attesa finche' la prova non la da'); [revoked] e' il segno delle revoche. */
  private class Gate {
    var next: CompletableDeferred<CollectionFaceCrop?> = CompletableDeferred()
    var asked = 0
    val revoked = MutableStateFlow(0)
    val crops = object : CollectionFaceCrops {
      override suspend fun crop(face: FaceLocator, sizePx: Int): CollectionFaceCrop? {
        asked++
        return next.await()
      }

      override val revocations: Flow<Any?> get() = revoked
    }

    /** Il cancello non ha ancora risposto alla prossima domanda. */
    fun suspend() {
      next = CompletableDeferred()
    }
  }

  private fun TestScope.covers(crops: CollectionFaceCrops? = null): CollectionCovers {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return CollectionCovers(
      fakeThumbnailLoader(), world.source.items(LibraryFilter.ALL), crops, dispatcher,
      PassDose(PassDose.PERIOD_MS) { testScheduler.currentTime },
    )
  }

  /** Le copertine con il volto gia' permesso per la vista di adesso. */
  private fun TestScope.permitted(gate: Gate): CollectionCovers {
    val covers = covers(gate.crops)
    gate.next.complete(permit())
    backgroundScope.launch { covers.refresh(listOf(person), facePx = PX) }
    runCurrent()
    assertWithMessage("permesso per la vista di adesso").that(covers.faceImage(face, PX)).isNotNull()
    return covers
  }

  /** (1) CG-5: la foto d'origine si nasconde; il volto sparisce subito, prima ancora che la raccolta delle viste giri, e mentre il cancello ci ripensa. */
  @Test
  fun laFotoDOrigineNascostaToglieIlVoltoSubitoMentreIlCancelloCiRipensa() = runTest {
    val gate = Gate()
    val covers = permitted(gate)
    gate.suspend()
    world.actions.setHidden(setOf(origin), true)
    assertWithMessage("CG-5: gia' al primo disegno con la vista nuova").that(covers.faceImage(face, PX)).isNull()
    runCurrent()
    assertThat(gate.asked).isEqualTo(2)
    assertWithMessage("CG-5: il vecchio ritaglio non si disegna mentre il cancello ci ripensa").that(covers.faceImage(face, PX)).isNull()
    // Il cancello rifiuta: resta niente.
    gate.next.complete(null)
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNull()
    // Lo stesso volto in un'altra foto visibile (un'altra copia): il cancello lo permette e torna.
    gate.next = CompletableDeferred<CollectionFaceCrop?>().apply { complete(permit(unrelated2)) }
    world.actions.setHidden(setOf(unrelated), true)
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNotNull()
  }

  /** (2) CU-19: una vista nuova che contiene ancora la foto d'origine (un lotto del backup) non toglie il volto mentre il cancello riconferma. */
  @Test
  fun unaVistaNuovaConLaFotoDOrigineDentroNonFaLampeggiareIlVolto() = runTest {
    val gate = Gate()
    val covers = permitted(gate)
    gate.suspend()
    world.actions.setFavorite(setOf(unrelated), true)
    assertThat(covers.faceImage(face, PX)).isNotNull()
    runCurrent()
    assertThat(gate.asked).isEqualTo(2)
    assertWithMessage("CU-19: resta disegnato mentre il cancello riconferma").that(covers.faceImage(face, PX)).isNotNull()
    gate.next.complete(permit())
    runCurrent()
    assertWithMessage("e resta dopo la riconferma").that(covers.faceImage(face, PX)).isNotNull()
    // Venti lotti di fila: mai un istante senza volto.
    repeat(BURST) { i ->
      gate.next = CompletableDeferred<CollectionFaceCrop?>().apply { complete(permit()) }
      world.actions.setFavorite(setOf(ids[3 + i]), true)
      assertThat(covers.faceImage(face, PX)).isNotNull()
      runCurrent()
      assertThat(covers.faceImage(face, PX)).isNotNull()
    }
  }

  /** (3) Il cancello che rifiuta (la foto d'origine e' ancora nella vista, ma non si puo' piu' mostrare) toglie il volto. */
  @Test
  fun ilCancelloCheRifiutaToglieIlVolto() = runTest {
    val gate = Gate()
    val covers = permitted(gate)
    gate.suspend()
    world.actions.setFavorite(setOf(unrelated), true)
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNotNull()
    gate.next.complete(null)
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNull()
  }

  /** (4) Una revoca che non passa dalla vista (cronologia o profilo azzerati, Persone spenta): con la stessa vista il volto sparisce subito. */
  @Test
  fun unaRevocaConLaStessaVistaToglieIlVoltoSubito() = runTest {
    val gate = Gate()
    val covers = permitted(gate)
    gate.suspend()
    gate.revoked.value = 1
    runCurrent()
    assertThat(gate.asked).isEqualTo(2)
    assertWithMessage("la revoca toglie il volto anche mentre il cancello ci ripensa").that(covers.faceImage(face, PX)).isNull()
    // Il cancello, dopo la revoca, lo permette di nuovo: torna.
    gate.next.complete(permit())
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNotNull()
  }

  @Test
  fun unElencoNuovoSullaStessaVistaNonTogliIVolti() = runTest {
    val gate = Gate()
    val covers = permitted(gate)
    // I fornitori danno un elenco nuovo (cambia solo un conto): la vista e' la stessa, il volto resta mentre il cancello riconferma.
    gate.suspend()
    backgroundScope.launch { covers.refresh(listOf(person.copy(subtitle = "321 foto")), facePx = PX) }
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNotNull()
  }

  @Test
  fun mentreIlCatalogoCresceILuoghiSiPassanoConLaDoseEArrivaLUltimaVista() = runTest {
    val covers = covers()
    backgroundScope.launch { covers.refresh(listOf(mapRow), facePx = PX) }
    runCurrent()
    assertWithMessage("la prima vista passa subito").that(covers.placePasses).isEqualTo(1)

    // Il catalogo che cresce: venti viste in due secondi (una ogni 100 ms).
    val ids = world.visibleIds.toList()
    repeat(BURST) { i ->
      world.actions.setFavorite(setOf(ids[i]), true)
      advanceTimeBy(STEP_MS)
      runCurrent()
    }
    assertWithMessage("al piu' una passata al secondo, non una per vista").that(covers.placePasses).isAtMost(1 + BURST * STEP_MS.toInt() / 1_000 + 1)

    // L'ultima vista cambia la foto di un luogo: si nasconde la piu' recente di Firenze. Subito dopo, dentro la dose, non si ripassa ancora...
    val firenze = places.first()
    val newest = covers.placeCover(firenze)!!
    world.actions.setHidden(setOf(newest), true)
    runCurrent()
    val passes = covers.placePasses
    // ...ma passato il secondo l'ultima vista arriva, sempre.
    advanceTimeBy(PassDose.PERIOD_MS + 1)
    runCurrent()
    assertThat(covers.placePasses).isEqualTo(passes + 1)
    val latest = world.source.items(LibraryFilter.ALL).view.value
    assertThat(covers.placeCover(firenze)).isEqualTo(CollectionCovers.newestByPlace(latest, setOf(firenze))[firenze])
    assertThat(covers.placeCover(firenze)).isNotEqualTo(newest)
  }

  @Test
  fun laStessaVistaConGliStessiLuoghiNonSiRipassa() = runTest {
    val covers = covers()
    val first = backgroundScope.launch { covers.refresh(listOf(mapRow), facePx = PX) }
    runCurrent()
    first.cancel()
    assertThat(covers.placePasses).isEqualTo(1)
    // Un elenco nuovo dei fornitori, stessi luoghi, stessa vista: nessun passaggio nuovo.
    backgroundScope.launch { covers.refresh(listOf(mapRow.copy(subtitle = "1.241 foto con la posizione")), facePx = PX) }
    advanceTimeBy(PassDose.PERIOD_MS * 3)
    runCurrent()
    assertThat(covers.placePasses).isEqualTo(1)
    assertThat(covers.placeCover(places.first())).isNotNull()
  }

  private companion object {
    const val PX = 44
    const val BURST = 20
    const val STEP_MS = 100L
  }
}
