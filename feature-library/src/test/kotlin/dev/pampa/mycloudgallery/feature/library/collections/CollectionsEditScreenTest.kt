package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.feature.library.collections.testing.CollectionsSectionSamples
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * "Modifica Raccolte" (Fase 7, P11, D33, §2.7): la scheda con le sezioni dei fornitori finti e un controllore vero sopra un deposito in
 * memoria. Si entra con il tasto in alto, ogni sezione ha un interruttore "Mostra", si riordina trascinando dalla maniglia o con le azioni
 * di TalkBack "Sposta su" e "Sposta giu'", i bersagli sono da 48 dp.
 *
 * G2 (fase grafica): Album, Album smart e Telefono stanno nella scheda Album della pillola e non ci sono piu' (ne' nell'elenco ne' qui); sul
 * tablet, senza le raccolte-filtro del Telefono, il pannello di dettaglio parte da "Scegli una raccolta".
 */
abstract class CollectionsEditScreenBase : ComposeScreenTest() {

  protected val world = FakeCollectionsWorld()
  protected val store = MemoryCollectionsLayoutStore()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
  protected val opened = mutableListOf<String>()

  protected val controller = CollectionsLayoutController(
    world.source, true, CollectionsSectionSamples.all(), store, scope, SharingStarted.Eagerly,
  )

  @After
  fun stop() {
    scope.cancel()
  }

  @Composable
  protected fun Host(twoPane: Boolean = false) {
    val inputs by controller.inputs.collectAsState()
    val state = remember(inputs) {
      CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed)
    }
    ModalTestHost {
    CollectionsScreen(
      state = state,
      contentPadding = PaddingValues(),
      backdropOut = {},
      onOpenCollection = { opened += it.code },
      onOpenSettings = { opened += "settings" },
      statusHeader = {},
      detail = { kind -> Text("dettaglio ${kind.code}") },
      onOpenEntry = { opened += it.code },
      onEdit = controller::startEditing,
      onDone = controller::stopEditing,
      onReorder = controller::reorder,
      onSetShown = controller::setShown,
    )
    }
  }

  /** H5: "Modifica Raccolte" sta nel menu in alto, con le Impostazioni (prima era un tasto suo). */
  protected fun edit() {
    openMenu()
    composeRule.onNodeWithText("Modifica Raccolte").performClick()
    composeRule.waitForIdle()
  }

  protected fun openMenu() {
    composeRule.onNodeWithContentDescription("Altre azioni sulle Raccolte").performClick()
    composeRule.waitForIdle()
  }

  protected fun codes(): List<String> = controller.inputs.value.let {
    CollectionsLayout.state(it.present, emptyList(), it.saved, it.hidden, it.editing, it.saveFailed).sections.map { s -> s.code }
  }

  /**
   * La riga di una sezione com'e' per TalkBack: il nodo che ha le azioni (interruttore, "Sposta su" e "Sposta giu'") e il testo ("Viaggi, Mostrata").
   * Fino al Fluid Engine 2.13 erano due nodi, uno dentro l'altro (il `ListItem` di Material fondeva da se' e Compose non fonde un nodo che fonde dentro
   * un altro): le azioni sopra, il testo nel figlio. Dalla 3.0 `FluidListRow` mette il suo modificatore sul `ListItem` stesso e la riga e' **un nodo
   * solo**, con le azioni e il testo insieme (fix della 3.0, "cambia per chi cerca"). Il cercatore accetta le due forme.
   */
  protected fun row(title: String) =
    composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and (hasText(title) or hasAnyChild(hasText(title))))

  /** Le azioni di accessibilita' della riga di una sezione, per etichetta. */
  protected fun customActions(title: String): List<String> =
    row(title).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions)?.map { it.label }.orEmpty()

  protected fun perform(title: String, label: String) {
    val node = row(title).fetchSemanticsNode()
    val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
    composeRule.runOnUiThread { action.action() }
    composeRule.waitForIdle()
  }
}

class CollectionsEditScreenPhoneTest : CollectionsEditScreenBase() {

  @Test
  fun ilMenuInAltoEntraNellaModificaEFineEsce() {
    show { Host() }
    // H5: in alto un solo tasto, il menu; niente ingranaggio accanto alle raccolte.
    composeRule.onNodeWithContentDescription("Altre azioni sulle Raccolte").assertExists()
    composeRule.onAllNodesWithContentDescription("Impostazioni").assertCountEquals(0)

    edit()

    // Il titolo c'e' due volte (grande e agganciato alla barra).
    assertThat(composeRule.onAllNodesWithText("Modifica Raccolte").fetchSemanticsNodes()).isNotEmpty()
    composeRule.onNodeWithContentDescription("Fine").assertExists()
    // Un solo gruppo di tasti per volta: in modifica c'e' "Fine" e basta, niente menu.
    composeRule.onAllNodesWithContentDescription("Altre azioni sulle Raccolte").assertCountEquals(0)
    assertThat(controller.inputs.value.editing).isTrue()

    composeRule.onNodeWithContentDescription("Fine").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Altre azioni sulle Raccolte").assertExists()
    assertThat(controller.inputs.value.editing).isFalse()
  }

  @Test
  fun leImpostazioniSiApronoDalMenu_ComeUltimaVoce() {
    show { Host() }
    openMenu()
    composeRule.onNodeWithText("Impostazioni").performClick()
    composeRule.waitForIdle()
    assertThat(opened).containsExactly("settings")
    assertThat(controller.inputs.value.editing).isFalse()
  }

  @Test
  fun inModificaCiSonoTutteLeSezioniNelLoroOrdineConLoStatoDiOgnuna() {
    show { Host() }
    edit()

    for (title in listOf("Viaggi", "Persone", "Cartelle NAS", "Da tenere al sicuro", "Utility", "Nascosti")) {
      composeRule.onNodeWithText(title).assertExists()
    }
    // G2: le sezioni della scheda Album non ci sono.
    for (title in listOf("Album", "Album smart", "Telefono")) composeRule.onAllNodesWithText(title).assertCountEquals(0)
    // Una sezione mostrata si vede dall'interruttore: niente frase sotto il nome. Nessuna sezione e' "sempre visibile" (lo era Album).
    composeRule.onAllNodesWithText("Sempre visibile").assertCountEquals(0)
    composeRule.onAllNodesWithText("Mostrata", substring = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Nascosta", substring = true).assertCountEquals(0)
    // Nascosta, dice dove si ritrova (Nascosti resta in Impostazioni → Privacy).
    row("Nascosti").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("Nascosta · Si apre da Impostazioni → Privacy").assertExists()
    assertThat(store.hidden.value).containsExactly("hidden")
  }

  @Test
  fun lInterruttoreMostraENascondeEUnaSezioneNascostaRestaInLista() {
    show { Host() }
    edit()

    row("Viaggi").assertIsOn()
    row("Viaggi").performClick()
    composeRule.waitForIdle()

    row("Viaggi").assertIsOff()
    assertThat(store.hidden.value).containsExactly("trips")
    composeRule.onNodeWithText("Nascosta").assertExists()

    composeRule.onNodeWithContentDescription("Fine").performClick()
    composeRule.waitForIdle()
    // Fuori dalla modifica la sezione non c'e' piu'.
    composeRule.onAllNodesWithText("Toscana, agosto 2025").assertCountEquals(0)

    edit()
    composeRule.onNodeWithText("Viaggi").performClick()
    composeRule.waitForIdle()
    row("Viaggi").assertIsOn()
    assertThat(store.hidden.value).isEmpty()
  }

  /** G2: Album (che non si nascondeva mai), Album smart e Telefono non sono piu' in "Modifica Raccolte": stanno nella scheda Album. */
  @Test
  fun albumAlbumSmartETelefonoNonSonoPiuNellaModifica() {
    show { Host() }
    edit()

    for (title in listOf("Album", "Album smart", "Telefono")) composeRule.onAllNodesWithText(title).assertCountEquals(0)
    assertThat(codes()).containsNoneOf("albums", "smart", "phone")
    assertThat(store.hidden.value).isEmpty()
  }

  @Test
  fun lAzioneDiTalkBackSpostaSuESpostaGiu_EleImpossibiliNonCiSono() {
    show { Host() }
    edit()

    // La prima riga non puo' salire, l'ultima non puo' scendere: l'azione non si offre.
    assertThat(customActions("Viaggi")).containsExactly("Sposta giù")
    assertThat(customActions("Nascosti")).containsExactly("Sposta su")
    assertThat(customActions("Cartelle NAS")).containsExactly("Sposta su", "Sposta giù").inOrder()

    perform("Cartelle NAS", "Sposta su")

    assertThat(codes().take(3)).containsExactly("trips", "nas", "people").inOrder()
    // G2: i codici della scheda Album restano nell'ordine salvato, al loro posto (le versioni vecchie li mostrano ancora).
    assertThat(store.order.value.filter { it !in CollectionSections.inAlbumsTab }.take(3)).containsExactly("trips", "nas", "people").inOrder()
    assertThat(customActions("Cartelle NAS")).containsExactly("Sposta su", "Sposta giù").inOrder()

    perform("Cartelle NAS", "Sposta su")
    assertThat(codes().take(2)).containsExactly("nas", "trips").inOrder()
    assertThat(customActions("Cartelle NAS")).containsExactly("Sposta giù")
    perform("Cartelle NAS", "Sposta giù")
    perform("Cartelle NAS", "Sposta giù")
    assertThat(codes().take(4)).containsExactly("trips", "people", "nas", "safe").inOrder()
  }

  @Test
  fun ogniRigaDiceLaPosizioneEIlSuoStato() {
    show { Host() }
    edit()

    val node = row("Persone").fetchSemanticsNode()
    assertThat(node.config[SemanticsProperties.StateDescription]).isEqualTo("Mostrata, posizione 2 di 6")
    assertThat(node.config[SemanticsProperties.Role].toString()).isEqualTo("Switch")
  }

  @Test
  fun trascinareDallaManigliaCambiaLOrdineESiSalva() {
    show { Host() }
    edit()
    val rowHeight = composeRule.onNodeWithText("Persone").fetchSemanticsNode().size.height.toFloat()

    composeRule.onNodeWithTag(handleTag("people"), useUnmergedTree = true).performTouchInput {
      down(center)
      repeat(12) { moveBy(Offset(0f, -rowHeight * 1.3f / 12)) }
      up()
    }
    composeRule.waitForIdle()

    assertThat(codes().take(3)).containsExactly("people", "trips", "nas").inOrder()
    assertThat(store.order.value.filter { it !in CollectionSections.inAlbumsTab }.take(3)).containsExactly("people", "trips", "nas").inOrder()
  }

  @Test
  fun unTocco_SullaManigliaNonCambiaLaVisibilita() {
    show { Host() }
    edit()

    composeRule.onNodeWithTag(handleTag("trips"), useUnmergedTree = true).performClick()
    composeRule.waitForIdle()

    assertThat(store.hidden.value).isEmpty()
  }

  @Test
  fun iBersagliSonoDa48dp() {
    show { Host() }
    edit()

    val dp48 = with(androidx.compose.ui.unit.Density(2f)) { 48.dp.toPx() }
    val handle = composeRule.onNodeWithTag(handleTag("trips"), useUnmergedTree = true).fetchSemanticsNode()
    assertThat(handle.size.width.toFloat()).isAtLeast(dp48)
    assertThat(handle.size.height.toFloat()).isAtLeast(dp48)
    for (title in listOf("Viaggi", "Persone", "Nascosti")) {
      assertThat(composeRule.onNodeWithText(title).fetchSemanticsNode().size.height.toFloat()).isAtLeast(dp48)
    }
    // E il tasto "Fine" della barra.
    val done = composeRule.onNodeWithContentDescription("Fine").fetchSemanticsNode()
    assertThat(done.size.width.toFloat()).isAtLeast(dp48)
  }

  @Test
  fun unaScelta_FattaSuUnAltroDispositivoSiVedeSubito() {
    show { Host() }
    edit()
    composeRule.runOnUiThread {
      kotlinx.coroutines.runBlocking {
        store.setHidden("people", true)
        store.setOrder(listOf("utility", "phone", "albums", "nas"))
      }
    }
    composeRule.waitForIdle()

    row("Persone").assertIsOff()
    assertThat(codes().take(2)).containsExactly("utility", "nas").inOrder()
  }

  @Test
  fun unSalvataggioCheFallisceLoDiceInAlto() {
    val failing = object : CollectionsLayoutStore by store {
      override suspend fun setHidden(code: String, hidden: Boolean) = throw IllegalStateException("disco pieno")
    }
    val failingController = CollectionsLayoutController(
      world.source, true, CollectionsSectionSamples.all(), failing, CoroutineScope(Dispatchers.Unconfined + Job()), SharingStarted.Eagerly,
    )
    show {
      val inputs by failingController.inputs.collectAsState()
      val state = remember(inputs) {
        CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed)
      }
      ModalTestHost {
        CollectionsScreen(
          state, PaddingValues(), {}, {}, {}, {}, {},
          onEdit = failingController::startEditing, onDone = failingController::stopEditing,
          onReorder = failingController::reorder, onSetShown = failingController::setShown,
        )
      }
    }
    edit()

    composeRule.onNodeWithText("Viaggi").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Non sono riuscito a salvare la tua scelta. Riprova fra poco.").assertExists()
    row("Viaggi").assertIsOn()
  }

  @Test
  fun senzaNessunaSezioneDaMostrareLaSchedaLoDiceEPortaAllaModifica() {
    val hidden = MemoryCollectionsLayoutStore(hidden = setOf("safe", "hidden"))
    val c = CollectionsLayoutController(world.source, true, emptyList(), hidden, CoroutineScope(Dispatchers.Unconfined + Job()), SharingStarted.Eagerly)
    show {
      val inputs by c.inputs.collectAsState()
      val state = remember(inputs) { CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, inputs.editing, inputs.saveFailed) }
      CollectionsScreen(state, PaddingValues(), {}, {}, {}, {}, {}, onEdit = c::startEditing, onDone = c::stopEditing)
    }

    composeRule.onNodeWithText("Nessuna sezione da mostrare").assertExists()
    composeRule.onNodeWithText("Modifica Raccolte").performClick()
    composeRule.waitForIdle()
    assertThat(c.inputs.value.editing).isTrue()
    row("Nascosti").assertIsOff()
  }

  @Test
  fun unOrdineSalvatoSiVedeNellaSchedaEUnaSezioneNuovaStaInCoda() {
    store.let { s ->
      kotlinx.coroutines.runBlocking {
        s.setOrder(listOf("hidden", "phone", "safe", "albums", "smart", "trips", "nas", "utility"))
      }
    }
    show { Host() }

    // "Persone" non e' nell'ordine salvato (arriva con la Fase 8): e' l'ultima. G2: i codici della scheda Album si saltano.
    assertThat(codes()).containsExactly("hidden", "safe", "trips", "nas", "utility", "people").inOrder()
    composeRule.onNodeWithText("Nascosti").assertExists()
    composeRule.onAllNodesWithText("Preferiti").assertCountEquals(0)
  }
}

/** Il sottotitolo della riga "Eliminati di recente" di Da tenere al sicuro: la distingue dalla voce con lo stesso nome di Utility. */
private const val TRASH_ROW = "Il cestino di Android"

/** Sul tablet l'elenco delle sezioni ha il dettaglio accanto, nello stesso ordine del telefono; la modifica prende il posto dell'elenco. */
@Config(qualifiers = TABLET_QUALIFIERS)
class CollectionsEditScreenTabletTest : CollectionsEditScreenBase() {

  @Test
  fun suDuePannelliL_ElencoStaAccantoAlDettaglioENelloStessoOrdine() {
    show { Host() }

    // G2: senza le raccolte-filtro del Telefono (scheda Album) il dettaglio parte da "Scegli una raccolta".
    composeRule.onNodeWithText("Scegli una raccolta").assertExists()
    // Le due colonne: l'elenco a sinistra, il dettaglio a destra; le sezioni nello stesso ordine del telefono.
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(TRASH_ROW, substring = true))
    // Ricordi e Luoghi (Fase 9) non hanno ancora un fornitore negli esempi: non ci sono; Album, Album smart e Telefono sono nella scheda Album.
    assertThat(codes()).isEqualTo(
      CollectionSections.defaultOrder.filter { it != "memories" && it != "places" && it !in CollectionSections.inAlbumsTab },
    )
    composeRule.onNodeWithText(TRASH_ROW, substring = true).performClick()
    composeRule.waitForIdle()
    val list = composeRule.onNodeWithText(TRASH_ROW, substring = true).fetchSemanticsNode().boundsInRoot
    val detail = composeRule.onNodeWithText("dettaglio recently_deleted").fetchSemanticsNode().boundsInRoot
    assertThat(detail.left).isAtLeast(list.right)
  }

  /** La riga "Eliminati di recente" di Da tenere al sicuro (Utility ha una voce con lo stesso nome, "Telefono e NAS insieme"). */
  private fun openRecentlyDeleted() {
    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(TRASH_ROW, substring = true))
    composeRule.onNodeWithText(TRASH_ROW, substring = true).performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("dettaglio recently_deleted").assertExists()
  }

  @Test
  fun laModificaPrendeIlPostoDellElencoEIlDettaglioRestaDovEra() {
    show { Host() }
    openRecentlyDeleted()

    edit()

    row("Viaggi").assertIsOn()
    composeRule.onNodeWithText("dettaglio recently_deleted").assertExists()
    composeRule.onNodeWithText("Viaggi").performClick()
    composeRule.waitForIdle()
    row("Viaggi").assertIsOff()
    composeRule.onNodeWithContentDescription("Fine").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("dettaglio recently_deleted").assertExists()
  }

  @Test
  fun nascondereLaSezioneDelDettaglioDaTabletNonLasciaUnDettaglioVuoto() {
    show { Host() }
    openRecentlyDeleted()
    edit()
    composeRule.onNodeWithText("Da tenere al sicuro").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithContentDescription("Fine").performClick()
    composeRule.waitForIdle()

    // La raccolta aperta non si vede piu': il pannello dice di sceglierne una, non resta sul suo dettaglio.
    composeRule.onAllNodesWithText("dettaglio recently_deleted").assertCountEquals(0)
    composeRule.onNodeWithText("Scegli una raccolta").assertExists()
  }

  @Test
  fun unaVoceDelFornitoreSiApreNelDettaglioSeAppDaLaPagina() {
    val picked = mutableListOf<String>()
    show {
      val inputs by controller.inputs.collectAsState()
      val state = remember(inputs) { CollectionsLayout.state(inputs.present, emptyList(), inputs.saved, inputs.hidden, false, false) }
      CollectionsScreen(
        state, PaddingValues(), {}, {}, {}, {}, { Text("dettaglio ${it.code}") },
        onOpenEntry = { picked += it.code },
        entryDetail = { entry -> Text("pagina ${entry.code}") },
      )
    }

    composeRule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Toscana, agosto 2025"))
    composeRule.onNodeWithText("Toscana, agosto 2025").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("pagina trips/t1").assertExists()
    assertThat(picked).isEmpty()
  }
}
