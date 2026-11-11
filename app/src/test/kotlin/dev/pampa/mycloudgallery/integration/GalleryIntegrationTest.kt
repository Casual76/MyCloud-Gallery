package dev.pampa.mycloudgallery.integration

import android.app.Application
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.PixelRect
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.contract.ViewerRequest
import dev.pampa.mycloudgallery.core.library.store.SnapshotStore
import dev.pampa.mycloudgallery.core.library.testing.FakeViewerAnchor
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.mediastore.diagnostics.PhoneLibraryDiagnostics
import dev.pampa.mycloudgallery.core.mediastore.meta.DefaultMetadataPass
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneLibrarySync
import dev.pampa.mycloudgallery.core.mediastore.sync.DefaultPhoneSyncRunner
import dev.pampa.mycloudgallery.core.mediastore.sync.DeferredFullHashes
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneLibraryStats
import dev.pampa.mycloudgallery.core.mediastore.sync.RoomPhoneCatalogReads
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeRecentlyDeletedSource
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.places.contract.PlacesAttribution
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import dev.pampa.mycloudgallery.feature.viewer.ViewerOverlay
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.gallery.LibraryDiagnostics
import dev.pampa.mycloudgallery.gallery.GalleryParts
import dev.pampa.mycloudgallery.gallery.PhoneInputs
import dev.pampa.mycloudgallery.gallery.PhoneLibrary
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P11, "Fatto quando" della riga P11 del §4.3: la galleria del telefono **montata come in produzione** (`GalleryGraph`:
 * catalogo, indice della timeline, sincronizzazione, azioni, viewer) sopra il MediaStore finto di P2. Cambiano solo le
 * parti che parlano con Android: il MediaStore (`FakeMediaStore`), i permessi e gli intent.
 *
 * - la Libreria si popola dalla lettura del telefono;
 * - una modifica esterna (una foto nuova scattata da un'altra app) compare da sola, con l'app in primo piano;
 * - il viewer si apre sugli elementi dell'indice, con i servizi del grafo;
 * - un nascosto non compare mai: ne' nell'indice, ne' nelle raccolte, ne' nell'istantanea su file (E-H1);
 * - con l'accesso parziale la Libreria mostra solo la selezione e non deduce assenze (E-P1).
 *
 * Il telefono e' in **inglese** (`en-rUS`): le date restano in italiano.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS-w412dp-h915dp-xhdpi")
class GalleryIntegrationTest {

  private val compose = createAndroidComposeRule<ComponentActivity>()
  private val temp = TemporaryFolder()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(temp).around(RegisterComponentActivity()).around(compose)

  private class RegisterComponentActivity : TestWatcher() {
    override fun starting(description: Description) {
      val app = ApplicationProvider.getApplicationContext<Application>()
      val info = ActivityInfo().apply {
        name = ComponentActivity::class.java.name
        packageName = app.packageName
        flags = flags or ActivityInfo.FLAG_HARDWARE_ACCELERATED
      }
      shadowOf(app.packageManager).addOrUpdateActivity(info)
    }
  }

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val store = FakeMediaStore().apply { addVolume(PRIMARY) }
  private val access = FakePhoneAccessReader(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(PRIMARY)))
  private val ready = object : FamilySetupRepository {
    override val state: StateFlow<SetupState> = MutableStateFlow(SetupState.Ready(admin = false))
  }
  private lateinit var replica: File
  private val gallery: GalleryGraph by lazy {
    replica = temp.newFolder("replica")
    GalleryGraph(
      context = app,
      scope = scope,
      parts = GalleryParts(
        databaseName = null,
        replicaDir = replica,
        preferencesFile = File(temp.root, "library.preferences_pb"),
        access = access,
        intents = FakePhoneMediaIntents(),
        recentlyDeleted = FakeRecentlyDeletedSource(),
        phone = ::fakePhone,
      ),
      setup = ready,
      ownerAccount = { AccountId.of("test") },
      clock = { NOW },
    )
  }

  /** La lettura del telefono con le classi vere di P2 sopra il MediaStore finto (lo stesso montaggio di `PhoneLibraryGraph`). */
  private fun fakePhone(inputs: PhoneInputs): PhoneLibrary {
    val reads = RoomPhoneCatalogReads(inputs.database)
    val stats = PhoneLibraryStats()
    val meta = DefaultMetadataPass(store, store, access, inputs.catalog, reads, inputs.extractor, clock = inputs.clock, stats = stats)
    val sync = DefaultPhoneLibrarySync(
      store, access, inputs.catalog, reads, clock = inputs.clock, stats = stats, metadataPass = meta, priority = inputs.priority,
    )
    return PhoneLibrary(
      sync = sync,
      access = access,
      metadataPass = meta,
      runner = DefaultPhoneSyncRunner(sync, meta, inputs.priority),
      stats = stats,
      diagnostics = PhoneLibraryDiagnostics(sync, access, stats) { inputs.catalog.mediaStoreCursors() },
      deferredHashes = DeferredFullHashes(store, access, inputs.catalog, inputs.database),
    )
  }

  @After
  fun tearDown() = closeScope(scope)

  // --- La Libreria si popola -----------------------------------------------------------------------------------

  @Test
  fun laLibreriaSiPopolaDalTelefono() = runBlocking<Unit> {
    repeat(5) { store.put(photo(it + 1L, minutesAgo = 60L * (it + 1))) }
    store.put(video(6, minutesAgo = 30))

    val view = startAndSync()

    assertThat(view.size).isEqualTo(6)
    // Dal piu' recente (il video, mezz'ora fa) al meno recente.
    assertThat(view.snapshot.kindAt(view.snapshotIndex(0)).isVideo).isTrue()
    // Le raccolte-filtro leggono la stessa vista dell'indice.
    assertThat(awaitView(LibraryFilter.VIDEOS) { it.size == 1 }.size).isEqualTo(1)
    // Ogni cella ha un riferimento ai byte del telefono (miniatura e viewer).
    repeat(view.size) { i -> assertThat(gallery.repository.refOf(view.snapshot.idAt(view.snapshotIndex(i)))).isNotNull() }
  }

  // --- Una modifica esterna si vede ---------------------------------------------------------------------------

  @Test
  fun unaFotoNuovaScattataFuoriCompareDaSola() = runBlocking<Unit> {
    store.put(photo(1, minutesAgo = 120))
    store.put(photo(2, minutesAgo = 60))
    startAndSync()
    gallery.onForeground()
    awaitIdle()

    // Un'altra app (la fotocamera) aggiunge una foto: il MediaStore lo annuncia, la lettura in primo piano la porta nel
    // catalogo e l'indice si aggiorna a pezzi (nessuna ricostruzione).
    store.put(photo(3, minutesAgo = 1))
    val view = awaitView { it.size == 3 }

    assertThat(view.size).isEqualTo(3)
    gallery.onBackground()
  }

  // --- CA-4 (Fase 10): la versione fermata dal manifest -------------------------------------------------------

  /**
   * CA-4 della revisione funzionale della Fase 10 (E-REL6): con la versione fermata la Libreria non si mostra, e la lettura del telefono agganciata al primo piano non
   * parte. La sincronizzazione qui registra gli agganci; [GalleryParts.releaseStopped] dice ogni volta che cosa ha risposto.
   */
  @Test
  fun ca4_conLaVersioneFermataLaLetturaDelTelefonoNonSiAgganciaAlPrimoPiano() = runBlocking<Unit> {
    var stopped = true
    val asked = kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val attached = java.util.concurrent.atomic.AtomicInteger()
    val yielding = java.util.concurrent.atomic.AtomicReference<dev.pampa.mycloudgallery.core.rules.run.YieldToApp?>()
    val recorder = object : dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync {
      override val status: StateFlow<SyncStatus> = dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneLibrarySync.status

      override suspend fun syncNow(reason: SyncReason) = dev.pampa.mycloudgallery.core.mediastore.noop.NoPhoneLibrarySync.syncNow(reason)

      override fun attach(scope: CoroutineScope) {
        yielding.set(scope.coroutineContext[dev.pampa.mycloudgallery.core.rules.run.YieldToApp])
        attached.incrementAndGet()
      }

      override fun detach() = Unit
    }
    val stoppable = GalleryGraph(
      context = app,
      scope = scope,
      parts = GalleryParts(
        databaseName = null,
        replicaDir = temp.newFolder("replica-ca4"),
        preferencesFile = File(temp.root, "library-ca4.preferences_pb"),
        access = access,
        intents = FakePhoneMediaIntents(),
        recentlyDeleted = FakeRecentlyDeletedSource(),
        phone = ::fakePhone,
        benchSync = recorder,
        releaseStopped = { stopped.also { asked.trySend(it) } },
      ),
      setup = ready,
      ownerAccount = { AccountId.of("test") },
      clock = { NOW },
    )
    stoppable.startWhenReady()
    withTimeout(TIMEOUT_MS) { stoppable.started.first { it } }

    stoppable.onForeground()
    assertThat(withTimeout(TIMEOUT_MS) { asked.receive() }).isTrue()
    // Il controllo che la prova non passi a vuoto: con la versione normale, al ritorno in primo piano, si aggancia (una volta: prima non si era agganciata).
    stoppable.onBackground()
    stopped = false
    stoppable.onForeground()
    assertThat(withTimeout(TIMEOUT_MS) { asked.receive() }).isFalse()
    until("la lettura del telefono agganciata") { attached.get() >= 1 }
    assertThat(attached.get()).isEqualTo(1)
    dev.pampa.mycloudgallery.work.UiWorkActivity.budget.interacted()
    assertThat(yielding.get()?.yielding).isTrue()
    stoppable.onBackground()
    assertThat(yielding.get()?.yielding).isFalse()
    stoppable.shutdown()
  }

  // --- Il viewer si apre --------------------------------------------------------------------------------------

  @Test
  fun ilViewerSiApreSugliElementiDellIndice() {
    val view = runBlocking {
      store.put(photo(1, minutesAgo = 60))
      store.put(photo(2, minutesAgo = 30))
      startAndSync()
    }
    val start = view.snapshot.idAt(view.snapshotIndex(0))
    val anchor = FakeViewerAnchor().apply { bounds[start] = PixelRect(100f, 400f, 300f, 600f) }
    val request = ViewerRequest(gallery.viewerItems(LibraryFilter.ALL), start, anchor, secure = false)

    compose.setContent {
      MyCloudGalleryTheme {
        val modal = rememberFluidGlassModalHostState()
        val backdrop = rememberGlassBackdrop()
        CompositionLocalProvider(LocalFluidGlassModalHostState provides modal) {
          Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
            ViewerOverlay(request = request, onClosed = {}, services = gallery.viewerServices)
          }
          FluidGlassModalHost(state = modal, backdrop = backdrop)
        }
      }
    }
    compose.waitForIdle()

    // Il titolo della barra e' la data dello scatto, in italiano anche con il telefono in inglese (P11).
    val day = Instant.ofEpochMilli(NOW - 30 * MINUTE).atZone(ZoneId.systemDefault()).toLocalDate()
    val italian = "${day.dayOfMonth} ${ITALIAN_MONTHS[day.monthValue - 1]} ${day.year}"
    assertWithMessage("titolo del viewer: $italian").that(compose.onAllNodesWithText(italian, substring = true).fetchSemanticsNodes()).isNotEmpty()
  }

  // --- Un nascosto non compare mai (E-H1) -------------------------------------------------------------------

  @Test
  fun unNascostoNonCompareMai() = runBlocking<Unit> {
    repeat(4) { store.put(photo(it + 1L, minutesAgo = 10L * (it + 1))) }
    store.put(video(5, minutesAgo = 5))
    val before = startAndSync()
    val secret = before.snapshot.idAt(before.snapshotIndex(0))

    gallery.actions.setHidden(setOf(secret), true)
    val after = awaitView { it.viewIndexOf(secret) < 0 }

    assertThat(after.size).isEqualTo(4)
    for (filter in LibraryFilter.entries) {
      val items = gallery.collectionItems.items(filter)
      assertWithMessage("filtro $filter").that(items.view.value.viewIndexOf(secret)).isLessThan(0)
    }
    // Sull'istantanea su file non c'e'.
    gallery.repository.flush()
    val onDisk = SnapshotStore(replica).read()
    assertThat(onDisk?.snapshot?.indexOf(secret) ?: -1).isLessThan(0)
    // Solo i Nascosti lo leggono, e solo da li' (dopo lo sblocco, che qui e' implicito).
    val hidden = gallery.hiddenLibrary.read()
    assertThat(hidden.view.value.viewIndexOf(secret)).isAtLeast(0)
    assertThat(hidden.view.value.size).isEqualTo(1)

    // Una nuova lettura del telefono non lo riporta indietro.
    gallery.core().phone.sync.syncNow(SyncReason.MANUAL)
    assertThat(gallery.repository.view(LibraryFilter.ALL).value.viewIndexOf(secret)).isLessThan(0)

    // "Mostra": torna nella Libreria.
    gallery.actions.setHidden(setOf(secret), false)
    assertThat(awaitView { it.viewIndexOf(secret) >= 0 }.size).isEqualTo(5)
  }

  // --- Accesso parziale (E-P1) --------------------------------------------------------------------------------

  @Test
  fun conLAccessoParzialeSiVedeSoloLaSelezioneSenzaAssenze() = runBlocking<Unit> {
    repeat(4) { store.put(photo(it + 1L, minutesAgo = 10L * (it + 1))) }
    startAndSync()

    // "Foto selezionate": solo due righe visibili adesso.
    access.set(PhoneAccess(MediaAccess.PARTIAL, mediaLocation = true, volumes = setOf(PRIMARY)))
    store.setSelection(PRIMARY, setOf(1L, 3L))
    gallery.core().phone.sync.syncNow(SyncReason.PERMISSION_CHANGED)

    val visible = withTimeout(TIMEOUT_MS) { gallery.visibleTimeline.view(LibraryFilter.ALL).first { it.size == 2 } }
    assertThat(visible.size).isEqualTo(2)
    // Il catalogo non ha segnato assenze: l'indice completo ha ancora tutto.
    assertThat(gallery.repository.view(LibraryFilter.ALL).value.size).isEqualTo(4)
  }

  // --- Diagnostica e licenze -----------------------------------------------------------------------------------

  /** I contributori della galleria usano solo codici del catalogo e chiavi dell'elenco chiuso (solo numeri ed enum). */
  @Test
  fun laDiagnosticaDellaGalleriaEsportaSoloChiaviAmmesse() = runBlocking<Unit> {
    store.put(photo(1, minutesAgo = 10))
    startAndSync()

    val items = gallery.phoneDiagnostics.collect() + LibraryDiagnostics(gallery).collect()

    assertThat(items.map { it.code.id }).containsAtLeast("PHONE_ACCESS", "PHONE_SYNC", "PHONE_PLACES", "PHONE_INDEX", "PHONE_THUMBNAILS")
    val outsideKeys = items.flatMap { it.export.keys }.filterNot(ExportKeys::isAllowed).distinct()
    assertWithMessage("chiavi fuori da ExportKeys: $outsideKeys").that(outsideKeys).isEmpty()
    val outsideCodes = items.map { it.code }.filterNot { it in DiagCatalog }.map { it.id }
    assertWithMessage("codici fuori dal catalogo: $outsideCodes").that(outsideCodes).isEmpty()
    // Nessun valore testuale: solo numeri, flag ed enumerati.
    items.flatMap { it.export.values }.forEach { value ->
      assertThat(value is ExportValue.Num || value is ExportValue.Flag || value is ExportValue.Enum).isTrue()
    }
  }

  /** L'attribuzione di GeoNames in Impostazioni → Informazioni e licenze e' quella del dataset (CC BY 4.0). */
  @Test
  fun lAttribuzioneDeiLuoghiNelleLicenzeEQuellaDelDataset() {
    assertThat(app.getString(dev.pampa.mycloudgallery.feature.settings.R.string.about_geonames_attribution)).isEqualTo(PlacesAttribution.TEXT)
  }

  // --- Supporto ---------------------------------------------------------------------------------------------

  private suspend fun startAndSync(): TimelineView {
    gallery.startWhenReady()
    withTimeout(TIMEOUT_MS) { gallery.started.first { it } }
    gallery.core().phone.sync.syncNow(SyncReason.APP_START)
    val expected = store.rowCount()
    return awaitView { it.size == expected }
  }

  private suspend fun awaitIdle() {
    withTimeout(TIMEOUT_MS) { gallery.phoneStatus.first { it is SyncStatus.Idle && it.atMs > 0 } }
  }

  private suspend fun awaitView(filter: LibraryFilter = LibraryFilter.ALL, predicate: (TimelineView) -> Boolean): TimelineView =
    withTimeout(TIMEOUT_MS) { gallery.repository.view(filter).first(predicate) }

  private fun FakeMediaStore.rowCount(): Int = (1L..MAX_ID).count { row(PRIMARY, it) != null }

  private fun photo(id: Long, minutesAgo: Long) = MediaStoreRow(
    volume = PRIMARY, id = id, type = RowType.IMAGE, displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
    mime = "image/jpeg", size = 4_000, dateModifiedMs = NOW - minutesAgo * MINUTE + 1_000, dateTakenMs = NOW - minutesAgo * MINUTE,
  )

  private fun video(id: Long, minutesAgo: Long) = MediaStoreRow(
    volume = PRIMARY, id = id, type = RowType.VIDEO, displayName = "VID_$id.mp4", relativePath = "DCIM/Camera/",
    mime = "video/mp4", size = 6_000, dateModifiedMs = NOW - minutesAgo * MINUTE + 1_000, dateTakenMs = NOW - minutesAgo * MINUTE,
    durationMs = 3_000,
  )

  private companion object {
    const val PRIMARY = "external_primary"
    /** 12/10/2026 12:00 UTC: lontano dalla mezzanotte, il giorno e' lo stesso in ogni fuso europeo. */
    const val NOW: Long = 1_791_806_400_000L
    const val MINUTE: Long = 60_000L
    val TIMEOUT_MS: Long = Attese.TETTO_MS
    const val MAX_ID: Long = 50L
    val ITALIAN_MONTHS = listOf(
      "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
    )
  }
}
