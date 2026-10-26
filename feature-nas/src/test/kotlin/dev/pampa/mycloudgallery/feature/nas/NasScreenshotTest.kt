package dev.pampa.mycloudgallery.feature.nas

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingTabBarDefaults
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryConfirmSheet
import dev.pampa.mycloudgallery.core.nasops.contract.NasFolderState
import dev.pampa.mycloudgallery.core.nasops.contract.NasSpaceState
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpPlan
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.nas.confirm.HiddenChoice
import dev.pampa.mycloudgallery.feature.nas.confirm.NameCheck
import dev.pampa.mycloudgallery.feature.nas.confirm.NameDraft
import dev.pampa.mycloudgallery.feature.nas.confirm.NamePurpose
import dev.pampa.mycloudgallery.feature.nas.confirm.NasConfirmSheetContent
import dev.pampa.mycloudgallery.feature.nas.confirm.NasNameSheetContent
import dev.pampa.mycloudgallery.feature.nas.folders.FolderWhere
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersActions
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersMode
import dev.pampa.mycloudgallery.feature.nas.folders.HiddenFiles
import dev.pampa.mycloudgallery.feature.nas.folders.FoldersUiState
import dev.pampa.mycloudgallery.feature.nas.folders.NasFoldersScreen
import dev.pampa.mycloudgallery.feature.nas.operations.NasOperationsScreen
import dev.pampa.mycloudgallery.feature.nas.operations.OperationsUi
import dev.pampa.mycloudgallery.feature.nas.operations.operationsUi
import dev.pampa.mycloudgallery.feature.nas.space.NasSpaceScreen
import dev.pampa.mycloudgallery.feature.nas.space.SpaceUi
import dev.pampa.mycloudgallery.feature.nas.trash.NasTrashScreen
import dev.pampa.mycloudgallery.feature.nas.trash.TrashActions
import dev.pampa.mycloudgallery.feature.nas.trash.TrashUi
import dev.pampa.mycloudgallery.feature.nas.trash.key
import dev.pampa.mycloudgallery.feature.nas.ui.UiNotice
import dev.pampa.mycloudgallery.feature.nas.ui.NasTone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gli screenshot di P14 (Cartelle NAS, Spazio NAS, Eliminati di recente del NAS, Operazioni in corso e i fogli di conferma):
 * chiaro e scuro, telefono (412 dp) e tablet (1280 dp, due pannelli dove la pagina li ha), piu' il testo grande. Sui fake: i dati di
 * [NasFixtures] (un NAS di famiglia) e le aree `test` / `test-comune`.
 *
 * `gradlew :feature-nas:recordRoborazziDebug --tests "*NasScreenshot*"` scrive i PNG in `docs/screenshots/4-P14/`. Nei normali
 * `testDebugUnitTest` si verifica solo che le schermate compongano.
 */
abstract class NasScreenshotBase(private val dark: Boolean, private val device: String) : NasComposeBase() {

  private val areas = FakeNasAreas.defaultAreas()
  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale, content = content)
    val suffix = if (fontScale != null) "-testo-grande" else ""
    composeRule.onRoot().captureRoboImage("$DIR/$name-$look-$device$suffix.png")
  }

  // --- Stati di prova ---------------------------------------------------------------------------------------------------------

  private fun folders(
    where: FolderWhere? = FolderWhere("test", SharePath.of("Viaggi")),
    listing: NasFolderState = NasFolderState.Listing(NasFixtures.tripEntries(), readOnly = false, cachedAt = null),
    selecting: Boolean = false,
    selection: Set<SharePath> = emptySet(),
    mode: FoldersMode = FoldersMode.Browse,
    activeOperations: Int = 0,
    notice: UiNotice? = null,
    confirm: OpPlan? = null,
    name: NameDraft? = null,
    hidden: HiddenFiles = HiddenFiles.Known(emptySet()),
  ) = FoldersUiState(
    areas = areas, where = where, listing = listing, selecting = selecting, selection = selection, mode = mode,
    activeOperations = activeOperations, notice = notice, confirm = confirm, name = name, hidden = hidden,
  )

  @Composable
  private fun FoldersPage(state: FoldersUiState, onBack: (() -> Unit)? = {}, hostPadding: PaddingValues = PaddingValues()) {
    NasFoldersScreen(state = state, actions = FoldersActions(), monotonicMs = { 0L }, onBack = onBack, hostPadding = hostPadding)
  }

  private fun trash(
    list: NasTrashState = NasTrashState.Ready(NasFixtures.trashItems(), NasFixtures.NOW, writable = true),
    selecting: Boolean = false,
    selection: Set<String> = emptySet(),
    notice: UiNotice? = null,
    confirm: OpPlan? = null,
  ) = TrashUi(areas = areas, list = list, selecting = selecting, selection = selection, confirm = confirm, notice = notice)

  @Composable
  private fun TrashPage(state: TrashUi, onBack: (() -> Unit)? = {}) {
    NasTrashScreen(state = state, actions = TrashActions(), monotonicMs = { 0L }, onBack = onBack)
  }

  // --- Cartelle NAS -----------------------------------------------------------------------------------------------------------

  @Test
  fun cartelleLeAree() = shoot("cartelle-aree") { FoldersPage(folders(where = null, listing = NasFolderState.Loading), onBack = null) }

  @Test
  fun cartelleLeAreeConUnaOperazione() = shoot("cartelle-aree-operazione") {
    FoldersPage(
      folders(where = null, listing = NasFolderState.Loading, activeOperations = 1, notice = UiNotice(R.string.nas_started, NasTone.Calm, opensOperations = true)),
      onBack = null,
    )
  }

  @Test
  fun cartelleLaRadice() = shoot("cartelle-radice") {
    FoldersPage(folders(where = FolderWhere("test", null), listing = NasFolderState.Listing(NasFixtures.rootEntries(), false, null)))
  }

  @Test
  fun cartelleUnaCartella() = shoot("cartelle-cartella") { FoldersPage(folders()) }

  @Test
  fun cartelleUnaCartellaTestoGrande() = shoot("cartelle-cartella", fontScale = LARGE_TEXT) { FoldersPage(folders()) }

  @Test
  fun cartelleInSelezione() {
    val entries = NasFixtures.tripEntries()
    shoot("cartelle-selezione") {
      FoldersPage(folders(selecting = true, selection = setOf(entries[0].path, entries[2].path, entries[4].path)))
    }
  }

  @Test
  fun cartelleInSelezioneTestoGrande() {
    val entries = NasFixtures.tripEntries()
    shoot("cartelle-selezione", fontScale = LARGE_TEXT) {
      FoldersPage(folders(selecting = true, selection = setOf(entries[0].path, entries[2].path, entries[4].path)))
    }
  }

  @Test
  fun cartelleLaScelta() {
    val items = NasFixtures.tripEntries().take(3).map { it.toItem("test") }
    shoot("cartelle-sposta") {
      FoldersPage(
        folders(
          where = FolderWhere("test-comune", SharePath.of("Famiglia")),
          listing = NasFolderState.Listing(NasFixtures.tripEntries().take(4), false, null),
          mode = FoldersMode.PickDestination(items),
        ),
      )
    }
  }

  @Test
  fun cartelleInSolaLettura() = shoot("cartelle-sola-lettura") {
    FoldersPage(folders(where = FolderWhere("test-comune", null), listing = NasFolderState.Listing(NasFixtures.rootEntries().take(5), readOnly = true, cachedAt = null)))
  }

  @Test
  fun cartelleNasNonRisponde() = shoot("cartelle-offline") {
    FoldersPage(folders(listing = NasFolderState.Offline(NasFixtures.tripEntries(), NasFixtures.NOW - 26 * 3_600_000L)))
  }

  @Test
  fun cartelleNasNonRispondeEnienteInCache() = shoot("cartelle-offline-vuota") {
    FoldersPage(folders(listing = NasFolderState.Offline(null, null)))
  }

  @Test
  fun cartelleVuota() = shoot("cartelle-vuota") {
    FoldersPage(folders(listing = NasFolderState.Listing(emptyList(), false, null)))
  }

  @Test
  fun cartelleNegata() = shoot("cartelle-negata") { FoldersPage(folders(listing = NasFolderState.Denied)) }

  @Test
  fun cartelleErrore() = shoot("cartelle-errore") { FoldersPage(folders(listing = NasFolderState.Error(OpErrorCode.UNEXPECTED))) }

  @Test
  fun cartelleInCaricamento() = shoot("cartelle-caricamento") { FoldersPage(folders(listing = NasFolderState.Loading)) }

  @Test
  fun cartelleUnPianoRifiutato() = shoot("cartelle-avviso") {
    FoldersPage(folders(notice = UiNotice(R.string.nas_reject_not_enough_space, NasTone.Alarm)))
  }

  @Test
  fun cartelleNuovaCartella() = shoot("cartelle-nuova") {
    FoldersPage(folders())
    GalleryConfirmSheet(visible = true, onDismissRequest = {}, paneTitle = "Nuova cartella") {
      NasNameSheetContent(
        draft = NameDraft(NamePurpose.NEW_FOLDER, "Estate 2026", NameCheck.Ready(NasFixtures.trashPlan(0)), shareName = "test", folder = listOf("Viaggi")),
        areas = areas, onTextChange = {}, onUseSuggestion = {}, onConfirm = {}, onCancel = {},
      )
    }
  }

  @Test
  fun cartelleNomeNonValido() = shoot("cartelle-nome-non-valido") {
    FoldersPage(folders())
    GalleryConfirmSheet(visible = true, onDismissRequest = {}, paneTitle = "Rinomina") {
      NasNameSheetContent(
        draft = NameDraft(NamePurpose.RENAME, "Oslo: giorno 1 ", NameCheck.Invalid("Oslo_ giorno 1")),
        areas = areas, onTextChange = {}, onUseSuggestion = {}, onConfirm = {}, onCancel = {},
      )
    }
  }

  // --- Spazio NAS -------------------------------------------------------------------------------------------------------------

  private fun space(space: NasSpaceState, share: String? = "test") = SpaceUi(areas = areas, share = share, space = space)

  @Composable
  private fun SpacePage(state: SpaceUi, expanded: Boolean = false) {
    NasSpaceScreen(state = state, onSelectArea = {}, onReload = {}, onBack = {}, onOpenTrash = {}, initiallyExpanded = expanded)
  }

  @Test
  fun spazio() = shoot("spazio") { SpacePage(space(NasSpaceState.Ready(NasFixtures.space()))) }

  @Test
  fun spazioTestoGrande() = shoot("spazio", fontScale = LARGE_TEXT) { SpacePage(space(NasSpaceState.Ready(NasFixtures.space()))) }

  @Test
  fun spazioConQuotaEIlCestinoDelWd() = shoot("spazio-quota") {
    SpacePage(space(NasSpaceState.Ready(NasFixtures.space(quota = 82_000_000_000L, wd = true, share = "test-comune")), share = "test-comune"))
  }

  @Test
  fun spazioTuttiIFile() = shoot("spazio-tutti") { SpacePage(space(NasSpaceState.Ready(NasFixtures.space())), expanded = true) }

  @Test
  fun spazioNonDisponibile() = shoot("spazio-non-disponibile") {
    SpacePage(space(NasSpaceState.Unavailable(OpErrorCode.UNREACHABLE, NasFixtures.largest(6))))
  }

  @Test
  fun spazioInCaricamento() = shoot("spazio-caricamento") { SpacePage(space(NasSpaceState.Loading)) }

  // --- Eliminati di recente del NAS -------------------------------------------------------------------------------------------

  @Test
  fun eliminati() = shoot("eliminati") { TrashPage(trash()) }

  @Test
  fun eliminatiTestoGrande() = shoot("eliminati", fontScale = LARGE_TEXT) { TrashPage(trash()) }

  @Test
  fun eliminatiInSelezioneDelleProprie() {
    val items = NasFixtures.trashItems()
    shoot("eliminati-selezione") { TrashPage(trash(selecting = true, selection = setOf(items[0].key(), items[1].key()))) }
  }

  @Test
  fun eliminatiInSelezioneConUnaVoceDiAltri() {
    val items = NasFixtures.trashItems()
    shoot("eliminati-selezione-mista") { TrashPage(trash(selecting = true, selection = setOf(items[0].key(), items[2].key()))) }
  }

  @Test
  fun eliminatiInSelezioneTestoGrande() {
    val items = NasFixtures.trashItems()
    shoot("eliminati-selezione", fontScale = LARGE_TEXT) { TrashPage(trash(selecting = true, selection = setOf(items[0].key(), items[1].key()))) }
  }

  @Test
  fun eliminatiNasNonRisponde() = shoot("eliminati-offline") {
    TrashPage(trash(list = NasTrashState.Offline(NasFixtures.trashItems().filter { it.deletableNow })))
  }

  @Test
  fun eliminatiInSolaLettura() = shoot("eliminati-sola-lettura") {
    TrashPage(trash(list = NasTrashState.Ready(NasFixtures.trashItems().map { it.copy(restorable = false, deletableNow = false) }, NasFixtures.NOW, writable = false)))
  }

  @Test
  fun eliminatiVuoti() = shoot("eliminati-vuoti") { TrashPage(trash(list = NasTrashState.Ready(emptyList(), NasFixtures.NOW, writable = true))) }

  @Test
  fun eliminatiInCaricamento() = shoot("eliminati-caricamento") { TrashPage(trash(list = NasTrashState.Loading)) }

  // --- Operazioni in corso ----------------------------------------------------------------------------------------------------

  @Test
  fun operazioni() = shoot("operazioni") { NasOperationsScreen(state = operationsUi(NasFixtures.operations()), onStop = {}, onBack = {}) }

  @Test
  fun operazioniTestoGrande() = shoot("operazioni", fontScale = LARGE_TEXT) {
    NasOperationsScreen(state = operationsUi(NasFixtures.operations()), onStop = {}, onBack = {})
  }

  @Test
  fun operazioniNessuna() = shoot("operazioni-nessuna") { NasOperationsScreen(state = OperationsUi(), onStop = {}, onBack = {}) }

  // --- I fogli di conferma ----------------------------------------------------------------------------------------------------

  @Composable
  private fun SheetOverFolders(plan: OpPlan, tapsDone: Int = 0, choice: HiddenChoice? = null) {
    FoldersPage(folders())
    GalleryConfirmSheet(visible = true, onDismissRequest = {}, paneTitle = "Conferma") {
      NasConfirmSheetContent(plan = plan, areas = areas, tapsDone = tapsDone, onConfirm = {}, onCancel = {}, choice = choice)
    }
  }

  @Test
  fun foglioSpostaNellAreaComune() = shoot("foglio-sposta-comune") { SheetOverFolders(NasFixtures.moveToCommonPlan(skipped = true)) }

  @Test
  fun foglioSpostaNellAreaComuneTestoGrande() = shoot("foglio-sposta-comune", fontScale = LARGE_TEXT) { SheetOverFolders(NasFixtures.moveToCommonPlan(skipped = true)) }

  // R11 (scelta del 15/10): lo spostamento nell'area comune con dei nascosti: "Lascia i nascosti dove sono" (di partenza) o "Sposta tutto".
  @Test
  fun foglioSpostaNellAreaComuneLasciandoINascosti() = shoot("foglio-sposta-comune-nascosti-lascia") {
    SheetOverFolders(NasFixtures.moveToCommonLeavingHiddenPlan(), choice = HiddenChoice(count = 2, leave = true, somethingLeft = true, onChange = {}))
  }

  @Test
  fun foglioSpostaNellAreaComuneLasciandoINascostiTestoGrande() = shoot("foglio-sposta-comune-nascosti-lascia", fontScale = LARGE_TEXT) {
    SheetOverFolders(NasFixtures.moveToCommonLeavingHiddenPlan(), choice = HiddenChoice(count = 2, leave = true, somethingLeft = true, onChange = {}))
  }

  @Test
  fun foglioSpostaNellAreaComuneConINascosti() = shoot("foglio-sposta-comune-nascosti-tutto") {
    SheetOverFolders(NasFixtures.moveToCommonPlan(), choice = HiddenChoice(count = 2, leave = false, somethingLeft = true, onChange = {}))
  }

  @Test
  fun foglioSpostaNellAreaComuneSeSonoTuttiNascosti() = shoot("foglio-sposta-comune-nascosti-niente") {
    SheetOverFolders(NasFixtures.moveToCommonPlan(), choice = HiddenChoice(count = 2, leave = true, somethingLeft = false, onChange = {}))
  }

  @Test
  fun foglioSpostaNellaStessaArea() = shoot("foglio-sposta") { SheetOverFolders(NasFixtures.moveSameAreaPlan()) }

  @Test
  fun foglioElimina() = shoot("foglio-elimina") { SheetOverFolders(NasFixtures.trashPlan()) }

  @Test
  fun foglioRipristina() = shoot("foglio-ripristina") { SheetOverFolders(NasFixtures.restorePlan()) }

  @Test
  fun foglioEliminaSubitoPrimoPannello() = shoot("foglio-elimina-subito-1") { SheetOverFolders(NasFixtures.purgePlan(), tapsDone = 0) }

  @Test
  fun foglioEliminaSubitoUltimaConferma() = shoot("foglio-elimina-subito-2") { SheetOverFolders(NasFixtures.purgePlan(), tapsDone = 1) }

  @Test
  fun foglioEliminaSubitoUltimaConfermaTestoGrande() = shoot("foglio-elimina-subito-2", fontScale = LARGE_TEXT) {
    SheetOverFolders(NasFixtures.purgePlan(), tapsDone = 1)
  }

  companion object {
    const val DIR = "../docs/screenshots/4-P14"
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class NasPhoneScreenshotTest(dark: Boolean) : NasScreenshotBase(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class NasTabletScreenshotTest(dark: Boolean) : NasScreenshotBase(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@Suppress("unused")
private val PillInset = FluidFloatingTabBarDefaults.ContentInset
