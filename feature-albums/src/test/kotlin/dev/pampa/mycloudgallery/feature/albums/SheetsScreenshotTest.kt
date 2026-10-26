package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumRole
import dev.pampa.mycloudgallery.core.albums.contract.ClosePreview
import dev.pampa.mycloudgallery.core.albums.contract.CloseMode
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose
import dev.pampa.mycloudgallery.core.albums.contract.ShareBlock
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.designsystem.sheet.GallerySheetTone
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.choose.AddResult
import dev.pampa.mycloudgallery.feature.albums.choose.AddToAlbumHandlers
import dev.pampa.mycloudgallery.feature.albums.choose.AddToAlbumSheet
import dev.pampa.mycloudgallery.feature.albums.choose.AddToAlbumUi
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.CloseSheetState
import dev.pampa.mycloudgallery.feature.albums.contract.PermissionInfo
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetNotice
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.core.albums.contract.AlbumListSort
import dev.pampa.mycloudgallery.feature.albums.list.CreateAlbumSheet
import dev.pampa.mycloudgallery.feature.albums.list.MoveAlbumSheet
import dev.pampa.mycloudgallery.feature.albums.list.NoAlbumListHandlers
import dev.pampa.mycloudgallery.feature.albums.list.AlbumListScreen
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageScreen
import dev.pampa.mycloudgallery.feature.albums.page.ConfirmSheet
import dev.pampa.mycloudgallery.feature.albums.page.ItemMenuSheet
import dev.pampa.mycloudgallery.feature.albums.page.NoAlbumPageHandlers
import dev.pampa.mycloudgallery.feature.albums.page.PermissionsSheet
import dev.pampa.mycloudgallery.feature.albums.page.RenameSheet
import dev.pampa.mycloudgallery.feature.albums.page.SortSheet
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheet
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheetHandlers
import dev.pampa.mycloudgallery.feature.albums.share.CloseSheetUi
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheet
import dev.pampa.mycloudgallery.feature.albums.share.ShareSheetHandlers
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumHost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Un foglio che non fa niente: per gli screenshot. */
object NoShareHandlers : ShareSheetHandlers {
  override fun onConfirm() = Unit

  override fun onDismiss() = Unit

  override fun onHiddenChoice(choice: HiddenChoice) = Unit

  override fun onLevel(level: ShareLevel) = Unit
}

object NoCloseHandlers : CloseSheetHandlers {
  override fun onChooseOthers(choice: OthersAfterClose) = Unit

  override fun onBringBack(on: Boolean) = Unit

  override fun onConfirm() = Unit

  override fun onDismiss() = Unit
}

object NoAddHandlers : AddToAlbumHandlers {
  override fun onChoose(album: Album) = Unit

  override fun onNewAlbum(title: String) = Unit

  override fun onNewSharedAlbum(title: String) = Unit

  override fun onOpenAlbum(album: AlbumId) = Unit

  override fun onDismiss() = Unit

  override fun onDismissMessage() = Unit
}

/**
 * Gli screenshot dei fogli (Fase 7, P10): il foglio di condivisione in tutti i suoi stati (conteggi, cartella, livello, nascosti con l'avviso di E-SH9,
 * "Arriva dopo le prove"), i fogli di chiusura (Q5: nessuna scelta di partenza, "Riporta"), i permessi, il menu di un elemento, "Aggiungi ad album" e gli altri.
 * Ogni foglio sta sopra la pagina o l'elenco da cui si apre, come nell'app. Chiaro e scuro, telefono e tablet.
 */
abstract class SheetShots(private val dark: Boolean, private val device: String) : AlbumsComposeTest() {

  private val scenes = Scenes()
  private val look: String get() = if (dark) "scuro" else "chiaro"

  private fun shoot(name: String, expect: List<String> = emptyList(), fontScale: Float? = null, content: @Composable () -> Unit) {
    show(dark = dark, fontScale = fontScale) { AlbumHost { content() } }
    for (text in expect) {
      assertWithMessage("manca «$text» nella scena $name").that(composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()).isNotEmpty()
    }
    val suffix = if (fontScale != null) "-testo-grande" else ""
    if (ScreenshotPolicy.capture(name, device, fontScale)) composeRule.onRoot().captureRoboImage("../docs/screenshots/7-P10/$name-$look-$device$suffix.png")
  }

  /** La pagina dell'album da cui si apre il foglio (sotto, oscurata dal foglio). */
  @Composable
  private fun Behind(id: AlbumId = scenes.cabin) {
    AlbumPageScreen(scenes.pageUi(id), "", scenes.thumbnails.thumbnails, NoAlbumPageHandlers)
  }

  // --- Foglio di condivisione -------------------------------------------------------------------------------------------------------

  private fun plan(state: ShareSheetState): @Composable () -> Unit = {
    Behind()
    ShareSheet(state, NoShareHandlers)
  }

  @Test
  fun condividiUnAlbumNuovoConIlLivello() = shoot(
    "foglio-condividi-nuovo", listOf("Condividi", "permessi li fa rispettare"),
    content = plan(ShareSheetState.Plan(ShareFixtures.newAlbumPlan(hiddenLeftOut = 3), HiddenChoice.LEAVE_OUT, ShareLevel.ADD)),
  )

  @Test
  fun condividiUnAlbumNuovoConIlLivelloTestoGrande() = shoot(
    "foglio-condividi-nuovo", listOf("Condividi"), LARGE_TEXT,
    plan(ShareSheetState.Plan(ShareFixtures.newAlbumPlan(hiddenLeftOut = 3), HiddenChoice.LEAVE_OUT, ShareLevel.ADD)),
  )

  @Test
  fun condividiAncheINascostiAvvisaCheDiventanoVisibili() = shoot(
    "foglio-condividi-nascosti", listOf("nascost"),
    content = plan(ShareSheetState.Plan(ShareFixtures.newAlbumPlan(hidden = HiddenChoice.SHARE_TOO, hiddenIn = 3), HiddenChoice.SHARE_TOO, ShareLevel.ADD)),
  )

  @Test
  fun aggiungiAUnAlbumCheCeGia() = shoot(
    "foglio-aggiungi", listOf("Natale in famiglia"),
    content = plan(ShareSheetState.Plan(ShareFixtures.addPlan(), HiddenChoice.LEAVE_OUT, null)),
  )

  @Test
  fun rifattoPerCatalogoCambiato() = shoot(
    "foglio-rifatto-catalogo", content = plan(ShareSheetState.Plan(ShareFixtures.addPlan(), HiddenChoice.LEAVE_OUT, null, ShareSheetNotice.CHANGED)),
  )

  @Test
  fun rifattoPerScadenza() = shoot(
    "foglio-rifatto-scaduto", content = plan(ShareSheetState.Plan(ShareFixtures.addPlan(), HiddenChoice.LEAVE_OUT, null, ShareSheetNotice.EXPIRED)),
  )

  @Test
  fun riportaNelleMieCartelle() = shoot("foglio-riporta", content = plan(ShareSheetState.Plan(ShareFixtures.bringBackPlan(), HiddenChoice.SHARE_TOO, null)))

  @Test
  fun fileAggiuntiDalPc() = shoot("foglio-file-dal-pc", content = plan(ShareSheetState.Plan(ShareFixtures.adoptPlan(), HiddenChoice.SHARE_TOO, null)))

  @Test
  fun arrivaDopoLeProve() = shoot("foglio-dopo-le-prove", listOf("prove"), content = plan(ShareSheetState.Blocked(ShareBlock.TEST_SHARES_ONLY)))

  @Test
  fun bloccatoPerLeScrittureFerme() = shoot("foglio-bloccato", content = plan(ShareSheetState.Blocked(ShareBlock.WRITES_BLOCKED)))

  @Test
  fun nienteDaCondividere() = shoot(
    "foglio-niente", content = plan(ShareSheetState.NothingToShare(mapOf(ShareExclusion.ALREADY_IN_ALBUM to 3, ShareExclusion.HIDDEN_LEFT_OUT to 2))),
  )

  @Test
  fun ilPermessoECambiato() = shoot("foglio-permesso-cambiato", content = plan(ShareSheetState.Refused(AlbumOp.ADD_MEDIA)))

  @Test
  fun titoloNonValido() = shoot("foglio-titolo-non-valido", content = plan(ShareSheetState.InvalidTitle(TitleProblem.EMPTY)))

  // --- Fogli di chiusura ----------------------------------------------------------------------------------------------------------

  private fun closeUi(mode: CloseMode, forOthers: OthersAfterClose?, bringBack: Boolean): CloseSheetUi {
    val candidates = (1..20).map { ShareFixtures.media(it) }
    val preview = ClosePreview(scenes.osloShared, "Vacanza a Oslo", mode, candidates, otherMembers = 2)
    return CloseSheetUi(CloseSheetState(scenes.osloShared, "Vacanza a Oslo", mode, forOthers, candidates.size, bringBack), preview)
  }

  private fun close(ui: CloseSheetUi): @Composable () -> Unit = {
    Behind()
    CloseSheet(ui, hidden = false, handlers = NoCloseHandlers)
  }

  @Test
  fun smettiDiCondividereSenzaNessunaSceltaDiPartenza() =
    shoot("foglio-chiusura-nessuna-scelta", listOf("Vacanza a Oslo"), content = close(closeUi(CloseMode.STOP, null, false)))

  @Test
  fun smettiDiCondividereSenzaNessunaSceltaTestoGrande() =
    shoot("foglio-chiusura-nessuna-scelta", listOf("Vacanza a Oslo"), LARGE_TEXT, close(closeUi(CloseMode.STOP, null, false)))

  @Test
  fun smettiDiCondividereSparisceChoPerGliAltri() = shoot("foglio-chiusura-sparisce", content = close(closeUi(CloseMode.STOP, OthersAfterClose.REMOVE, false)))

  @Test
  fun eliminaLAlbumConLeCopieEIlRiportaAvvisaCheLeCopiePerdonoQuelliRiportati() =
    shoot("foglio-chiusura-copie-riporta", content = close(closeUi(CloseMode.DELETE, OthersAfterClose.KEEP_COPY, true)))

  @Test
  fun eliminaLAlbumSenzaNessunaScelta() = shoot("foglio-eliminazione-nessuna-scelta", content = close(closeUi(CloseMode.DELETE, null, false)))

  // --- Permessi, menu, conferme ---------------------------------------------------------------------------------------------------

  private fun info(level: ShareLevel, mine: Boolean): PermissionInfo {
    val roles = if (mine) setOf(AlbumRole.CREATOR) else setOf(AlbumRole.OTHER)
    return PermissionInfo(level, scenes.marco, mine, AlbumOp.entries.filter { AlbumPermissions.admits(it, level, roles) }.toSet())
  }

  private fun permissions(info: PermissionInfo, creator: String?): @Composable () -> Unit = {
    Behind()
    PermissionsSheet(visible = true, info = info, creatorName = creator, onSetLevel = {}, onDismiss = {})
  }

  @Test
  fun permessiDelCreatoreCambiaLivello() = shoot("foglio-permessi-creatore", listOf("rispettare"), content = permissions(info(ShareLevel.ADD, mine = true), null))

  @Test
  fun permessiDiUnAltroAGliAltriAggiungono() =
    shoot("foglio-permessi-altro-aggiunge", listOf("Marco"), content = permissions(info(ShareLevel.ADD, mine = false), "Marco"))

  @Test
  fun permessiDiUnAltroAGliAltriGuardano() =
    shoot("foglio-permessi-altro-guarda", listOf("Marco"), content = permissions(info(ShareLevel.VIEW, mine = false), "Marco"))

  @Test
  fun permessiTestoGrande() = shoot("foglio-permessi-creatore", listOf("rispettare"), LARGE_TEXT, permissions(info(ShareLevel.ADD, mine = true), null))

  private fun itemMenu(id: AlbumId, media: Int, by: String? = null): @Composable () -> Unit = {
    Behind()
    val menu = scenes.itemMenu(id, ShareFixtures.media(media), by?.let { scenes.marco })
    ItemMenuSheet(menu, { scenes.people.nameOf(it) }, {}, {}, {}, {}, {}, {})
  }

  @Test
  fun menuDiUnElementoDiUnAlbumPersonale() = shoot("foglio-elemento-personale", content = itemMenu(scenes.lake, 3))

  @Test
  fun menuDiUnElementoAggiuntoDaAltri() = shoot("foglio-elemento-condiviso", listOf("Marco"), content = itemMenu(scenes.cabin, 600, by = "Marco"))

  @Test
  fun menuDiUnElementoDiUnAlbumSmart() = shoot("foglio-elemento-smart", content = itemMenu(scenes.oslo, 70))

  @Test
  fun ordine() = shoot("foglio-ordine", content = { Behind(scenes.lake); SortSheet(true, AlbumSort.NEWEST_FIRST, {}, {}) })

  @Test
  fun rinomina() = shoot("foglio-rinomina", content = { Behind(scenes.lake); RenameSheet(true, "Compleanno di Anna", {}, {}) })

  @Test
  fun eliminaUnAlbumPersonale() = shoot(
    "foglio-elimina", content = {
      Behind(scenes.lake)
      ConfirmSheet(
        visible = true, icon = Icons.Rounded.Delete, tone = GallerySheetTone.Destructive, title = "Eliminare l’album?",
        message = "Le foto restano dove sono.", confirm = "Elimina l’album", destructive = true, onConfirm = {}, onDismiss = {},
      )
    },
  )

  @Test
  fun nonMostrarePiu() = shoot(
    "foglio-lascia", content = {
      Behind(scenes.cabin)
      ConfirmSheet(
        visible = true, icon = Icons.Rounded.VisibilityOff, tone = GallerySheetTone.Caution, title = "Non mostrare più «Natale in famiglia»?",
        message = "Resta agli altri.", confirm = "Non mostrare più", destructive = false, onConfirm = {}, onDismiss = {},
      )
    },
  )

  @Test
  fun spostaUnAlbumNellOrdinePersonalizzato() = shoot(
    "foglio-sposta", content = {
      AlbumListScreen(scenes.listUi(scenes.listReady(AlbumListSort.CUSTOM), reorderable = true), scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers)
      MoveAlbumSheet(title = "Compleanno di Anna", canMoveUp = true, canMoveDown = true, onMove = {}, onDismiss = {})
    },
  )

  @Test
  fun spostaIlPrimoAlbumNonVaPiuSu() = shoot(
    "foglio-sposta-primo", content = {
      AlbumListScreen(scenes.listUi(scenes.listReady(AlbumListSort.CUSTOM), reorderable = true), scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers)
      MoveAlbumSheet(title = "Da riempire", canMoveUp = false, canMoveDown = true, onMove = {}, onDismiss = {})
    },
  )

  // --- Nuovo album, Aggiungi ad album ---------------------------------------------------------------------------------------------

  @Test
  fun nuovoAlbum() = shoot(
    "foglio-nuovo-album", content = {
      AlbumListScreen(scenes.listUi(), scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers)
      CreateAlbumSheet(visible = true, problem = null, unavailable = false, onCreate = { _, _ -> }, onDismiss = {})
    },
  )

  @Test
  fun nuovoAlbumConTitoloNonValido() = shoot(
    "foglio-nuovo-album-titolo", content = {
      AlbumListScreen(scenes.listUi(), scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers)
      CreateAlbumSheet(visible = true, problem = TitleProblem.EMPTY, unavailable = false, onCreate = { _, _ -> }, onDismiss = {})
    },
  )

  private fun addUi(result: AddResult? = null, message: AlbumMessage? = null, count: Int = 12) = AddToAlbumUi(
    count = count,
    choices = scenes.world.albums.all.value.albums.filter { it is Album.Manual || (it is Album.Shared && it.level != ShareLevel.VIEW) },
    result = result,
    message = message,
    ready = true,
  )

  private fun add(ui: AddToAlbumUi): @Composable () -> Unit = {
    AlbumListScreen(scenes.listUi(), scenes.thumbnails.thumbnails, scenes.people, NoAlbumListHandlers)
    AddToAlbumSheet(ui, hidden = false, handlers = NoAddHandlers)
  }

  @Test
  fun aggiungiAdAlbumScelta() = shoot("foglio-aggiungi-ad-album", listOf("12"), content = add(addUi()))

  @Test
  fun aggiungiAdAlbumSceltaTestoGrande() = shoot("foglio-aggiungi-ad-album", listOf("12"), LARGE_TEXT, add(addUi()))

  @Test
  fun aggiungiAdAlbumFatto() = shoot("foglio-aggiungi-ad-album-fatto", content = add(addUi(result = AddResult.Added(scenes.birthday, added = 10, alreadyThere = 2))))

  @Test
  fun aggiungiAdAlbumNienteDaAggiungere() = shoot("foglio-aggiungi-ad-album-niente", content = add(addUi(count = 0)))

  companion object {
    fun looks(): List<Array<Any>> = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = PHONE_QUALIFIERS)
class SheetsPhoneScreenshotTest(dark: Boolean) : SheetShots(dark, "telefono") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = TABLET_QUALIFIERS)
class SheetsTabletScreenshotTest(dark: Boolean) : SheetShots(dark, "tablet") {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "scuro={0}")
    fun data(): List<Array<Any>> = looks()
  }
}
