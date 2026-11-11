package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.library.contract.GridLevel
import dev.pampa.mycloudgallery.core.library.contract.GridSpec
import dev.pampa.mycloudgallery.core.library.contract.GridStyle
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.testing.SimpleGridLayoutEngine
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.security.lock.AuthAvailability
import dev.pampa.mycloudgallery.core.security.lock.AuthResult
import dev.pampa.mycloudgallery.feature.library.collections.testing.CountingRecentlyDeletedSource
import dev.pampa.mycloudgallery.feature.library.collections.testing.CountingTrashMask
import dev.pampa.mycloudgallery.feature.library.collections.testing.FakeCollectionsWorld
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeGridEnvironment
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeThumbnailLoader
import dev.pampa.mycloudgallery.feature.library.collections.testing.fakeTrashedItems
import dev.pampa.mycloudgallery.feature.library.grid.MediaGrid
import dev.pampa.mycloudgallery.feature.library.grid.MediaGridState
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenAccessGate
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenController
import dev.pampa.mycloudgallery.feature.library.hidden.HiddenHost
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedController
import dev.pampa.mycloudgallery.feature.library.trash.RecentlyDeletedHost
import dev.pampa.mycloudgallery.feature.library.trash.TrashUiState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * **E-H1: i nascosti non si trovano mai** in nessuna superficie della Fase 3 di questo modulo, e si vedono solo dopo lo
 * sblocco. Il test scorre ogni superficie:
 *
 *  - l'indice, per ogni filtro della Libreria, ogni livello e ogni stile (tutte le posizioni, non solo le visibili);
 *  - i conteggi dell'elenco delle Raccolte e le viste che il viewer scorre;
 *  - le quattro griglie filtrate e la griglia della Libreria, **disegnate** e scorse, con un caricatore di miniature che
 *    ricorda ogni identita' per cui qualcuno ha chiesto un'immagine;
 *  - gli Eliminati di recente;
 *  - i Nascosti da chiusi.
 *
 * Il mondo finto ha nascosti veri di ogni genere (preferiti, video, screenshot, WhatsApp, normali): sono i casi in cui un
 * nascosto potrebbe finire in una raccolta se una schermata leggesse da una strada diversa dall'indice. Un controllo
 * positivo (i Nascosti sbloccati li chiedono davvero) prova che il test li saprebbe vedere.
 */
class NoHiddenLeakTest : ComposeScreenTest() {

  private val world = FakeCollectionsWorld()
  private val hidden: Set<MediaId> = world.hiddenIds
  private val loader = RecordingLoader(fakeThumbnailLoader())
  private val scope = CoroutineScope(Dispatchers.Unconfined)

  @After
  fun stop() {
    scope.cancel()
  }

  @Test
  fun ilMondoHaNascostiVeriDiOgniGenere() {
    val full = world.library.snapshot
    val indices = hidden.map { full.indexOf(it) }

    assertThat(hidden.size).isAtLeast(10)
    assertThat(indices.any { full.hasFlag(it, CellFlags.FAVORITE) }).isTrue()
    assertThat(indices.any { full.kindAt(it) == MediaKind.VIDEO }).isTrue()
    assertThat(indices.any { full.sourceAt(it) == MediaSource.SCREENSHOT }).isTrue()
    assertThat(indices.any { full.sourceAt(it) == MediaSource.WHATSAPP }).isTrue()
    assertThat(indices.any { full.sourceAt(it) == MediaSource.CAMERA }).isTrue()
    // E l'indice visibile non ne ha nessuno.
    assertThat(world.visibleIds.intersect(hidden)).isEmpty()
  }

  @Test
  fun indiceELayoutPerOgniFiltroLivelloEStileNonHannoNascosti() {
    for (filter in LibraryFilter.entries) {
      val view = world.repository.view(filter).value
      assertWithMessage("filtro $filter").that(idsOf(view).toSet().intersect(hidden)).isEmpty()
      for (level in GridLevel.entries) {
        for (style in GridStyle.entries) {
          val spec = GridSpec(level = level, style = style, columns = 4, widthPx = 1_000, gapPx = 2, densityDp = 2f)
          val layout = SimpleGridLayoutEngine().layout(view, spec)
          val seen = (0 until layout.itemCount).map { view.snapshot.idAt(view.snapshotIndex(layout.viewIndexAt(it))) }
          assertWithMessage("$filter $level $style").that(seen.toSet().intersect(hidden)).isEmpty()
          // Ogni cella del layout e' un elemento della vista: ne' di piu' ne' di meno.
          assertWithMessage("$filter $level $style").that(seen).hasSize(view.size)
          assertWithMessage("$filter $level $style").that(layout.sections.sumOf { it.count }).isEqualTo(view.size)
        }
      }
    }
  }

  @Test
  fun ilViewerScorreLaStessaVistaSenzaNascosti() {
    for (kind in CollectionKind.filters) {
      val items: ViewerItems = world.source.items(kind.filter!!)
      assertWithMessage("viewer di ${kind.code}").that(idsOf(items.view.value).toSet().intersect(hidden)).isEmpty()
    }
    assertThat(idsOf(world.source.items(LibraryFilter.ALL).view.value).toSet().intersect(hidden)).isEmpty()
  }

  @Test
  fun iNascostiDelleRaccolteNonDiconoQuantiSono() {
    // E-H1: da fuori non si capisce se ce ne sono. (CS-17: le raccolte-filtro con i loro conteggi stanno nella scheda Album.)
    val state = collectionsUiState(canTrash = true)
    assertThat(state.protectedRows.first { it.kind == CollectionKind.HIDDEN }.count).isNull()
  }

  @Test
  fun ogniSuperficieDisegnataESCorsaNonChiedeMaiUnNascosto() {
    val items = world.source.items(LibraryFilter.ALL)
    val surfaces = mutableListOf<Pair<String, @Composable () -> Unit>>()
    surfaces += "elenco delle Raccolte" to {
      CollectionsScreen(
        state = layoutOf(world),
        contentPadding = PaddingValues(),
        backdropOut = {},
        onOpenCollection = {},
        onOpenSettings = {},
        statusHeader = {},
        detail = {},
      )
    }
    for (kind in CollectionKind.filters) {
      surfaces += "raccolta ${kind.code}" to {
        CollectionGridScreen(kind, world.source.items(kind.filter!!), fakeGridEnvironment(loader = loader), { _, _ -> }, {}, {})
      }
    }
    val libraryGrids = mutableListOf<MediaGridState>()
    // Le due pagine dietro la credenziale, con la schermata di sistema aperta: chiuse, senza sbloccare mai.
    val lockedAuth = FakeAuthenticator().also { it.hold = CompletableDeferred() }
    val lockedHidden = HiddenController(HiddenAccessGate(TestClock(), 60_000L), world.hiddenLibrary, world.actions, scope)
    surfaces += "nascosti chiusi" to {
      HiddenHost(lockedHidden, fakeGridEnvironment(loader = loader), { false }, {}, lockedAuth)
    }
    val lockedTrash = trashController(TrashWorld(world, 1_800_000_000_000L), HiddenAccessGate(TestClock(), 60_000L), 1_800_000_000_000L)
    surfaces += "eliminati di recente chiusi" to {
      RecentlyDeletedHost(lockedTrash, loader, { false }, lockedAuth, onBack = {})
    }
    for (level in GridLevel.entries) {
      for (style in GridStyle.entries) {
        val state = fakeGridEnvironment(level, style, loader).gridStateFor(items.view.value, items)
        libraryGrids += state
        surfaces += "griglia $level $style" to { MediaGrid(state = state) }
      }
    }

    var current by mutableIntStateOf(0)
    // Ogni superficie e' una schermata a se': si compone da capo, mai riusando lo stato della precedente.
    show { key(current) { surfaces[current].second() } }

    for ((index, surface) in surfaces.withIndex()) {
      try {
        composeRule.runOnUiThread { current = index }
        composeRule.waitForIdle()
        // Le griglie si scorrono dalla cima al fondo, a passi: ogni riga passa almeno una volta per lo schermo.
        libraryGrids.firstOrNull { grid -> surface.first == "griglia ${grid.level} ${grid.style}" }?.let { grid ->
          val rows = grid.layout?.rowCount ?: 0
          for (row in 0..rows step SCROLL_STEP) {
            composeRule.runOnIdle { runBlocking { grid.listState.scrollToItem(row) } }
            composeRule.waitForIdle()
          }
        }
      } catch (e: Throwable) {
        throw AssertionError("superficie: ${surface.first}", e)
      }
      assertWithMessage("superficie: ${surface.first}").that(loader.seen.intersect(hidden)).isEmpty()
    }
    // Il controllo che serve: la prova vedeva qualcosa. Le miniature chieste sono tante e tutte visibili.
    assertThat(loader.seen).isNotEmpty()
    assertThat(world.visibleIds).containsAtLeastElementsIn(loader.seen)
  }

  /** Un cestino di sistema di prova con due nascosti eliminati (eliminati dal telefono dopo averli nascosti). */
  private class TrashWorld(world: FakeCollectionsWorld, now: Long) {
    val trashed = fakeTrashedItems(now, count = 8)
    val hiddenRefs = setOf(trashed[1].ref, trashed[5].ref)
    val source = CountingRecentlyDeletedSource(FakeRecentlyDeletedSource(trashed))
    val mask = CountingTrashMask(world.trashMask(*hiddenRefs.toTypedArray()))
  }

  private fun trashController(trash: TrashWorld, gate: HiddenAccessGate, now: Long) = RecentlyDeletedController(
    gate = gate,
    source = trash.source,
    mask = trash.mask,
    intents = FakePhoneMediaIntents(canTrash = true),
    scope = scope,
  ) { now }

  @Test
  fun gliEliminatiDiRecenteDaChiusiNonMostranoNienteENonLeggono() {
    val now = 1_800_000_000_000L
    val trash = TrashWorld(world, now)
    val auth = FakeAuthenticator()
    auth.hold = CompletableDeferred() // la schermata di sistema resta aperta: non si sblocca mai
    val controller = trashController(trash, HiddenAccessGate(TestClock(), 60_000L), now)
    show { RecentlyDeletedHost(controller, loader, { false }, auth, onBack = {}) }

    // Nemmeno la' dentro: senza sblocco non si legge il cestino, non si chiede quali sono nascosti, nessuna miniatura.
    assertThat(auth.calls).isEqualTo(1)
    assertThat(trash.source.reads).isEqualTo(0)
    assertThat(trash.mask.asked).isEqualTo(0)
    assertThat(loader.seen).isEmpty()
    assertThat(controller.state.value).isNotInstanceOf(TrashUiState.Loaded::class.java)
  }

  @Test
  fun gliEliminatiDiRecenteRifiutatiNonMostranoNiente() {
    val now = 1_800_000_000_000L
    val trash = TrashWorld(world, now)
    val auth = FakeAuthenticator(result = AuthResult.Cancelled)
    val controller = trashController(trash, HiddenAccessGate(TestClock(), 60_000L), now)
    show { RecentlyDeletedHost(controller, loader, { false }, auth, onBack = {}) }

    assertThat(trash.source.reads).isEqualTo(0)
    assertThat(loader.seen).isEmpty()
  }

  @Test
  fun gliEliminatiDiRecenteSbloccatiMostranoINascostiColSegno() {
    val now = 1_800_000_000_000L
    val trash = TrashWorld(world, now)
    val controller = trashController(trash, HiddenAccessGate(TestClock(), 60_000L), now)
    show { RecentlyDeletedHost(controller, loader, { false }, FakeAuthenticator(), onBack = {}) }

    // Controllo positivo: sbloccati ci sono, tutti, e i nascosti sono riconosciuti.
    composeRule.runOnIdle {
      val loaded = controller.state.value as TrashUiState.Loaded
      assertThat(loaded.entries).hasSize(trash.trashed.size)
      assertThat(loaded.entries.filter { it.hidden }.map { it.item.ref }.toSet()).isEqualTo(trash.hiddenRefs)
    }
    trash.hiddenRefs.forEach { assertThat(loader.seen).contains(RecentlyDeletedController.trashEntryId(it)) }
  }

  @Test
  fun iNascostiDaChiusiNonLiMostranoNeLiLeggono() {
    val gate = HiddenAccessGate(TestClock(), 60_000L)
    val auth = FakeAuthenticator()
    auth.hold = CompletableDeferred() // la schermata di sistema resta aperta: non si sblocca mai
    val controller = HiddenController(gate, world.hiddenLibrary, world.actions, scope)
    show { HiddenHost(controller, fakeGridEnvironment(loader = loader), { false }, {}, auth) }

    assertThat(auth.calls).isEqualTo(1)
    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
    assertThat(loader.seen).isEmpty()
  }

  @Test
  fun senzaCredenzialeNonSiApreMaiEUnNegatoNonLeggeNiente() {
    val gate = HiddenAccessGate(TestClock(), 60_000L)
    val denied = FakeAuthenticator(availability = AuthAvailability.NO_SCREEN_LOCK)
    val controller = HiddenController(gate, world.hiddenLibrary, world.actions, scope)
    show { HiddenHost(controller, fakeGridEnvironment(loader = loader), { false }, {}, denied) }

    assertThat(world.hiddenLibrary.reads).isEqualTo(0)
    assertThat(loader.seen).isEmpty()
  }

  @Test
  fun controlloPositivo_sbloccatiINascostiLiChiedonoDavvero() {
    val gate = HiddenAccessGate(TestClock(), 60_000L)
    val controller = HiddenController(gate, world.hiddenLibrary, world.actions, scope)
    show { HiddenHost(controller, fakeGridEnvironment(loader = loader), { false }, {}, FakeAuthenticator()) }

    assertThat(loader.seen).isNotEmpty()
    assertThat(hidden).containsAtLeastElementsIn(loader.seen)
    assertThat(world.hiddenLibrary.reads).isEqualTo(1)
  }

  @Test
  fun nascondereDaUnaSuperficieLoTogliDaTutteLeAltre() = runBlocking {
    // Un preferito: sta nei Preferiti, nella Libreria e, secondo cos'e', in Video, Screenshot o WhatsApp.
    val target = idsOf(world.source.items(LibraryFilter.FAVORITES).view.value).first()
    val before = LibraryFilter.entries.associateWith { world.source.items(it).view.value }
    val memberOf = before.filterValues { it.viewIndexOf(target) >= 0 }.keys
    assertThat(memberOf).containsAtLeast(LibraryFilter.ALL, LibraryFilter.FAVORITES)

    world.actions.setHidden(setOf(target), true)

    for (filter in LibraryFilter.entries) {
      val view = world.source.items(filter).view.value
      assertWithMessage("filtro $filter").that(view.viewIndexOf(target)).isEqualTo(-1)
      val expected = before.getValue(filter).size - if (filter in memberOf) 1 else 0
      assertWithMessage("filtro $filter").that(view.size).isEqualTo(expected)
    }
    assertThat(idsOf(world.hiddenLibrary.read().view.value)).contains(target)

    // E mostrarlo lo riporta in ogni raccolta a cui appartiene.
    world.actions.setHidden(setOf(target), false)
    for (filter in memberOf) {
      assertWithMessage("filtro $filter").that(world.source.items(filter).view.value.viewIndexOf(target)).isAtLeast(0)
    }
  }

  @Test
  fun soloIlPackageHiddenLeggeINascosti() {
    val root = File("src/main/kotlin/dev/pampa/mycloudgallery/feature/library")
    val offenders = root.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .filterNot { it.invariantSeparatorsPath.contains("/hidden/") || it.invariantSeparatorsPath.contains("/collections/testing/") }
      .filter { file ->
        val text = file.readText()
        "HiddenLibrary" in text || "hiddenRows" in text
      }
      .map { it.name }
      .toList()

    assertWithMessage("Solo hidden/ puo' leggere i nascosti (E-H1): $offenders").that(offenders).isEmpty()
  }

  @Test
  fun ilFiltroDeiNascostiDelCestinoSegueIlRiferimento() {
    // Il filtro dei nascosti del cestino segue il riferimento, non l'identita': due riferimenti diversi non si confondono.
    val a = MediaRef.Phone("external_primary", 10, "image/jpeg")
    val b = MediaRef.Phone("external_primary", 11, "image/jpeg")
    val mask = world.trashMask(a)

    val result = runBlocking { mask.hiddenAmong(listOf(a, b)) }

    assertThat(result).containsExactly(a)
  }

  private companion object {
    const val SCROLL_STEP = 12
  }

  /**
   * H5: le copertine delle Raccolte (l'ultimo ricordo, un viaggio) si cercano nella Libreria visibile. Una copertina scelta prima che la foto fosse
   * nascosta non ha una richiesta e non si chiede mai al caricatore: la riga mostra l'icona. Il controllo positivo: la copertina visibile si chiede.
   */
  @Test
  fun unaCopertinaNascostaNonSiChiedeMaiNelleRaccolte() {
    val covers = CollectionCovers(loader, world.source.items(LibraryFilter.ALL))
    val hiddenCover = hidden.first()
    val visibleCover = world.visibleIds.first()
    for (id in hidden) assertWithMessage("richiesta per un nascosto").that(covers.request(covers.view.value, id)).isNull()

    val group = CollectionEntryGroup(
      title = "Ricordi",
      code = CollectionSections.MEMORIES,
      entries = listOf(
        CollectionEntry("m_nascosto", "Un ricordo", "3 foto", Icons.Rounded.History, dev.antigravity.fluidengine.ui.theme.FluidTone.Neutral, cover = hiddenCover),
        CollectionEntry("m_visibile", "Un altro ricordo", "5 foto", Icons.Rounded.History, dev.antigravity.fluidengine.ui.theme.FluidTone.Neutral, cover = visibleCover),
      ),
    )
    show {
      androidx.compose.runtime.CompositionLocalProvider(LocalCollectionCovers provides covers) {
        CollectionsScreen(
          state = layoutOf(world, extra = listOf(group)),
          contentPadding = PaddingValues(),
          backdropOut = {},
          onOpenCollection = {},
          onOpenSettings = {},
          statusHeader = {},
          detail = {},
        )
      }
    }
    composeRule.waitForIdle()

    assertThat(loader.seen).doesNotContain(hiddenCover)
    assertThat(loader.seen.intersect(hidden)).isEmpty()
    assertThat(loader.seen).contains(visibleCover)
  }
}
