package dev.pampa.mycloudgallery.feature.backup.free

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionFailure
import dev.pampa.mycloudgallery.core.backup.contract.CorrectionReadiness
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskId
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationRules
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.VersionArea
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAdvice
import dev.pampa.mycloudgallery.core.backup.free.PhoneOnlyAssessment
import dev.pampa.mycloudgallery.core.backup.testing.ScriptedCorrections
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.feature.backup.BackupComposeBase
import dev.pampa.mycloudgallery.feature.backup.LARGE_TEXT
import dev.pampa.mycloudgallery.feature.backup.PHONE_QUALIFIERS
import dev.pampa.mycloudgallery.feature.backup.TABLET_QUALIFIERS
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTasksActions
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTasksScreen
import dev.pampa.mycloudgallery.feature.backup.tasks.PendingTasksUi
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionDraft
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionMode
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSheet
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSheetActions
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSheetContent
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionSubject
import dev.pampa.mycloudgallery.feature.backup.versions.CorrectionUi
import dev.pampa.mycloudgallery.feature.backup.versions.PlaceChoice
import dev.pampa.mycloudgallery.feature.backup.versions.PlaceEdit
import dev.pampa.mycloudgallery.feature.backup.versions.VersionSamples
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsSheet
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsSheetActions
import dev.pampa.mycloudgallery.feature.backup.versions.VersionsUi
import dev.pampa.mycloudgallery.feature.backup.versions.build
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P12 per le schermate della 5B in feature-backup: "Libera spazio" (pagina e fogli), "Elimina solo dal telefono", le versioni, "Correggi"
 * e "Da decidere". Chiaro e scuro, telefono (412 dp) e tablet (1280 dp), piu' il testo grande (1,3x) per le schermate principali. Sui dati dei test.
 *
 * `gradlew :feature-backup:recordRoborazziDebug --tests "*FiveB*Shots*"` scrive i PNG in `docs/screenshots/5-P12/`. Nei normali `testDebugUnitTest` si
 * verifica solo che le schermate compongano. Le pagine si fotografano in una finestra alta (scorrono); i fogli in una di altezza normale.
 * Le voci del menu del viewer, i fogli della condivisione e le Impostazioni hanno i loro screenshot nella stessa cartella (feature-viewer, feature-settings).
 */
abstract class FiveBShotBase(private val dark: Boolean, private val device: String) : BackupComposeBase() {

  private val look: String get() = if (dark) "scuro" else "chiaro"

  internal fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  /** Il viewer di sfondo di un foglio che si apre sopra una foto: una pagina scura come una foto a tutta pagina, che il foglio sfoca. */
  @Composable
  internal fun Behind() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
  }

  // --- I dati ----------------------------------------------------------------------------------------------------------------------

  internal val summary: FreeSpaceUi.Summary =
    FreeSpaceUi.Summary(FreeSpaceSamples.plan(excluded = FreeSpaceSamples.excludedSample, needsGuardian = 40), FreeSpaceKind.ALL)

  internal val emptySummary: FreeSpaceUi.Summary =
    FreeSpaceUi.Summary(FreeSpaceSamples.plan(items = 0, excluded = mapOf(dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion.RECENT to 214)), FreeSpaceKind.VIDEOS_ONLY)

  @Composable
  internal fun FreePage(state: FreeSpaceUi, sheet: FreeSheet = FreeSheet.None, taps: ConfirmTaps? = null) {
    FreeSpaceScreen(state = state, sheet = sheet, taps = taps, actions = FreeSpaceActions(), nowMs = 120_000L, onBack = {})
  }

  internal fun task(id: Long) = PendingTask(
    id = PendingTaskId(id),
    kind = PendingTaskKind.EDITED_VIDEO_ORIGINAL,
    state = PendingTaskState.OPEN,
    mediaId = FreeSpaceSamples.mediaId(id.toInt()),
    versionId = FreeSpaceSamples.versionId(id.toInt()),
    bytes = 1_200_000_000L + id * 300_000_000L,
    createdAtNasMillis = 1_792_067_520_000L - id * 86_400_000L,
  )

  private val subject = CorrectionSubject(
    mediaId = VersionSamples.media,
    takenAt = LocalDateTime.of(2026, 10, 12, 14, 32),
    offsetMinutes = 120,
    placeLabel = "Oslo",
    hasLocation = true,
  )

  internal val bergen = PlaceChoice("Bergen", GeoPoint(60.39, 5.32))

  internal fun editing(draft: CorrectionDraft, mode: CorrectionMode = CorrectionMode.ALL, withPlan: Boolean = false, canSearch: Boolean = false, places: List<PlaceChoice> = emptyList()) =
    CorrectionUi.Editing(
      subject = subject,
      mode = mode,
      draft = draft,
      errors = emptySet(),
      plan = if (withPlan) draft.build(subject, mode).request?.let { runBlocking { ScriptedCorrections().plan(VersionSamples.media, it) } } else null,
      places = places,
      canSearchPlaces = canSearch,
    )

  internal fun start(): CorrectionDraft = CorrectionDraft.startingFrom(subject)

  internal fun fullDraft(): CorrectionDraft =
    start().copy(changeDate = true, dateText = "03/10/2026", timeText = "10:00", place = PlaceEdit.CHANGE, placeQuery = "Ber", chosenPlace = bergen, quarterTurns = 1)

  @Composable
  internal fun CorrectionOver(state: CorrectionUi) {
    Behind()
    val taps = (state as? CorrectionUi.Editing)?.plan?.let { ConfirmTaps(it) { 0L } }
    CorrectionSheet(visible = true, state = state, taps = taps, actions = CorrectionSheetActions(), onDismissRequest = {})
  }

  @Composable
  internal fun VersionsOver(state: VersionsUi) {
    Behind()
    val taps = (state as? VersionsUi.ConfirmRestore)?.let { ConfirmTaps(it.plan) { 0L } }
    VersionsSheet(visible = true, state = state, taps = taps, actions = VersionsSheetActions(), onDismissRequest = {})
  }

  @Composable
  internal fun PhoneOnlyOver(state: PhoneOnlyUi) {
    Behind()
    PhoneOnlySheet(visible = true, state = state, onChoose = {}, onSafeguard = {}, onContinue = {}, onDismiss = {})
  }

  internal fun advice(vararg values: PhoneOnlyAdvice): PhoneOnlyUi.Advice =
    PhoneOnlyAssessment(values.withIndex().associate { (i, a) -> FreeSpaceSamples.mediaId(i + 1) to a }).let { PhoneOnlyUi.Advice(it, safeFirst = it.preselectSafeFirst) }

  /** Il secondo pannello di "Libera subito": il primo gesto e' gia' stato fatto (con i tempi del contratto). */
  internal fun secondGesture(): Pair<dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan, ConfirmTaps> {
    val plan = FreeSpaceSamples.plan(items = 240, itemSize = 15_000_000L, permanent = true)
    var now = 10_000L
    val taps = ConfirmTaps(plan) { now }
    now += PhoneConfirmationRules.MIN_TAP_GAP_MS + 50
    taps.tap()
    return plan to taps
  }

  companion object {
    const val DIR = "../docs/screenshots/5-P12"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

// --- Le pagine ------------------------------------------------------------------------------------------------------------------------

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w412dp-h1500dp-xhdpi")
class FiveBPhonePageShots(dark: Boolean) : FiveBShotBase(dark, "telefono") {

  @Test
  fun liberaSpazioRiepilogo() = shoot("libera-spazio-riepilogo") { FreePage(summary) }

  @Test
  fun liberaSpazioRiepilogoTestoGrande() = shoot("libera-spazio-riepilogo", LARGE_TEXT) { FreePage(summary) }

  @Test
  fun liberaSpazioRiepilogoVuoto() = shoot("libera-spazio-riepilogo-vuoto") { FreePage(emptySummary) }

  @Test
  fun liberaSpazioVerifica() = shoot("libera-spazio-verifica") { FreePage(FreeSpaceUi.Verifying(340, 1_240, 4_200_000_000L)) }

  @Test
  fun liberaSpazioPronto() = shoot("libera-spazio-pronto") {
    FreePage(FreeSpaceUi.Ready(0, 1, 1_240, 18_600_000_000L, permanent = false, validUntilMonotonicMs = 21_600_000L))
  }

  @Test
  fun liberaSpazioProntoTestoGrande() = shoot("libera-spazio-pronto", LARGE_TEXT) {
    FreePage(FreeSpaceUi.Ready(0, 3, 4_500, 4_500_000_000L, permanent = false, validUntilMonotonicMs = 21_600_000L))
  }

  @Test
  fun liberaSpazioProntoDefinitivo() = shoot("libera-spazio-pronto-definitivo") {
    FreePage(FreeSpaceUi.Ready(0, 1, 240, 3_600_000_000L, permanent = true, validUntilMonotonicMs = 21_000_000L))
  }

  @Test
  fun liberaSpazioDialogoAperto() = shoot("libera-spazio-dialogo-aperto") { FreePage(FreeSpaceUi.Confirming(2, 3, permanent = false)) }

  @Test
  fun liberaSpazioFatto() = shoot("libera-spazio-fatto") {
    FreePage(FreeSpaceUi.Done(1_240, 18_600_000_000L, toAndroidTrash = true, kept = 30, dropped = 10, cancelled = false))
  }

  @Test
  fun liberaSpazioBloccatoLavoriFermi() = shoot("libera-spazio-bloccato-lavori-fermi") { FreePage(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.AREA_FROZEN)) }

  @Test
  fun liberaSpazioBloccatoVerificaScaduta() = shoot("libera-spazio-bloccato-verifica-scaduta") { FreePage(FreeSpaceUi.Blocked(FreeSpaceBlockedBy.EXPIRED)) }

  @Test
  fun correggiCompleto() = shoot("correggi-completo") {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
      val state = editing(fullDraft(), withPlan = true, canSearch = true, places = listOf(bergen, PlaceChoice("Berlino", GeoPoint(52.52, 13.4))))
      CorrectionSheetContent(state = state, taps = ConfirmTaps(state.plan!!) { 0L }, actions = CorrectionSheetActions())
    }
  }

  @Test
  fun daDecidere() = shoot("da-decidere") { PendingTasksScreen(PendingTasksUi(listOf(task(1), task(2)), canDeleteOriginal = true), PendingTasksActions(), onBack = {}) }

  @Test
  fun daDecidereTestoGrande() = shoot("da-decidere", LARGE_TEXT) {
    PendingTasksScreen(PendingTasksUi(listOf(task(1), task(2)), canDeleteOriginal = true), PendingTasksActions(), onBack = {})
  }

  @Test
  fun daDecidereVuota() = shoot("da-decidere-vuota") { PendingTasksScreen(PendingTasksUi(emptyList(), canDeleteOriginal = true), PendingTasksActions(), onBack = {}) }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w1280dp-h1300dp-hdpi")
class FiveBTabletPageShots(dark: Boolean) : FiveBShotBase(dark, "tablet") {

  @Test
  fun liberaSpazioRiepilogo() = shoot("libera-spazio-riepilogo") { FreePage(summary) }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

// --- I fogli ---------------------------------------------------------------------------------------------------------------------------

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class FiveBPhoneSheetShots(dark: Boolean) : FiveBShotBase(dark, "telefono") {

  private val plan = FreeSpaceSamples.plan(items = 1_240, itemSize = 15_000_000L, excluded = FreeSpaceSamples.excludedSample)

  // --- Libera spazio -------------------------------------------------------------------------------------------------------------

  @Test
  fun foglioLiberaSpazio() = shoot("foglio-libera-spazio") {
    FreePage(summary, FreeSheet.Confirm(plan), ConfirmTaps(plan) { 0L })
  }

  @Test
  fun foglioLiberaSpazioTestoGrande() = shoot("foglio-libera-spazio", LARGE_TEXT) {
    FreePage(summary, FreeSheet.Confirm(plan), ConfirmTaps(plan) { 0L })
  }

  @Test
  fun foglioLiberaSubitoPrimoGesto() {
    val permanent = FreeSpaceSamples.plan(items = 240, itemSize = 15_000_000L, permanent = true)
    shoot("foglio-libera-subito-primo-gesto") { FreePage(summary, FreeSheet.ConfirmNow(permanent), ConfirmTaps(permanent) { 0L }) }
  }

  @Test
  fun foglioLiberaSubitoSecondoGesto() {
    val (permanent, taps) = secondGesture()
    shoot("foglio-libera-subito-secondo-gesto") { FreePage(summary, FreeSheet.ConfirmNow(permanent), taps) }
  }

  @Test
  fun foglioLiberaSubitoSecondoGestoTestoGrande() {
    val (permanent, taps) = secondGesture()
    shoot("foglio-libera-subito-secondo-gesto", LARGE_TEXT) { FreePage(summary, FreeSheet.ConfirmNow(permanent), taps) }
  }

  // --- Elimina solo dal telefono -------------------------------------------------------------------------------------------------

  @Test
  fun eliminaSoloDalTelefonoNonAlSicuro() = shoot("elimina-solo-telefono-non-al-sicuro") { PhoneOnlyOver(advice(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.SAFE_FIRST)) }

  @Test
  fun eliminaSoloDalTelefonoAlSicuro() = shoot("elimina-solo-telefono-al-sicuro") { PhoneOnlyOver(advice(PhoneOnlyAdvice.FREE, PhoneOnlyAdvice.GUARD_FIRST)) }

  // --- Versioni --------------------------------------------------------------------------------------------------------------------

  @Test
  fun versioniLista() = shoot("versioni-lista") { VersionsOver(VersionsUi.Listing(VersionSamples.plan(), canView = true, canDeleteIntermediates = true)) }

  @Test
  fun versioniListaTestoGrande() = shoot("versioni-lista", LARGE_TEXT) {
    VersionsOver(VersionsUi.Listing(VersionSamples.plan(), canView = true, canDeleteIntermediates = true))
  }

  @Test
  fun versioniOriginaleNonAlSicuro() = shoot("versioni-originale-non-al-sicuro") {
    VersionsOver(VersionsUi.Listing(VersionSamples.plan(listOf(VersionSamples.original(safe = false), VersionSamples.current()), RestoreAvailability.ORIGINAL_NOT_SAFE), false, false))
  }

  @Test
  fun versioniAreaComune() = shoot("versioni-area-comune") {
    VersionsOver(VersionsUi.Listing(VersionSamples.plan(area = VersionArea.COMMON_NEEDS_NOTICE), false, false))
  }

  @Test
  fun versioniRiepilogo() = shoot("versioni-ripristina-riepilogo") { VersionsOver(VersionsUi.ConfirmRestore(VersionSamples.plan())) }

  @Test
  fun versioniPermesso() = shoot("versioni-ripristina-permesso") { VersionsOver(VersionsUi.AwaitingSystemDialog(VersionSamples.plan(), 1)) }

  @Test
  fun versioniFatto() = shoot("versioni-ripristina-fatto") { VersionsOver(VersionsUi.Restored(VersionSamples.plan(), phoneKeptEdited = true)) }

  // --- Correggi ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun correggiModifica() = shoot("correggi-modifica") {
    CorrectionOver(editing(fullDraft(), withPlan = true, canSearch = true, places = listOf(bergen, PlaceChoice("Berlino", GeoPoint(52.52, 13.4)))))
  }

  @Test
  fun correggiModificaTestoGrande() = shoot("correggi-modifica", LARGE_TEXT) {
    CorrectionOver(editing(fullDraft(), withPlan = true, canSearch = true, places = listOf(bergen, PlaceChoice("Berlino", GeoPoint(52.52, 13.4)))))
  }

  @Test
  fun correggiInizio() = shoot("correggi-inizio") { CorrectionOver(editing(start())) }

  @Test
  fun correggiRuota() = shoot("correggi-ruota") { CorrectionOver(editing(start().copy(quarterTurns = 3), CorrectionMode.ROTATE, withPlan = true)) }

  @Test
  fun correggiPrimaAlSicuro() = shoot("correggi-prima-al-sicuro") { CorrectionOver(CorrectionUi.NeedsSafeFirst(CorrectionSubject(VersionSamples.media))) }

  @Test
  fun correggiPermesso() = shoot("correggi-permesso") { CorrectionOver(CorrectionUi.AwaitingSystemDialog(1)) }

  @Test
  fun correggiCarica() = shoot("correggi-carica") { CorrectionOver(CorrectionUi.Uploading(onlyInApp = false)) }

  @Test
  fun correggiSoloNellApp() = shoot("correggi-solo-nell-app") { CorrectionOver(CorrectionUi.Done(onlyInApp = true)) }

  @Test
  fun correggiGuasto() = shoot("correggi-guasto") { CorrectionOver(CorrectionUi.Failed(CorrectionFailure.PHONE_NOT_RESTORED)) }

  @Test
  fun correggiBloccato() = shoot("correggi-bloccato") { CorrectionOver(CorrectionUi.Blocked(CorrectionReadiness.COMMON_AREA_NOTICE)) }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class FiveBTabletSheetShots(dark: Boolean) : FiveBShotBase(dark, "tablet") {

  private val plan = FreeSpaceSamples.plan(items = 1_240, itemSize = 15_000_000L, excluded = FreeSpaceSamples.excludedSample)

  @Test
  fun liberaSpazioPronto() = shoot("libera-spazio-pronto") {
    FreePage(FreeSpaceUi.Ready(0, 1, 1_240, 18_600_000_000L, permanent = false, validUntilMonotonicMs = 21_600_000L))
  }

  @Test
  fun liberaSpazioFatto() = shoot("libera-spazio-fatto") {
    FreePage(FreeSpaceUi.Done(1_240, 18_600_000_000L, toAndroidTrash = true, kept = 30, dropped = 10, cancelled = false))
  }

  @Test
  fun daDecidere() = shoot("da-decidere") { PendingTasksScreen(PendingTasksUi(listOf(task(1), task(2)), canDeleteOriginal = true), PendingTasksActions(), onBack = {}) }

  @Test
  fun foglioLiberaSpazio() = shoot("foglio-libera-spazio") { FreePage(summary, FreeSheet.Confirm(plan), ConfirmTaps(plan) { 0L }) }

  @Test
  fun foglioLiberaSubitoSecondoGesto() {
    val (permanent, taps) = secondGesture()
    shoot("foglio-libera-subito-secondo-gesto") { FreePage(summary, FreeSheet.ConfirmNow(permanent), taps) }
  }

  @Test
  fun versioniLista() = shoot("versioni-lista") { VersionsOver(VersionsUi.Listing(VersionSamples.plan(), canView = true, canDeleteIntermediates = true)) }

  @Test
  fun correggiModifica() = shoot("correggi-modifica") {
    CorrectionOver(editing(fullDraft(), withPlan = true, canSearch = true, places = listOf(bergen, PlaceChoice("Berlino", GeoPoint(52.52, 13.4)))))
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
