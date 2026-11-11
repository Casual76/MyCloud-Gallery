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
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * H5, giro di correzione 2: le copertine delle Raccolte.
 *
 *  - **CG-5**: un volto ritagliato e tenuto dalla UI vale solo per la vista in cui il cancello di feature-people l'ha permesso. Alla vista nuova
 *    (una foto nascosta) smette di disegnarsi **subito**, mentre il cancello ci ripensa (qui sospeso), e torna solo se il cancello lo ripermette;
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
  private val face = FaceLocator(ContentKey("%064x".format(1)), 0, FaceBox(10, 10, 90, 90))
  private val person = CollectionEntry("person/1", "Anna", "320 foto", Icons.Rounded.Face, FluidTone.Info, faces = listOf(face))
  private val places = CollectionsSectionSamples.PRODUCTION_PLACES.map { it.first }
  private val mapRow = CollectionEntry("map", "Mappa", "1.240 foto con la posizione", Icons.Rounded.Map, FluidTone.Info, coverPlaces = places)

  private fun bitmap(): Bitmap = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)

  /** Il cancello di prova: risponde con [next] (una risposta pronta, o in attesa finche' la prova non la da'). */
  private class Gate {
    var next: CompletableDeferred<Bitmap?> = CompletableDeferred()
    var asked = 0
    val crops = CollectionFaceCrops { _, _ ->
      asked++
      next.await()
    }
  }

  private fun TestScope.covers(crops: CollectionFaceCrops? = null): CollectionCovers {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return CollectionCovers(
      fakeThumbnailLoader(), world.source.items(LibraryFilter.ALL), crops, dispatcher,
      PassDose(PassDose.PERIOD_MS) { testScheduler.currentTime },
    )
  }

  @Test
  fun unVoltoTenutoDallaUiNonSiDisegnaMentreIlCancelloCiRipensa() = runTest {
    val gate = Gate()
    val covers = covers(gate.crops)
    gate.next.complete(bitmap())
    backgroundScope.launch { covers.refresh(listOf(person), requestThumbs = false, facePx = PX) }
    runCurrent()
    assertWithMessage("permesso per la vista di adesso").that(covers.faceImage(face, PX)).isNotNull()

    // La foto del volto si nasconde: vista nuova. Il cancello non ha ancora risposto (sospeso).
    gate.next = CompletableDeferred()
    world.actions.setHidden(setOf(world.visibleIds.first()), true)
    runCurrent()
    assertThat(gate.asked).isEqualTo(2)
    assertWithMessage("CG-5: il vecchio ritaglio non si disegna mentre il cancello ci ripensa").that(covers.faceImage(face, PX)).isNull()

    // Il cancello rifiuta: resta niente.
    gate.next.complete(null)
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNull()

    // Un'altra vista, e il cancello di nuovo permette: il volto torna.
    gate.next = CompletableDeferred<Bitmap?>().apply { complete(bitmap()) }
    world.actions.setHidden(setOf(world.visibleIds.first()), true)
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNotNull()
  }

  @Test
  fun unElencoNuovoSullaStessaVistaNonTogliIVolti() = runTest {
    val gate = Gate()
    val covers = covers(gate.crops)
    gate.next.complete(bitmap())
    val first = backgroundScope.launch { covers.refresh(listOf(person), requestThumbs = false, facePx = PX) }
    runCurrent()
    first.cancel()
    // I fornitori danno un elenco nuovo (cambia solo un conto): la vista e' la stessa, il permesso vale ancora mentre il cancello riconferma.
    gate.next = CompletableDeferred()
    backgroundScope.launch { covers.refresh(listOf(person.copy(subtitle = "321 foto")), requestThumbs = false, facePx = PX) }
    runCurrent()
    assertThat(covers.faceImage(face, PX)).isNotNull()
  }

  @Test
  fun mentreIlCatalogoCresceILuoghiSiPassanoConLaDoseEArrivaLUltimaVista() = runTest {
    val covers = covers()
    backgroundScope.launch { covers.refresh(listOf(mapRow), requestThumbs = false, facePx = PX) }
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
    val first = backgroundScope.launch { covers.refresh(listOf(mapRow), requestThumbs = false, facePx = PX) }
    runCurrent()
    first.cancel()
    assertThat(covers.placePasses).isEqualTo(1)
    // Un elenco nuovo dei fornitori, stessi luoghi, stessa vista: nessun passaggio nuovo.
    backgroundScope.launch { covers.refresh(listOf(mapRow.copy(subtitle = "1.241 foto con la posizione")), requestThumbs = false, facePx = PX) }
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
