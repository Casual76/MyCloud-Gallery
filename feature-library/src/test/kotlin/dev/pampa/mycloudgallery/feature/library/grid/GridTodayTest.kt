package dev.pampa.mycloudgallery.feature.library.grid

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.imaging.testing.FakePlaceholderCache
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.layout.TimelineLayoutEngine
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineView
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.places.testing.FakePlaces
import java.time.LocalDate
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FAB-1 della review generale: **"Oggi" e "Ieri" restavano quelli del giorno in cui la Libreria era stata composta**. Con l'app rimasta viva dalla
 * sera prima, la mattina le foto di ieri stavano ancora sotto "Oggi" (la griglia leggeva `today()` una volta con `remember(state)`).
 *
 * Ora "oggi" e' uno stato della griglia: si rilegge quando la finestra torna in primo piano (`ON_START`) e a ogni vista nuova, e le intestazioni e
 * l'etichetta di vetro in alto lo leggono da li'. Il test porta la giornata avanti **dopo** che la griglia e' composta e fa tornare la finestra in
 * primo piano: le foto di quel giorno passano da "Oggi" a "Ieri", senza ricomporre la griglia a ogni fotogramma.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
class GridTodayTest {

  val composeRule = createComposeRule()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(RegisterComponentActivity()).around(composeRule)

  private val padding = PaddingValues(top = 100.dp, bottom = 120.dp)

  /** Una finestra di cui il test comanda il ciclo di vita, come il sistema che la porta in secondo piano e poi la riporta davanti. */
  private class Window : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    fun resume() {
      registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
      registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
      registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun toBackgroundAndBack() {
      registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
      registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
      registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
      registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
  }

  /** [count] foto tutte dello stesso giorno [day] (`aaaammgg`): una sola intestazione, quella del giorno. */
  private fun oneDay(day: Int, count: Int): InMemoryTimelineSnapshot {
    val msb = LongArray(count) { it + 1L }
    val lsb = LongArray(count) { 0x7007L }
    return InMemoryTimelineSnapshot(
      seq = 1,
      idMsb = msb,
      idLsb = lsb,
      sortTime = LongArray(count) { 1_760_000_000_000L - it * 1_000L },
      localDay = IntArray(count) { day },
      kinds = ByteArray(count) { MediaKind.PHOTO.ordinal.toByte() },
      sources = ByteArray(count) { MediaSource.CAMERA.ordinal.toByte() },
      flags = IntArray(count),
      aspectMilli = IntArray(count) { 1333 },
      durationMs = LongArray(count),
      place = IntArray(count),
      groupSize = IntArray(count) { 1 },
    )
  }

  private fun gridOn(snapshot: InMemoryTimelineSnapshot, today: () -> LocalDate): MediaGridState = MediaGridState(
    view = InMemoryTimelineView(snapshot, LibraryFilter.ALL),
    loader = ControlledThumbnailLoader(),
    placeholders = FakePlaceholderCache(),
    placeNames = FakePlaces(),
    engine = TimelineLayoutEngine(),
    refOf = { MediaRef.Synthetic(1L, 100, 100) },
    level = GridLevel.DAYS,
    today = today,
  )

  private fun toTop(state: MediaGridState) {
    composeRule.runOnIdle { state.userScrolled = true; state.listState.requestScrollToItem(0, 0) }
    composeRule.waitForIdle()
  }

  @Test
  fun conLAppVivaDaUnGiornoALAltroOggiDiventaIeriQuandoLaFinestraTornaDavanti() {
    val day = LocalDate.of(2025, 10, 9)
    var now = day
    val state = gridOn(oneDay(day = 20251009, count = 30), today = { now })
    val window = Window().also { it.resume() }
    var label: MediaGridSection? = null
    composeRule.showThemed {
      CompositionLocalProvider(LocalLifecycleOwner provides window) {
        MediaGrid(state = state, contentPadding = padding)
        // L'etichetta di vetro in alto legge la sezione sotto la sua linea: lo stesso "oggi" delle intestazioni.
        val section = rememberMediaGridSection(state, line = 300.dp)
        SideEffect { label = section }
      }
    }
    toTop(state)
    composeRule.onAllNodesWithText("Oggi").onFirst().assertExists()
    composeRule.onAllNodesWithText("Ieri").assertCountEquals(0)
    assertThat(label?.title).isEqualTo("Oggi")

    // La mattina dopo: l'app e' ancora viva, il giorno e' cambiato, la finestra torna in primo piano.
    now = day.plusDays(1)
    composeRule.runOnUiThread { window.toBackgroundAndBack() }
    composeRule.waitForIdle()

    composeRule.onAllNodesWithText("Ieri").onFirst().assertExists()
    composeRule.onAllNodesWithText("Oggi").assertCountEquals(0)
    assertThat(label?.title).isEqualTo("Ieri")
  }

  @Test
  fun unaVistaNuovaRileggeIlGiornoSenzaAspettareLaFinestra() {
    val day = LocalDate.of(2025, 10, 9)
    var now = day
    val snapshot = oneDay(day = 20251009, count = 30)
    val state = gridOn(snapshot, today = { now })
    val window = Window().also { it.resume() }
    composeRule.showThemed {
      CompositionLocalProvider(LocalLifecycleOwner provides window) { MediaGrid(state = state, contentPadding = padding) }
    }
    toTop(state)
    composeRule.onAllNodesWithText("Oggi").onFirst().assertExists()

    // Il giorno cambia e arriva una vista nuova (una foto, una modifica del catalogo): "Ieri" subito, senza che la finestra sia andata via.
    now = day.plusDays(1)
    composeRule.runOnUiThread { state.view = InMemoryTimelineView(snapshot, LibraryFilter.ALL) }
    composeRule.waitForIdle()
    toTop(state)

    composeRule.onAllNodesWithText("Ieri").onFirst().assertExists()
  }

  /** Il valore cambia una volta al giorno: finche' e' lo stesso, nessuna riga si ricompone (niente lavoro a ogni fotogramma). */
  @Test
  fun fincheIlGiornoELoStessoNessunaRigaSiRicompone() {
    val day = LocalDate.of(2025, 10, 9)
    val state = gridOn(oneDay(day = 20251009, count = 30), today = { day })
    val probe = CountingProbe()
    state.probe = probe
    val window = Window().also { it.resume() }
    composeRule.showThemed {
      CompositionLocalProvider(LocalLifecycleOwner provides window) { MediaGrid(state = state, contentPadding = padding) }
    }
    toTop(state)
    val rowsBefore = probe.snapshotRows()

    composeRule.runOnUiThread { window.toBackgroundAndBack() }
    composeRule.waitForIdle()

    assertThat(probe.snapshotRows()).isEqualTo(rowsBefore)
  }
}
