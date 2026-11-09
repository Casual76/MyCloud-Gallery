package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.Albums
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.testing.SmartMembership
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.choose.AddResult
import dev.pampa.mycloudgallery.feature.albums.choose.AddToAlbumController
import dev.pampa.mycloudgallery.feature.albums.contract.AddToAlbumRequest
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMessage
import dev.pampa.mycloudgallery.feature.albums.contract.ShareSheetState
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionKind
import dev.pampa.mycloudgallery.feature.albums.page.SuggestionsController
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * "Aggiungi ad album" (D35, D22, D12) e "Suggerite" (D14, E-AL5): i nascosti non si offrono mai fuori dai Nascosti; un album condiviso apre sempre il foglio
 * di conferma; accettare ed escludere valgono per sempre e le decide l'utente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddToAlbumAndSuggestionsTest {

  private val other = AccountId("test2")

  private fun TestScope.chooser(world: AlbumsTestWorld, request: AddToAlbumRequest, withVisibility: Boolean = true) =
    AddToAlbumController(request, world.albums, world.sharedAlbums, if (withVisibility) world.library else null, world.runner, world.screens, backgroundScope)
      .also { it.start() }

  /** Tre foto della Libreria, la seconda nascosta. */
  private fun seed(world: AlbumsTestWorld) {
    world.library.put(world.photo(1), world.photo(2, hidden = true), world.photo(3))
  }

  private val all = listOf(AlbumsTestWorld.media(1), AlbumsTestWorld.media(2), AlbumsTestWorld.media(3))

  @Test
  fun fuoriDaiNascostiUnNascostoNonSiOffreMai() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    assertThat(chooser(world, AddToAlbumRequest(all)).ui.value.count).isEqualTo(2)
    // Dai Nascosti si offre tutto: l'album riceve il media ma non lo mostra finche' e' nascosto.
    assertThat(chooser(world, AddToAlbumRequest(all, fromHidden = true)).ui.value.count).isEqualTo(3)
  }

  @Test
  fun senzaLaPortaDellaVisibilitaNonSiOffreNienteEFallisceChiuso() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    val c = chooser(world, AddToAlbumRequest(all), withVisibility = false)
    assertThat(c.ui.value.count).isEqualTo(0)
    assertThat(c.ui.value.ready).isTrue()
  }

  @Test
  fun inUnAlbumPersonaleEntranoSoloIVisibiliEUnaSecondaVoltaSonoGiaLi() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    val album = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val c = chooser(world, AddToAlbumRequest(all))
    c.add(world.albums.all.value.byId(album)!!)
    val first = c.ui.value.result as AddResult.Added
    assertThat(first.added).isEqualTo(2)
    assertThat(world.albums.all.value.byId(album)?.count).isEqualTo(2)
    c.add(world.albums.all.value.byId(album)!!)
    val second = c.ui.value.result as AddResult.Added
    assertThat(second.added).isEqualTo(0)
    assertThat(second.alreadyThere).isEqualTo(2)
  }

  /**
   * R-1 della review generale: `FilePersonalLedger.save` con la memoria piena lancia `IOException`, che attraversa `Albums.add` fino al `scope.launch` del controller e chiudeva
   * l'app (e lasciava "occupato" per sempre). Ora e' il messaggio "Non ci sono riuscito", niente e' cambiato e si puo' riprovare.
   */
  @Test
  fun conLaMemoriaPienaL_AggiuntaDiceCheNonEStataFattaENonFaCadereNiente() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    val album = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    var full = true
    val failing = object : Albums by world.albums {
      override suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome =
        if (full) throw IOException("No space left on device") else world.albums.add(id, media)
    }
    val c = AddToAlbumController(AddToAlbumRequest(all), failing, world.sharedAlbums, world.library, world.runner, world.screens, backgroundScope).also { it.start() }

    c.add(world.albums.all.value.byId(album)!!)

    assertThat(c.ui.value.message).isEqualTo(AlbumMessage.NotDone)
    assertThat(c.ui.value.busy).isFalse()
    assertThat(c.ui.value.result).isNull()
    assertThat(world.albums.all.value.byId(album)?.count).isEqualTo(0)

    // Liberata un po' di memoria si riprova, e funziona.
    full = false
    c.dismissMessage()
    c.add(world.albums.all.value.byId(album)!!)
    assertThat((c.ui.value.result as AddResult.Added).added).isEqualTo(2)
  }

  @Test
  fun conLaMemoriaPienaIlNuovoAlbumDiceCheNonEStatoFatto() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    val failing = object : Albums by world.albums {
      override suspend fun create(title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome = throw IOException("No space left on device")
    }
    val c = AddToAlbumController(AddToAlbumRequest(all), failing, world.sharedAlbums, world.library, world.runner, world.screens, backgroundScope).also { it.start() }

    c.addToNewAlbum("Nuovo")

    assertThat(c.ui.value.message).isEqualTo(AlbumMessage.NotDone)
    assertThat(c.ui.value.busy).isFalse()
  }

  @Test
  fun daiNascostiUnNascostoEntraNellAlbumMaNonSiMostraFincheENascosto() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    val album = (world.albums.create("Mio", AlbumKind.MANUAL) as CreateOutcome.Created).id
    val c = chooser(world, AddToAlbumRequest(listOf(AlbumsTestWorld.media(2)), fromHidden = true))
    c.add(world.albums.all.value.byId(album)!!)
    assertThat((c.ui.value.result as AddResult.Added).added).isEqualTo(1)
    // D22: nell'elenco e nel conteggio non c'e', anche se e' un elemento dell'album.
    assertThat(world.albums.all.value.byId(album)?.count).isEqualTo(0)
    world.library.unhide(AlbumsTestWorld.media(2))
    assertThat(world.albums.all.value.byId(album)?.count).isEqualTo(1)
  }

  @Test
  fun leScelteSonoIMieAlbumManualiEICondivisiDoveLaTabellaLasciaAggiungere() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    world.albums.create("Mio", AlbumKind.MANUAL)
    world.albums.create("Oslo 2026", AlbumKind.SMART, SmartRules(null, null, "neve"))
    world.albumBy(other, "Si aggiunge", ShareLevel.ADD)
    world.albumBy(other, "Sola visione", ShareLevel.VIEW)
    val choices = chooser(world, AddToAlbumRequest(all)).ui.value.choices
    assertThat(choices.map { it.title }).containsExactly("Mio", "Si aggiunge")
    assertThat(choices.none { it is Album.Smart }).isTrue()
  }

  @Test
  fun unAlbumCondivisoApreSempreIlFoglioDiConfermaEMaiUnAggiuntaDiretta() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    val id = world.albumBy(other, "Si aggiunge", ShareLevel.ADD)
    val c = chooser(world, AddToAlbumRequest(all))
    c.add(c.ui.value.choices.single())
    assertThat(c.share.state.value).isInstanceOf(ShareSheetState.Plan::class.java)
    assertThat(c.ui.value.result).isNull()
    // Niente e' partito: il diario dell'orchestratore non ha operazioni.
    assertThat(world.diary.openOps()).isEmpty()
    // Annullare il foglio non lascia niente in sospeso.
    c.share.dismiss()
    assertThat(world.diary.openOps()).isEmpty()
    assertThat(world.albums.all.value.byId(id)?.count).isEqualTo(0)
  }

  @Test
  fun unAlbumNuovoManualeSiCreaConIMediaOfferti() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    val c = chooser(world, AddToAlbumRequest(all))
    c.addToNewAlbum("   ")
    assertThat(c.ui.value.titleProblem).isNotNull()
    assertThat(c.ui.value.result).isNull()
    c.addToNewAlbum("Vacanza")
    val created = c.ui.value.result as AddResult.Added
    assertThat(created.added).isEqualTo(2)
    assertThat(world.albums.all.value.byId(created.album)?.title).isEqualTo("Vacanza")
  }

  @Test
  fun unAlbumNuovoCondivisoApreIlFoglioConIlLivelloDiPartenzaESenzaINascosti() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    seed(world)
    val c = chooser(world, AddToAlbumRequest(all))
    c.addToNewSharedAlbum("Prova Oslo")
    val sheet = c.share.state.value as ShareSheetState.Plan
    // Q2: di partenza "Gli altri aggiungono"; il nascosto non si e' offerto e non e' nel piano.
    assertThat(sheet.level).isEqualTo(ShareLevel.ADD)
    assertThat(sheet.plan.items).hasSize(2)
    assertThat(world.diary.openOps()).isEmpty()
  }

  // --- Suggerite ---------------------------------------------------------------------------------------------------------------

  private suspend fun smartWorld(): Pair<AlbumsTestWorld, AlbumId> {
    val world = AlbumsTestWorld()
    world.library.put(world.photo(1), world.photo(2), world.photo(3), world.photo(4, hidden = true))
    world.smart = SmartMembership(
      confident = listOf(AlbumsTestWorld.media(1)),
      suggested = listOf(AlbumsTestWorld.media(2), AlbumsTestWorld.media(3), AlbumsTestWorld.media(4)),
    )
    val id = (world.albums.create("Oslo 2026", AlbumKind.SMART, SmartRules(null, null, "neve")) as CreateOutcome.Created).id
    return world to id
  }

  private fun TestScope.suggestions(world: AlbumsTestWorld, id: AlbumId) =
    SuggestionsController(id, SuggestionKind.SUGGESTED, world.albums, world.albums, world.runner, world.screens, backgroundScope)

  @Test
  fun leSuggerisonoSonoSenzaINascostiEUnGestoVaSullaSelezioneOSuTutte() = runTest(UnconfinedTestDispatcher()) {
    val (world, id) = smartWorld()
    val s = suggestions(world, id)
    assertThat(s.ui.value.count).isEqualTo(2)
    assertThat(s.ui.value.album?.title).isEqualTo("Oslo 2026")
    // Senza una selezione le azioni valgono per tutte; con una, per quelle.
    assertThat(s.target()).containsExactly(AlbumsTestWorld.media(2), AlbumsTestWorld.media(3))
    s.toggleSelecting()
    s.toggle(AlbumsTestWorld.media(3))
    assertThat(s.target()).containsExactly(AlbumsTestWorld.media(3))
  }

  @Test
  fun accettareFaEntrareLeFotoEdEscluderleLeTogliePerSempre() = runTest(UnconfinedTestDispatcher()) {
    val (world, id) = smartWorld()
    val s = suggestions(world, id)
    s.toggleSelecting()
    s.toggle(AlbumsTestWorld.media(2))
    s.accept()
    assertThat(s.ui.value.count).isEqualTo(1)
    assertThat(s.ui.value.selected).isEmpty()
    assertThat(s.ui.value.selecting).isFalse()
    assertThat(world.albums.all.value.byId(id)?.count).isEqualTo(2)
    s.exclude()
    assertThat(s.ui.value.count).isEqualTo(0)
    // Escluse per sempre: non tornano fra le suggerite, e nell'album ci sono solo le due di prima.
    assertThat(world.albums.all.value.byId(id)?.count).isEqualTo(2)
    assertThat((world.albums.all.value.byId(id) as Album.Smart).suggested).isEqualTo(0)
  }

  @Test
  fun selezionareTuttoEUnaSceltaENonUnaConferma() = runTest(UnconfinedTestDispatcher()) {
    val (world, id) = smartWorld()
    val s = suggestions(world, id)
    s.selectAll()
    assertThat(s.ui.value.selected).hasSize(2)
    s.clearSelection()
    assertThat(s.ui.value.selected).isEmpty()
    assertThat(world.albums.all.value.byId(id)?.count).isEqualTo(1)
  }
}
