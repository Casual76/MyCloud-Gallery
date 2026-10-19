package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.backup.testing.ScriptedCorrections
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTasksActions
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTasksScreen
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTasksUi
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionDraft
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionMode
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSheetActions
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSheetContent
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSubject
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionUi
import dev.pampa.mycloudgallery.feature.backup.versions.PlaceChoice
import dev.pampa.mycloudgallery.feature.backup.versions.PlaceEdit
import dev.pampa.mycloudgallery.feature.backup.versions.VersionSamples
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsSheetActions
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsSheetContent
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsUi
import dev.pampa.mycloudgallery.feature.backup.versions.build
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking

// Il TEST DEI BORDI delle schermate della 5B (`docs/grafica-progetto.md` §3, P2 e P3; riga P12 del §4.4), una volta sola per telefono e tablet:
// con il testo a 1x e a 1,3x ogni controllo toccabile e' almeno 48 x 48 dp, sta dentro lo schermo e nessuno ne tocca un altro (salvo chi ne
// contiene un altro). Il vetro non si vede (Robolectric non sfoca): la leggibilita' dei fogli e' dei componenti del design system e le misure
// della chrome sono quelle di G2 (§12.6).

/** Cio' che serve per provare i bordi su un telefono o su un tablet: la scena, il testo grande, l'attesa e le misure. */
internal interface LayoutHarness {
  fun mount(content: @Composable () -> Unit)

  fun fontScale(value: Float)

  fun onUi(block: () -> Unit)

  fun idle()

  /** I nodi toccabili con il nome che TalkBack legge. */
  fun clickables(): List<Pair<SemanticsNode, String>>

  fun rootBounds(): Rect

  fun minTargetPx(): Float

  fun pxOf(dp: Float): Float
}

private fun Rect.overlaps(other: Rect): Boolean = left < other.right - 1f && other.left < right - 1f && top < other.bottom - 1f && other.top < bottom - 1f

private fun Rect.contains(other: Rect): Boolean = left <= other.left + 1f && top <= other.top + 1f && right >= other.right - 1f && bottom >= other.bottom - 1f

/** Ogni nodo toccabile e' almeno 48 dp, dentro lo schermo, e nessuna coppia si sovrappone se non per contenimento. */
internal fun LayoutHarness.assertBounds(where: String) {
  val min = minTargetPx()
  val root = rootBounds()
  val nodes = clickables()
  for ((node, name) in nodes) {
    val b = node.boundsInRoot
    assertWithMessage("$where: $name $b ha un nome").that(name).isNotEmpty()
    assertWithMessage("$where: $name $b alto almeno 48 dp").that(b.height).isAtLeast(min - 1f)
    assertWithMessage("$where: $name $b largo almeno 48 dp").that(b.width).isAtLeast(min - 1f)
    assertWithMessage("$where: $name $b dentro lo schermo $root").that(b.left >= root.left - 1f && b.right <= root.right + 1f).isTrue()
  }
  for (i in nodes.indices) {
    for (j in i + 1 until nodes.size) {
      val a = nodes[i].first.boundsInRoot
      val b = nodes[j].first.boundsInRoot
      if (a.contains(b) || b.contains(a)) continue
      assertWithMessage("$where: ${nodes[i].second} $a e ${nodes[j].second} $b si sovrappongono").that(a.overlaps(b)).isFalse()
    }
  }
}

private val FONT_SCALES = listOf(1f, 1.3f)

/** Prova ogni stato di [states] a testo normale e ingrandito. [scene] legge lo stato dalla riga che gli passa la suite. */
private fun <T> LayoutHarness.eachState(name: String, states: List<Pair<String, T>>, scene: @Composable (T) -> Unit) {
  val current = mutableStateOf(states.first().second)
  mount { scene(current.value) }
  for (scale in FONT_SCALES) {
    fontScale(scale)
    for ((label, state) in states) {
      onUi { current.value = state }
      idle()
      assertBounds("$name, $label, ${scale}x")
    }
  }
}

internal object FiveBLayoutSuite {

  private val subject = CorrectionSubject(
    mediaId = VersionSamples.media,
    takenAt = LocalDateTime.of(2026, 10, 12, 14, 32),
    offsetMinutes = 120,
    placeLabel = "Oslo",
    hasLocation = true,
  )

  // --- Libera spazio -------------------------------------------------------------------------------------------------------------------

  fun freeSpacePage(h: LayoutHarness) {
    val summary = FreeSpaceUi.Summary(FreeSpaceSamples.plan(excluded = FreeSpaceSamples.excludedSample, needsGuardian = 40), FreeSpaceKind.ALL)
    val states: List<Pair<String, FreeSpaceUi>> = listOf(
      "riepilogo" to summary,
      "riepilogo vuoto" to FreeSpaceUi.Summary(FreeSpaceSamples.plan(items = 0), FreeSpaceKind.VIDEOS_ONLY),
      "conta" to FreeSpaceUi.Idle,
      "guardiano" to FreeSpaceUi.Guarding(12, 40),
      "verifica" to FreeSpaceUi.Verifying(340, 1_240, 4_200_000_000L),
      "pronto" to FreeSpaceUi.Ready(0, 3, 4_500, 4_500_000_000L, permanent = false, validUntilMonotonicMs = 21_600_000L),
      "pronto definitivo" to FreeSpaceUi.Ready(1, 3, 2_500, 2_500_000_000L, permanent = true, validUntilMonotonicMs = null),
      "dialogo aperto" to FreeSpaceUi.Confirming(2, 3, permanent = false),
      "fatto" to FreeSpaceUi.Done(1_240, 18_600_000_000L, toAndroidTrash = true, kept = 30, dropped = 10, cancelled = false),
      "fatto, niente" to FreeSpaceUi.Done(0, 0, toAndroidTrash = true, kept = 0, dropped = 12, cancelled = true),
    ) + FreeSpaceBlockedBy.entries.map { "blocco ${it.name}" to FreeSpaceUi.Blocked(it) }
    h.eachState("Libera spazio", states) { state: FreeSpaceUi ->
      FreeSpaceScreen(state = state, sheet = FreeSheet.None, taps = null, actions = FreeSpaceActions(), nowMs = 0L, onBack = {})
    }
  }

  fun freeSpaceSheets(h: LayoutHarness) {
    var now = 10_000L
    val normal = FreeSpaceSamples.plan(items = 3)
    val permanent = FreeSpaceSamples.plan(items = 3, permanent = true)
    val normalTaps = ConfirmTaps(normal) { now }
    val permanentTaps = ConfirmTaps(permanent) { now }
    val sheets = listOf<Pair<String, @Composable () -> Unit>>(
      "libera spazio" to { FreeConfirmContent(normal, normalTaps, {}, {}) },
      "libera subito, primo gesto" to { FreeConfirmNowContent(permanent, permanentTaps, {}, {}) },
    )
    h.eachState("Fogli di Libera spazio", sheets) { sheet: @Composable () -> Unit -> sheet() }
    // Il secondo pannello di "Libera subito" compare dopo il primo gesto.
    now += PhoneConfirmationRules.MIN_TAP_GAP_MS + 50
    h.onUi { permanentTaps.tap() }
    h.idle()
    h.assertBounds("Libera subito, secondo gesto")
  }

  fun phoneOnlySheet(h: LayoutHarness) {
    fun advice(vararg values: PhoneOnlyAdvice) = PhoneOnlyAssessment(values.withIndex().associate { (i, a) -> FreeSpaceSamples.mediaId(i + 1) to a })
        .let { PhoneOnlyUi.Advice(it, safeFirst = it.preselectSafeFirst) }
    val states: List<Pair<String, PhoneOnlyUi>> = listOf(
      "da liberare" to advice(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.GUARD_FIRST),
      "non al sicuro" to advice(PhoneOnlyAdvice.SAFE_FIRST),
      "misto" to advice(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.SAFE_FIRST, PhoneOnlyAdvice.WAIT, PhoneOnlyAdvice.FROZEN),
      "tutti i consigli" to advice(*PhoneOnlyAdvice.entries.toTypedArray()),
    )
    h.eachState("Elimina solo dal telefono", states) { state: PhoneOnlyUi ->
      PhoneOnlyContent(state = state, onChoose = {}, onSafeguard = {}, onContinue = {}, onClose = {})
    }
  }

  // --- Versioni --------------------------------------------------------------------------------------------------------------------------

  fun versionsSheet(h: LayoutHarness) {
    val plan = VersionSamples.plan()
    var now = 10_000L
    val taps = ConfirmTaps(plan) { now }
    val states: List<Pair<String, VersionsUi>> = listOf(
      "lista" to VersionsUi.Listing(plan, canView = true, canDeleteIntermediates = true),
      "lista con avviso" to VersionsUi.Listing(plan, canView = false, canDeleteIntermediates = false, message = dev.pampa.mycloudgallery.feature.backup.versions.VersionsMessage.PLAN_CHANGED),
      "riepilogo" to VersionsUi.ConfirmRestore(plan),
      "permesso" to VersionsUi.AwaitingSystemDialog(plan, 1),
      "scambio" to VersionsUi.Restoring(plan),
      "fatto" to VersionsUi.Restored(plan, phoneKeptEdited = true),
      "caricamento" to VersionsUi.Loading,
      "non pronto" to VersionsUi.Unavailable,
    )
    now += 100
    h.eachState("Versioni", states) { state: VersionsUi ->
      VersionsSheetContent(state = state, taps = taps, actions = VersionsSheetActions())
    }
  }

  // --- Correzioni --------------------------------------------------------------------------------------------------------------------------

  private fun editing(draft: CorrectionDraft, mode: CorrectionMode, withPlan: Boolean, canSearch: Boolean = false, places: List<PlaceChoice> = emptyList()) =
    CorrectionUi.Editing(
      subject = subject,
      mode = mode,
      draft = draft,
      errors = emptySet(),
      plan = if (withPlan) draft.build(subject, mode).request?.let { request -> runBlocking { ScriptedCorrections().plan(VersionSamples.media, request) } } else null,
      places = places,
      canSearchPlaces = canSearch,
    )

  fun correctionSheet(h: LayoutHarness) {
    val start = CorrectionDraft.startingFrom(subject)
    val results = listOf(PlaceChoice("Bergen", GeoPoint(60.39, 5.32)), PlaceChoice("Berlino", GeoPoint(52.52, 13.4)))
    val full = start.copy(changeDate = true, dateText = "03/10/2026", timeText = "10:00", place = PlaceEdit.CHANGE, placeQuery = "Ber", chosenPlace = results.first(), quarterTurns = 1)
    val states: List<Pair<String, CorrectionUi>> = listOf(
      "chiuso" to editing(start, CorrectionMode.ALL, withPlan = false),
      "tutto acceso" to editing(full, CorrectionMode.ALL, withPlan = true, canSearch = true, places = results),
      "solo la data" to editing(start.copy(changeDate = true), CorrectionMode.ALL, withPlan = false),
      "toglie il luogo" to editing(start.copy(place = PlaceEdit.CLEAR), CorrectionMode.ALL, withPlan = true),
      "ruota" to editing(start.copy(quarterTurns = 3), CorrectionMode.ROTATE, withPlan = true),
    )
    val others: List<Pair<String, CorrectionUi>> = listOf(
      "prima al sicuro" to CorrectionUi.NeedsSafeFirst(subject),
      "permesso" to CorrectionUi.AwaitingSystemDialog(1),
      "scrittura" to CorrectionUi.Writing,
      "carica" to CorrectionUi.Uploading(false),
      "fatto" to CorrectionUi.Done(false),
      "solo nell'app" to CorrectionUi.Done(true),
      "guasto" to CorrectionUi.Failed(dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure.PHONE_NOT_RESTORED),
      "blocco" to CorrectionUi.Blocked(CorrectionReadiness.COMMON_AREA_NOTICE),
      "non pronto" to CorrectionUi.Unavailable,
    )
    var now = 10_000L
    h.eachState("Correggi", states + others) { state: CorrectionUi ->
      val taps = (state as? CorrectionUi.Editing)?.plan?.let { ConfirmTaps(it) { now } }
      CorrectionSheetContent(state = state, taps = taps, actions = CorrectionSheetActions())
    }
    now += 1
  }

  // --- Da decidere -------------------------------------------------------------------------------------------------------------------------

  fun pendingTasks(h: LayoutHarness) {
    fun task(id: Long) = dev.pampa.mycloudgallery.core.backup.contract.PendingTask(
      id = dev.pampa.mycloudgallery.core.backup.contract.PendingTaskId(id),
      kind = dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind.EDITED_VIDEO_ORIGINAL,
      state = dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState.OPEN,
      mediaId = FreeSpaceSamples.mediaId(id.toInt()),
      versionId = FreeSpaceSamples.versionId(id.toInt()),
      bytes = 1_200_000_000L,
      createdAtNasMillis = 1_792_067_520_000L,
    )
    val states = listOf(
      "vuota" to PendingTasksUi(emptyList(), canDeleteOriginal = true),
      "due" to PendingTasksUi(listOf(task(1), task(2)), canDeleteOriginal = true),
      "senza il tasto elimina" to PendingTasksUi(listOf(task(1)), canDeleteOriginal = false),
    )
    h.eachState("Da decidere", states) { state: PendingTasksUi ->
      PendingTasksScreen(state = state, actions = PendingTasksActions(), onBack = {})
    }
  }

  // --- Un foglio che non ci sta tutto: i pulsanti restano sempre in vista --------------------------------------------------------------

  /**
   * Il foglio dell'engine e' alto al piu' 640 dp (`GallerySheetDefaults.MaxHeight`) e non scorre da se': il corpo scorre e i pulsanti in fondo
   * restano in vista, anche con il testo a 1,3x e tutte le sezioni aperte.
   */
  fun biggestSheetsKeepTheirButtonsInView(h: LayoutHarness) {
    val start = CorrectionDraft.startingFrom(subject)
    val bergen = PlaceChoice("Bergen", GeoPoint(60.39, 5.32))
    val full = start.copy(changeDate = true, dateText = "03/10/2026", timeText = "10:00", place = PlaceEdit.CHANGE, placeQuery = "Ber", chosenPlace = bergen, quarterTurns = 1)
    val correction = editing(full, CorrectionMode.ALL, withPlan = true, canSearch = true, places = listOf(bergen))
    val taps = ConfirmTaps(correction.plan!!) { 20_000L }
    val boxHeight = 640f
    h.mount {
      Box(Modifier.height(boxHeight.dp)) { CorrectionSheetContent(state = correction, taps = taps, actions = CorrectionSheetActions()) }
    }
    h.fontScale(1.3f)
    h.idle()
    val box = h.rootBounds().let { Rect(it.left, it.top, it.right, it.top + h.pxOf(boxHeight)) }
    val nodes = h.clickables()
    val apply = nodes.firstOrNull { it.second == "Correggi" }
    assertThat(apply).isNotNull()
    assertThat(apply!!.first.boundsInRoot.bottom).isAtMost(box.bottom + 1f)
    val cancel = nodes.first { it.second == "Annulla" }
    assertThat(cancel.first.boundsInRoot.bottom).isAtMost(box.bottom + 1f)
  }
}
