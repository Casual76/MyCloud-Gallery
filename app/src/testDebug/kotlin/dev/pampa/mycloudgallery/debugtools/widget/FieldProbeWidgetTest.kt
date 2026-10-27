package dev.pampa.mycloudgallery.debugtools.widget

import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.R
import dev.pampa.mycloudgallery.core.library.noop.EmptyTimelineSnapshot
import dev.pampa.mycloudgallery.core.library.testing.FakeVisibility
import dev.pampa.mycloudgallery.core.library.testing.SyntheticLibrary
import dev.pampa.mycloudgallery.core.library.visibility.RotatingWidgetFeed
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fase 7 (P9, D22, L65, F73): il widget di prova dei Nascosti (solo debug). Mostra solo cio' che gli da' `WidgetFeed`, e `WidgetFeed` non da' mai un
 * nascosto (ne' un membro di un gruppo con un nascosto, ne' un media nascosto un attimo fa); le descrizioni per TalkBack non portano id ne' nomi di
 * file; il manifest di debug lo dichiara non esportato.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FieldProbeWidgetTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val library = SyntheticLibrary(count = 30, seed = 3)
  private val snapshot = library.snapshot
  private val ids: List<MediaId> = (0 until snapshot.size).map { snapshot.idAt(it) }

  private fun visibility(hidden: Set<MediaId> = emptySet(), groups: List<Set<MediaId>> = emptyList()) =
    FakeVisibility(snapshot = snapshot, present = ids.toSet(), hidden = hidden, groups = groups)

  @Test
  fun ilWidgetMostraSoloQuelloCheDaWidgetFeedEMaiUnNascosto() = runBlocking<Unit> {
    val group = setOf(ids[2], ids[3])
    val v = visibility(hidden = setOf(ids[0], ids[3]) + ids[7], groups = listOf(group))
    val feed = RotatingWidgetFeed(v, v)
    val forbidden = setOf(ids[0], ids[2], ids[3], ids[7])

    val shown = HashSet<MediaId>()
    repeat(12) { shown += FieldProbeWidgetContent.cells(feed, snapshot) { null }.map { it.id } }

    assertThat(shown).containsNoneIn(forbidden)
    assertThat(shown).containsExactlyElementsIn(ids - forbidden)
  }

  @Test
  fun ilWidgetNonMostraUnMediaNascostoDopoL_Istantanea() = runBlocking<Unit> {
    val v = visibility()
    val feed = RotatingWidgetFeed(v, v)
    assertThat(FieldProbeWidgetContent.cells(feed, snapshot) { null }.map { it.id }).containsExactlyElementsIn(ids.take(FieldProbeWidgetContent.CELLS))

    v.hidden += ids[FieldProbeWidgetContent.CELLS] // nascosto un attimo fa: l'istantanea del widget ce l'ha ancora

    val next = FieldProbeWidgetContent.cells(feed, snapshot) { null }.map { it.id }
    assertThat(next).doesNotContain(ids[FieldProbeWidgetContent.CELLS])
  }

  @Test
  fun senzaIstantaneaOSenzaCelleNienteDaMostrare() = runBlocking<Unit> {
    val v = visibility()
    val feed = RotatingWidgetFeed(v, v)
    assertThat(FieldProbeWidgetContent.cells(feed, null) { null }).isEmpty()
    assertThat(FieldProbeWidgetContent.cells(RotatingWidgetFeed(FakeVisibility(snapshot = EmptyTimelineSnapshot), v), EmptyTimelineSnapshot) { null }).isEmpty()
  }

  @Test
  fun ogniCellaHaUnaDescrizioneChePartecipaTipoEDataSenzaIdNeNomiDiFile() {
    val cell = FieldProbeWidgetCell(ids[0], MediaKind.PHOTO, LocalDate.of(2024, 3, 12), ref = null)
    val video = FieldProbeWidgetCell(ids[1], MediaKind.VIDEO, LocalDate.of(2024, 3, 12), ref = null)
    val photoText = context.getString(R.string.probe_widget_photo)
    val videoText = context.getString(R.string.probe_widget_video)

    assertThat(cell.description(photoText, videoText)).isEqualTo("Foto, 12 marzo 2024")
    assertThat(video.description(photoText, videoText)).isEqualTo("Video, 12 marzo 2024")
    assertThat(cell.toString()).doesNotContain(ids[0].value)
    assertThat(FieldProbeWidgetContent.dayOf(0)).isEqualTo(LocalDate.of(1970, 1, 1))
  }

  /** Le viste vere (RemoteViews in un FrameLayout): le celle senza elemento spariscono e la descrizione e' quella data. */
  private fun inflate(cells: List<FieldProbeRenderedCell>): View = FieldProbeWidgetViews.build(context, cells).apply(context, FrameLayout(context))

  private fun View.cell(i: Int): ImageView = findViewById(
    intArrayOf(R.id.probe_widget_cell_0, R.id.probe_widget_cell_1, R.id.probe_widget_cell_2, R.id.probe_widget_cell_3)[i],
  )

  @Test
  fun leCelleSiDisegnanoConLaLoroDescrizioneEQuelleSenzaElementoSpariscono() {
    val root = inflate(listOf(FieldProbeRenderedCell("Foto, 1 marzo 2024", null), FieldProbeRenderedCell("Video, 2 marzo 2024", null)))

    assertThat(root.cell(0).visibility).isEqualTo(View.VISIBLE)
    assertThat(root.cell(0).contentDescription.toString()).isEqualTo("Foto, 1 marzo 2024")
    assertThat(root.cell(1).contentDescription.toString()).isEqualTo("Video, 2 marzo 2024")
    assertThat(root.cell(2).visibility).isEqualTo(View.INVISIBLE)
    assertThat(root.cell(3).visibility).isEqualTo(View.INVISIBLE)
    assertThat(root.findViewById<TextView>(R.id.probe_widget_empty).visibility).isEqualTo(View.GONE)
  }

  @Test
  fun senzaCelleDiceNienteDaMostrare() {
    val root = inflate(emptyList())

    assertThat(root.findViewById<TextView>(R.id.probe_widget_empty).visibility).isEqualTo(View.VISIBLE)
    assertThat(root.findViewById<TextView>(R.id.probe_widget_empty).text.toString()).isEqualTo("Niente da mostrare")
    assertThat(root.findViewById<ViewGroup>(R.id.probe_widget_cells).visibility).isEqualTo(View.GONE)
  }

  @Test
  fun alPiuQuattroCelle() {
    val six = (1..6).map { FieldProbeRenderedCell("Foto $it", null) }
    val root = inflate(six)

    assertThat((0..3).map { root.cell(it).contentDescription.toString() }).containsExactly("Foto 1", "Foto 2", "Foto 3", "Foto 4").inOrder()
  }

  /** Il ricevitore sta solo nel manifest di debug e non e' esportato: lo raggiunge il sistema dei widget, nessun'altra app. */
  @Test
  fun ilRicevitoreEDichiaratoNonEsportatoSoloNelManifestDiDebug() {
    val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle").isFile }
    val debug = File(root, "app/src/debug/AndroidManifest.xml").readText()
    val main = File(root, "app/src/main/AndroidManifest.xml").readText()
    val block = debug.substringAfter("FieldProbeHiddenWidgetProvider").substringBefore("</receiver>")

    assertThat(block).contains("android:exported=\"false\"")
    assertThat(block).contains("android.appwidget.action.APPWIDGET_UPDATE")
    assertThat(main).doesNotContain("appwidget")
  }

  // --- NA-2 (revisione di Fase 7): il widget a processo freddo --------------------------------------------------------------------

  @Test
  fun na2_AProcessoFreddoLIstantaneaSiAspetta_NonSiRispondeVuoto() = kotlinx.coroutines.test.runTest {
    // Il sistema dei widget sveglia l'app solo per ridisegnare: la Libreria non e' ancora letta, l'istantanea e' null.
    val flow = kotlinx.coroutines.flow.MutableStateFlow<dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot?>(null)

    val waiting = backgroundScope.let { scope -> scope.async { FieldProbeWidgetContent.awaitSnapshot(flow, waitMs = 8_000) } }
    testScheduler.advanceTimeBy(3_000)
    testScheduler.runCurrent()
    assertThat(waiting.isCompleted).isFalse()

    // L'istantanea arriva mentre si aspetta: il widget si disegna con quella, senza aspettare il giro dopo.
    flow.value = snapshot
    testScheduler.runCurrent()
    assertThat(waiting.await()).isSameInstanceAs(snapshot)
  }

  @Test
  fun na2_SeLIstantaneaNonArrivaMai_DopoIlTettoSiRispondeNullEIlWidgetRestaSenzaCelle() = kotlinx.coroutines.test.runTest {
    val flow = kotlinx.coroutines.flow.MutableStateFlow<dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot?>(null)

    val result = backgroundScope.async { FieldProbeWidgetContent.awaitSnapshot(flow, waitMs = 8_000) }
    testScheduler.advanceTimeBy(8_001)
    testScheduler.runCurrent()

    assertThat(result.await()).isNull()
    // Senza istantanea nessuna cella: mai un elemento che non si e' potuto filtrare.
    val v = visibility()
    assertThat(FieldProbeWidgetContent.cells(RotatingWidgetFeed(v, v), null) { null }).isEmpty()
  }

  @Test
  fun na2_ConLIstantaneaGiaPronta_SiRispondeSubito() = kotlinx.coroutines.test.runTest {
    val flow = kotlinx.coroutines.flow.MutableStateFlow<dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot?>(snapshot)

    assertThat(FieldProbeWidgetContent.awaitSnapshot(flow, waitMs = 8_000)).isSameInstanceAs(snapshot)
  }
}
