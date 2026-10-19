package dev.pampa.mycloudgallery.feature.viewer.menu

import android.app.Application
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.designsystem.sheet.LocalConfirmSheetShield
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cio' che il viewer mostra per "Condividi" e per "Elimina… → Ovunque" della 5B (`docs/fase5-progetto.md` §2.7, D22, D26, E-D15, L41): l'avviso con
 * l'avanzamento, il foglio di una condivisione ferma (che offre "Condividi con la posizione" solo come **scelta** dell'utente) e le versioni
 * precedenti elencate prima di confermare "Ovunque". Con lo scudo contro le sovrapposizioni (R7) e i bersagli da 48 dp.
 */
class BackupActionsSheetsTest : NasActionsPhoneTest() {

  private var withLocation = 0
  private var dismissed = 0
  private var cancelled = 0

  private fun button(text: String) = composeRule.onNode(hasText(text) and hasClickAction())

  private fun absent(text: String) = assertThat(composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes()).isEmpty()

  // --- L'avviso della condivisione ------------------------------------------------------------------------------------------------

  @Composable
  private fun Notice(share: BackupShareUi) {
    BackupShareNotice(share, rememberGlassBackdrop(), onCancel = { cancelled++ })
  }

  @Test
  fun preparandoDiceCosaFaEQuantiNeHaFatti() {
    show { Notice(BackupShareUi.Preparing(1, 3, removingLocation = true)) }
    composeRule.onNodeWithText("Tolgo la posizione e controllo il file").assertExists()
    composeRule.onNodeWithText("1 di 3").assertExists()
    button("Annulla").performClick()
    assertThat(cancelled).isEqualTo(1)
  }

  @Test
  fun senzaTogliereLaPosizioneDiceSoloChePreparaEUnFileNonHaIlConto() {
    show { Notice(BackupShareUi.Preparing(0, 1, removingLocation = false)) }
    composeRule.onNodeWithText("Preparo la copia da condividere").assertExists()
    absent("di 1")
  }

  @Test
  fun prontoDiceCheLaPosizioneEStataTolta_EControllata() {
    show { Notice(BackupShareUi.Ready(verified = 2, direct = 0)) }
    composeRule.onNodeWithText("Posizione tolta e controllata in 2 file").assertExists()
    composeRule.onNodeWithText("Il file che condividi non contiene nessuna posizione.").assertExists()
  }

  @Test
  fun unSoloFileProntoDicePosizioneTolta() {
    show { Notice(BackupShareUi.Ready(verified = 1, direct = 0)) }
    composeRule.onNodeWithText("Posizione tolta e controllata").assertExists()
  }

  @Test
  fun seLaPosizioneNonVaTolta_NonSiPromettePiuDelVero() {
    show { Notice(BackupShareUi.Ready(verified = 0, direct = 2)) }
    composeRule.onNodeWithText("Pronto da condividere").assertExists()
    absent("Posizione tolta")
    absent("nessuna posizione")
  }

  @Test
  fun IdleELaFermataNonDisegnanoNienteNellAvviso() {
    show { Notice(BackupShareUi.Idle) }
    assertThat(composeRule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()).isEmpty()
  }

  // --- Una condivisione ferma ------------------------------------------------------------------------------------------------------

  private val stopped = BackupShareUi.Refused(
    "Questa foto non si può condividere senza posizione: condividila con la posizione o annulla.",
    canShareWithLocation = true,
  )

  @Test
  fun ilFoglioDiceIlPercheEOffreLaSceltaEsplicita() {
    show { BackupShareRefusedContent(stopped, { withLocation++ }, { dismissed++ }) }
    composeRule.onNodeWithText("Condivisione ferma").assertExists()
    composeRule.onNodeWithText("Questa foto non si può condividere senza posizione", substring = true).assertExists()
    // Nessuna condivisione parte da sola, nemmeno mostrando il foglio.
    assertThat(withLocation).isEqualTo(0)
    button("Condividi con la posizione").performClick()
    assertThat(withLocation).isEqualTo(1)
    assertThat(dismissed).isEqualTo(0)
  }

  @Test
  fun annullareChiudeSenzaCondividere() {
    show { BackupShareRefusedContent(stopped, { withLocation++ }, { dismissed++ }) }
    button("Annulla").performClick()
    assertThat(dismissed).isEqualTo(1)
    assertThat(withLocation).isEqualTo(0)
  }

  @Test
  fun quandoLaPosizioneNonSiPuoOffrireCeSoloHoCapito() {
    show { BackupShareRefusedContent(BackupShareUi.Refused("Il NAS non risponde: non posso scaricare il file da condividere.", canShareWithLocation = false), { withLocation++ }, { dismissed++ }) }
    absent("Condividi con la posizione")
    button("Ho capito").performClick()
    assertThat(dismissed).isEqualTo(1)
    assertThat(withLocation).isEqualTo(0)
  }

  // --- Ovunque con le versioni --------------------------------------------------------------------------------------------------------

  private val kit = NasOpsTestKit()

  private fun deleteState(scope: GalleryDeleteScope): NasActionsState.Delete {
    kit.putFile("test", "Viaggi/IMG_1.jpg", byteArrayOf(1, 2, 3, 4))
    val target = runBlocking { KitNasMediaActions(kit).target(KitNasMediaActions.REF)!! }
    return NasActionsState.Delete(target, scope, plan = null, planning = false)
  }

  @Test
  fun ovunqueElencaLeVersioniPrecedentiCheVannoNelCestino() {
    val state = deleteState(GalleryDeleteScope.Everywhere)
    show {
      NasDeleteSheetContent(state, onScope = {}, onConfirm = {}, onCancel = {}, extraLocations = everywhereLocations(EverywhereSummary(previousVersions = 2, commonCopies = 1)))
    }
    composeRule.onNodeWithText("2 versioni precedenti").assertExists()
    composeRule.onNodeWithText("Nella cartella Versioni sul NAS", substring = true).assertExists()
    composeRule.onNodeWithText("1 copia nell’area comune").assertExists()
    // Con "Ovunque" vanno tutte nel cestino del NAS.
    assertThat(composeRule.onAllNodes(hasText("Verrà eliminata")).fetchSemanticsNodes().size).isAtLeast(3)
  }

  @Test
  fun soloDalTelefonoLeVersioniRestano() {
    val state = deleteState(GalleryDeleteScope.PhoneOnly)
    show {
      NasDeleteSheetContent(state, onScope = {}, onConfirm = {}, onCancel = {}, extraLocations = everywhereLocations(EverywhereSummary(previousVersions = 1)))
    }
    composeRule.onNodeWithText("1 versione precedente").assertExists()
    assertThat(composeRule.onAllNodes(hasText("Resta")).fetchSemanticsNodes().size).isAtLeast(2)
  }

  @Test
  fun senzaVersioniPrecedentiIlFoglioNonCambia() {
    var unknown: List<dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation>? = null
    var none: List<dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation>? = null
    show {
      unknown = everywhereLocations(null)
      none = everywhereLocations(EverywhereSummary(previousVersions = 0))
    }
    assertThat(unknown).isEmpty()
    assertThat(none).isEmpty()
  }

  // --- Elimina solo dal telefono ----------------------------------------------------------------------------------------------------

  private fun showDeleteSheets(onDelete: () -> Unit, onPhoneOnly: (() -> Unit)?): NasActionsController {
    kit.putFile("test", "Viaggi/IMG_1.jpg", byteArrayOf(1, 2, 3, 4))
    val controller = NasActionsController(KitNasMediaActions(kit), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
    controller.bind(KitNasMediaActions.REF)
    controller.requestDelete()
    show { NasActionSheets(controller = controller, onDeleteFromPhone = onDelete, onPhoneOnlyDelete = onPhoneOnly) }
    return controller
  }

  @Test
  fun soloDalTelefonoPassaDallaVerificaDelBackupSeCe_ENonDalCestinoDiPrima() {
    var plain = 0
    var verified = 0
    showDeleteSheets(onDelete = { plain++ }, onPhoneOnly = { verified++ })
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Elimina dal telefono").performClick()
    composeRule.waitForIdle()
    assertThat(verified).isEqualTo(1)
    assertThat(plain).isEqualTo(0)
  }

  @Test
  fun senzaIlBackupSoloDalTelefonoUsaIlCestinoDiPrima() {
    var plain = 0
    showDeleteSheets(onDelete = { plain++ }, onPhoneOnly = null)
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Elimina dal telefono").performClick()
    composeRule.waitForIdle()
    assertThat(plain).isEqualTo(1)
  }

  // --- Lo scudo, i bordi ---------------------------------------------------------------------------------------------------------------

  @Test
  fun r7_ilFoglioDiUnaCondivisioneFermaHaLoScudoSoloDaAperto() {
    var shieldCalls = 0
    var window: View? = null
    val state = mutableStateOf<BackupShareUi.Refused?>(null)
    show {
      val view = LocalView.current
      SideEffect { window = view }
      CompositionLocalProvider(LocalConfirmSheetShield provides { SideEffect { shieldCalls++ } }) {
        BackupShareRefusedSheet(state = state.value, onShareWithLocation = {}, onDismiss = {})
      }
    }
    assertThat(shieldCalls).isEqualTo(0)
    assertThat(window!!.filterTouchesWhenObscured).isFalse()
    composeRule.runOnIdle { state.value = stopped }
    composeRule.waitForIdle()
    assertThat(shieldCalls).isGreaterThan(0)
    assertThat(window!!.filterTouchesWhenObscured).isTrue()
    composeRule.runOnIdle { state.value = null }
    composeRule.waitForIdle()
    assertThat(window!!.filterTouchesWhenObscured).isFalse()
  }

  private fun clickable(): List<SemanticsNode> =
    composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

  private fun assertBounds(where: String) {
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val root: Rect = composeRule.onAllNodes(SemanticsMatcher("radice") { it.isRoot }).fetchSemanticsNodes().first().boundsInRoot
    val nodes = clickable()
    assertWithMessage("$where: ci sono cose da toccare").that(nodes).isNotEmpty()
    for (node in nodes) {
      val b = node.boundsInRoot
      assertWithMessage("$where: $b alto almeno 48 dp").that(b.height).isAtLeast(min - 1f)
      assertWithMessage("$where: $b largo almeno 48 dp").that(b.width).isAtLeast(min - 1f)
      assertWithMessage("$where: $b dentro lo schermo").that(b.left >= root.left - 1f && b.right <= root.right + 1f).isTrue()
    }
    for (i in nodes.indices) {
      for (j in i + 1 until nodes.size) {
        val a = nodes[i].boundsInRoot
        val b = nodes[j].boundsInRoot
        val nested = (a.left <= b.left + 1f && a.top <= b.top + 1f && a.right >= b.right - 1f && a.bottom >= b.bottom - 1f) ||
          (b.left <= a.left + 1f && b.top <= a.top + 1f && b.right >= a.right - 1f && b.bottom >= a.bottom - 1f)
        if (nested) continue
        val overlaps = a.left < b.right - 1f && b.left < a.right - 1f && a.top < b.bottom - 1f && b.top < a.bottom - 1f
        assertWithMessage("$where: $a e $b si sovrappongono").that(overlaps).isFalse()
      }
    }
  }

  @Test
  fun iBottoniDelFoglioEDellAvvisoSonoDa48DpAUnoEATreDecimi() {
    val share = mutableStateOf<BackupShareUi>(BackupShareUi.Preparing(1, 3, true))
    val sheet = mutableStateOf(stopped)
    show {
      androidx.compose.foundation.layout.Column {
        Notice(share.value)
        BackupShareRefusedContent(sheet.value, {}, {})
      }
    }
    assertBounds("avviso e foglio 1x")
    composeRule.runOnIdle { sheet.value = BackupShareUi.Refused("Il NAS non risponde.", canShareWithLocation = false) }
    composeRule.waitForIdle()
    assertBounds("foglio senza posizione 1x")
  }
}

/** Lo stesso a 1,3x. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = NAS_PHONE)
class BackupActionsLargeTextTest : NasActionsComposeBase() {

  @Test
  fun alTestoGrandeIBottoniNonSiToccanoENonEscono() {
    val stopped = BackupShareUi.Refused("Questa foto non si può condividere senza posizione: condividila con la posizione o annulla.", canShareWithLocation = true)
    show(fontScale = NAS_LARGE_TEXT) {
      androidx.compose.foundation.layout.Column {
        BackupShareNotice(BackupShareUi.Preparing(1, 3, true), rememberGlassBackdrop(), onCancel = {})
        BackupShareRefusedContent(stopped, {}, {})
      }
    }
    val min = with(composeRule.density) { GalleryDimens.MinTouchTarget.toPx() }
    val nodes = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0f }
    assertThat(nodes).isNotEmpty()
    val ordered = nodes.sortedBy { it.boundsInRoot.top }
    for (node in ordered) assertThat(node.boundsInRoot.height).isAtLeast(min - 1f)
    for (i in 0 until ordered.size - 1) assertThat(ordered[i].boundsInRoot.bottom).isAtMost(ordered[i + 1].boundsInRoot.top + 1f)
  }
}
