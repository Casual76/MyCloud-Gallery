package dev.pampa.mycloudgallery.integration

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.backup.FiveBInputs
import dev.pampa.mycloudgallery.backup.IncomingStates
import dev.pampa.mycloudgallery.core.backup.contract.DestinationArea
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSaveOutcome
import dev.pampa.mycloudgallery.core.backup.contract.IncomingSavePlan
import dev.pampa.mycloudgallery.core.backup.contract.IncomingStream
import dev.pampa.mycloudgallery.core.backup.contract.NasDestination
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.integrations.receive.DefaultIncomingUploads
import dev.pampa.mycloudgallery.feature.integrations.receive.ImageSize
import dev.pampa.mycloudgallery.feature.integrations.receive.IncomingStager
import android.media.ExifInterface
import android.net.Uri
import dev.pampa.mycloudgallery.core.backup.contract.EditBegin
import dev.pampa.mycloudgallery.core.backup.contract.EditOutcome
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.metadata.extract.AndroidPreviewDecoder
import dev.pampa.mycloudgallery.core.metadata.strip.DefaultLocationStripper
import dev.pampa.mycloudgallery.core.metadata.strip.LocationRechecks
import dev.pampa.mycloudgallery.feature.integrations.share.CacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.LocationFreeShare
import dev.pampa.mycloudgallery.feature.integrations.share.SharePreparation
import dev.pampa.mycloudgallery.feature.integrations.share.UriRevoker
import dev.pampa.mycloudgallery.integrations.AppShareSource
import dev.pampa.mycloudgallery.integrations.IncomingSpace
import java.time.ZoneId
import dev.pampa.mycloudgallery.integrations.IntegrationsGraph
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.AppLockSettings
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentLabels
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import dev.pampa.mycloudgallery.work.BackupStages
import java.io.ByteArrayInputStream
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.TrashOutcome
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaRewriter
import dev.pampa.mycloudgallery.core.metadata.write.WrittenCheck
import dev.pampa.mycloudgallery.core.metadata.write.WrittenFileCheck
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La **5B con il grafo vero** (P14; docs/fase5-progetto.md §4.4 riga P14): `GalleryGraph` con NAS, backup e 5B sopra il NAS finto di core-nas
 * (`InMemorySmbBackend`) e il MediaStore finto con i byte delle foto. Finti solo il NAS e il telefono (dialoghi di sistema e riscrittore
 * compresi); catalogo, diari, motori, cancelli, adattatori e porte sono quelli dell'app.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FiveBIntegrationTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val time = TestTime()
  private val devices = mutableListOf<TestDevice>()
  private val rigs = mutableListOf<BackupRig>()

  /** I dialoghi di sistema finti: registrano le richieste (l'effetto lo da' `FakeMediaStore.resolveRequest`, come l'utente). */
  private val phoneIntents = FakePhoneMediaIntents(canTrash = true)

  /** Il controllo della copia corretta: in questi test si scrive solo con l'estrattore vero dove serve; qui ogni copia vale. */
  private val anyCopyIsValid = WrittenFileCheck { _, _, _ -> WrittenCheck.VALID }

  @After
  fun tearDown() {
    rigs.forEach { runBlocking { it.gallery.shutdown() } }
    devices.forEach { it.close() }
  }

  private fun device(name: String, backend: InMemorySmbBackend, flags: FeatureFlags = DEBUG_LIKE): TestDevice =
    TestDevice(app, File(temp.root, name), TestPlatform(time, backend), flags).also { devices += it }

  private fun rig(device: TestDevice): BackupRig = BackupRig(
    app = app,
    device = device,
    account = TestNas.ADMIN_USER,
    fiveB = { store: FakeMediaStore ->
      FiveBInputs(
        intents = phoneIntents,
        rewriter = { FakePhoneMediaRewriter(store) },
        fileCheck = { anyCopyIsValid },
        bootCounter = { 1L },
        rowFolders = null,
      )
    },
  ).also { rigs += it }

  /** Un telefono con la famiglia fondata e [count] foto vecchie (oltre i 30 giorni di "Libera spazio"), tutte al sicuro dopo un giro. */
  private suspend fun phoneWithSafePhotos(backend: InMemorySmbBackend, count: Int): Pair<BackupRig, List<MediaId>> {
    val phone = device("telefono", backend)
    phone.graph.start()
    phone.graph.foundFamily()
    val rig = rig(phone)
    repeat(count) { i ->
      BackupRig.photo(rig.store, id = i + 1L, bytes = BackupRig.jpeg(tone = 30 + 50 * i), takenAt = time.now() - (90 - i) * DAY)
    }
    rig.open()
    rig.chooseFolder("Pixel di prova")
    val last = checkNotNull(checkNotNull(rig.round()) { "il giro non e' partito" }.last)
    assertWithMessage("giro: $last").that(last.done).isEqualTo(count)
    // I media del telefono (la Libreria puo' avere anche dei solo-NAS messi dal test): quelli con una copia del telefono.
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val view = withTimeout(60_000) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.size >= count } }
    val ids = (0 until view.size).map { view.snapshot.idAt(view.snapshotIndex(it)) }
      .filter { id -> catalog.media(id)?.locations?.any { it.isPhone && it.isPresent } == true }
    assertThat(ids).hasSize(count)
    return rig to ids
  }

  // --- "Libera spazio" con una copia rovinata dal PC ------------------------------------------------------------------------------

  /**
   * F37 su JVM: due foto al sicuro; il PC rovina la copia sul NAS di una delle due. "Libera spazio" la rilegge sul momento e la **esclude**
   * (la copia sul NAS non e' piu' quella verificata), toglie dal telefono solo l'altra dopo il dialogo di sistema, e il catalogo dice che
   * quella e' ormai solo sul NAS; la foto con la copia rovinata resta sul telefono.
   */
  @Test
  fun liberaSpazioEscludeLaCopiaRovinataDalPcELiberaIlResto() = runBlocking {
    val backend = TestNas.backend(time)
    val (rig, ids) = phoneWithSafePhotos(backend, count = 2)
    val backup = rig.backup
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog

    // Il PC rovina una copia (stessa dimensione, byte diversi): il catalogo non lo sa ancora.
    val stored = filesUnder(backend, TestNas.ADMIN_USER, "Backup").sorted()
    assertThat(stored).hasSize(2)
    val damaged = stored.first()
    val original = checkNotNull(backend.bytes(TestNas.ADMIN_USER, damaged))
    backend.put(TestNas.ADMIN_USER, damaged, original.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
    val damagedMedia = ids.single { id -> catalog.media(id)!!.locations.any { (it.location as? Location.Nas)?.path?.value == damaged } }
    val healthy = ids.single { it != damagedMedia }

    // La pagina: il piano (tutto il sicuro), la conferma con un tocco, la verifica sul momento.
    val free = backup.fiveB.freeSpace
    val plan = free.plan(FreeSpaceFilter(excludeRecentDays = 0))
    assertWithMessage("piano: ${plan.excluded}").that(plan.items.map { it.mediaId }.toSet()).containsExactlyElementsIn(ids)
    val run = free.verify(plan, TestConfirmations.confirm(plan, backup.clock.monotonicMs()), 5.minutes)
    assertWithMessage("giro: $run").that(run.state).isEqualTo(FreeSpaceRunState.READY)
    assertThat(run.readyItems).isEqualTo(1)
    // La rilettura sul momento non torna: quella copia non e' verificata e la foto resta sul telefono.
    assertWithMessage("esclusi: ${run.dropped}").that(run.dropped[FreeSpaceExclusion.NOT_VERIFIED]).isEqualTo(1)

    // Il dialogo di sistema: l'utente dice si', Android toglie la riga; l'esito si registra.
    val batch = checkNotNull(free.nextBatch(run))
    assertThat(batch.items.map { it.mediaId }).containsExactly(healthy)
    batch.items.forEach { rig.store.trash(it.phone.volume, it.phone.mediaStoreId) }
    val done = free.recordOutcome(batch, TrashOutcome.Confirmed(batch.items.map { it.phone }.toSet()))
    assertThat(done.freedItems).isEqualTo(1)
    assertThat(done.state).isEqualTo(FreeSpaceRunState.DONE)

    // Il catalogo: la foto liberata non ha piu' la copia del telefono (resta sul NAS, al sicuro); l'altra e' ancora sul telefono.
    val freed = checkNotNull(catalog.media(healthy))
    assertThat(freed.locations.filter { it.isPresent && it.isPhone }).isEmpty()
    assertThat(freed.locations.filter { it.isPresent && it.isNas }).isNotEmpty()
    val kept = checkNotNull(catalog.media(damagedMedia))
    assertThat(kept.locations.filter { it.isPresent && it.isPhone }).isNotEmpty()
    assertThat(rig.store.row(BackupRig.PRIMARY, kept.locations.first { it.isPhone }.let { (it.location as Location.Phone).mediaStoreId!! })).isNotNull()
    // Niente e' stato toccato sul NAS: la copia rovinata e' ancora li', quella buona anche (D2).
    assertThat(filesUnder(backend, TestNas.ADMIN_USER, "Backup").sorted()).isEqualTo(stored)
  }

  // --- Modifica sul telefono, versione, Versioni, "Ripristina originale" ----------------------------------------------------------

  /**
   * F34–F36 su JVM: una foto al sicuro; Samsung "Salva" la modifica sul telefono. La lettura del telefono vede una versione nuova; il giro del
   * backup (le versioni pianificate **prima** della 5A, nella stessa tappa) mette l'originale in `Versioni/` e la modifica al solito percorso
   * (niente "nome (2)"). Il foglio Versioni la mostra; "Ripristina originale" con la conferma e il permesso di scrittura scambia le due copie sul
   * NAS e riscrive la riga del telefono con l'originale riletto.
   */
  @Test
  fun modificaSulTelefonoDiventaVersioneEPoiSiRipristinaLOriginale() = runBlocking {
    val backend = TestNas.backend(time)
    val (rig, ids) = phoneWithSafePhotos(backend, count = 1)
    val id = ids.single()
    val backup = rig.backup
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val usual = filesUnder(backend, TestNas.ADMIN_USER, "Backup").single()
    val originalBytes = checkNotNull(backend.bytes(TestNas.ADMIN_USER, usual))

    // Samsung "Salva": stessa riga, byte nuovi, data di modifica dopo.
    val old = checkNotNull(rig.store.row(BackupRig.PRIMARY, 1L))
    val edited = BackupRig.jpeg(tone = 222)
    rig.store.put(
      MediaStoreRow(
        volume = old.volume, id = old.id, type = old.type, displayName = old.displayName, relativePath = old.relativePath, mime = old.mime,
        size = edited.size.toLong(), dateModifiedMs = old.dateModifiedMs + 60_000, dateTakenMs = old.dateTakenMs,
      ),
      edited,
    )
    rig.readPhone()
    val media = checkNotNull(catalog.media(id))
    assertWithMessage("versioni dopo la lettura").that(media.versions).hasSize(2)

    // Il giro: la versione nuova al solito percorso, l'originale in Versioni/ (mai un "nome (2)", mai una sostituzione).
    val last = checkNotNull(checkNotNull(rig.round()).last)
    assertWithMessage("giro: $last").that(last.failed).isEqualTo(0)
    assertThat(backend.bytes(TestNas.ADMIN_USER, usual)?.toList()).isEqualTo(edited.toList())
    val archived = filesUnder(backend, TestNas.ADMIN_USER, "Versioni")
    assertWithMessage("Versioni/: $archived").that(archived).hasSize(1)
    assertThat(backend.bytes(TestNas.ADMIN_USER, archived.single())?.toList()).isEqualTo(originalBytes.toList())
    assertThat(filesUnder(backend, TestNas.ADMIN_USER, "Backup")).containsExactly(usual)

    // Il foglio Versioni: due versioni, l'originale al sicuro, "Ripristina originale" disponibile.
    val keeper = backup.fiveB.versionKeeper
    val plan = keeper.plan(id)
    assertThat(plan.versions).hasSize(2)
    assertWithMessage("piano: $plan").that(plan.restore).isEqualTo(RestoreAvailability.AVAILABLE)

    // "Ripristina originale": la conferma del foglio; il telefono ha la modifica, quindi prima il permesso di scrittura.
    val confirmation = TestConfirmations.confirm(plan, backup.clock.monotonicMs())
    val asked = keeper.restoreOriginal(id, confirmation, null)
    assertThat(asked).isInstanceOf(RestoreOutcome.NeedsPhoneWrite::class.java)
    val rows = (asked as RestoreOutcome.NeedsPhoneWrite).rows
    // Il dialogo di sistema (la porta dei dialoghi dell'app lo chiede per quelle righe); l'utente dice si'.
    backup.fiveB.systemDialogs.writeRequest(rows)
    assertThat(phoneIntents.writeRequests.single().map { it.mediaStoreId }).containsExactlyElementsIn(rows.map { it.mediaStoreId })
    rig.store.resolveRequest(FakeMediaStore.SystemRequest.WRITE, rows.map { MediaRef.Phone(it.volume, it.mediaStoreId, null) }, FakeMediaStore.DialogAnswer.Accept)
    assertThat(keeper.restoreOriginal(id, confirmation, PhoneWriteGrant(rows.toSet()))).isEqualTo(RestoreOutcome.Queued)

    // Sul NAS le due copie si sono scambiate (due rinomine), sul telefono c'e' di nuovo l'originale; nel catalogo l'originale e' l'attuale.
    assertThat(backend.bytes(TestNas.ADMIN_USER, usual)?.toList()).isEqualTo(originalBytes.toList())
    val afterSwap = filesUnder(backend, TestNas.ADMIN_USER, "Versioni")
    assertThat(afterSwap).hasSize(1)
    assertThat(backend.bytes(TestNas.ADMIN_USER, afterSwap.single())?.toList()).isEqualTo(edited.toList())
    assertThat(rig.store.bytesOf(BackupRig.PRIMARY, 1L)?.toList()).isEqualTo(originalBytes.toList())
    val restored = checkNotNull(catalog.media(id))
    assertThat(restored.currentVersion.kind).isEqualTo(VersionKind.ORIGINAL)
    assertThat(keeper.plan(id).restore).isEqualTo(RestoreAvailability.ALREADY_ORIGINAL)
  }

  // --- Selettore e provider: i solo-NAS escono, i nascosti mai -------------------------------------------------------------------

  /** Le integrazioni come le monta l'app (blocco spento: il cancello d'ingresso e' aperto con la famiglia pronta). */
  private fun integrations(rig: BackupRig): IntegrationsGraph {
    val lock = AppLockController().apply { updateSettings(AppLockSettings()) }
    return IntegrationsGraph(
      context = rig.device.context,
      gallery = rig.gallery,
      scope = rig.device.scope,
      lock = lock,
      lockSettings = flowOf(AppLockSettings()),
      familyReady = { true },
      shareProbe = rig.device.graph.shareProbe,
    )
  }

  private suspend fun mediaAt(rig: BackupRig, path: String): MediaId {
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val info = checkNotNull(catalog.nasLocationAt(Location.Nas(AccountId.of(TestNas.ADMIN_USER), TestNas.ADMIN_USER, NasPath.of(path)))) { path }
    return checkNotNull(catalog.mediaIdOfLocation(info.locationId))
  }

  /**
   * D23, E-H1, F39 su JVM: due foto solo sul NAS (in `Archivio/`), una nascosta. Il provider dei documenti (Cartelle NAS) elenca la visibile e la
   * consegna con i suoi byte (letti a intervalli dal NAS), il selettore la consegna con un id; la nascosta non compare da nessuna parte e non si
   * consegna, anche se la cartella del catalogo la conosce.
   */
  @Test
  fun selettoreEProviderDannoISoloNasMaiINascosti() = runBlocking {
    val backend = TestNas.backend(time)
    val visibleBytes = BackupRig.jpeg(tone = 77)
    // File messi dal PC due giorni fa (un file appena scritto la scansione lo lascia assestare).
    backend.put(TestNas.ADMIN_USER, "Archivio/vecchia.jpg", visibleBytes, lastWriteMs = time.now() - 2 * DAY)
    backend.put(TestNas.ADMIN_USER, "Archivio/segreta.jpg", BackupRig.jpeg(tone = 177), lastWriteMs = time.now() - 2 * DAY)
    val (rig, _) = phoneWithSafePhotos(backend, count = 1)
    val visible = mediaAt(rig, "Archivio/vecchia.jpg")
    val hidden = mediaAt(rig, "Archivio/segreta.jpg")
    rig.gallery.actions.setHidden(setOf(hidden), true)
    withTimeout(30_000) { rig.gallery.repository.view(LibraryFilter.ALL).first { it.snapshot.indexOf(hidden) < 0 && it.snapshot.indexOf(visible) >= 0 } }

    val graph = integrations(rig)
    assertThat(graph.entryGate.decide()).isEqualTo(EntryDecision.ALLOWED)
    val docs = graph.documents
    val labels = DocumentLabels.from(rig.device.context)
    val root = docs.roots().single().rootDocument
    val nasFolders = docs.children(root, 10).single { it.isFolder && it.displayName == labels.nasFolders }
    val private = docs.children(nasFolders.id, 10).single { it.displayName == graph.labels.privateArea }
    val archive = docs.children(private.id, 50).single { it.isFolder && it.displayName == "Archivio" }
    val files = docs.children(archive.id, 50).filter { !it.isFolder }
    assertThat(files.map { it.displayName }).containsExactly("vecchia.jpg")
    // I Recenti non hanno la nascosta.
    val recent = docs.children(root, 10).single { it.isFolder && it.displayName == labels.recent }
    assertThat(docs.children(recent.id, 100).map { it.displayName }).doesNotContain("segreta.jpg")

    // Il file si apre e si legge a intervalli dal NAS, byte per byte.
    val reader = checkNotNull(docs.open(files.single().id))
    val read = reader.use { r ->
      val out = java.io.ByteArrayOutputStream()
      val buf = ByteArray(97)
      var at = 0L
      while (true) {
        val n = r.read(at, buf, 0, buf.size)
        if (n <= 0) break
        out.write(buf, 0, n)
        at += n
      }
      out.toByteArray()
    }
    assertThat(read.toList()).isEqualTo(visibleBytes.toList())

    // Il selettore: la visibile si consegna con un id opaco, la nascosta no.
    assertThat(docs.issue(visible)).isNotNull()
    assertThat(docs.issue(hidden)).isNull()
    // L'azzeramento revoca ogni id: dopo, niente si apre.
    graph.revokeAll()
    assertThat(docs.open(files.single().id)).isNull()
  }

  // --- "Modifica con…" di un solo-NAS ------------------------------------------------------------------------------------------

  /**
   * D25, E-E3, F41 su JVM: una foto solo sul NAS. "Modifica con…" ne fa la copia in cache (dal NAS, con l'impronta), l'editor la riscrive, al
   * ritorno la sessione la riconosce come **versione dello stesso media** (nessun media nuovo), il file preparato diventa un lavoro `VERSION` e
   * il giro del backup mette la modifica al solito percorso e l'originale in `Versioni/`.
   */
  @Test
  fun modificaConDiUnSoloNasDiventaUnaVersioneSulNas() = runBlocking {
    val backend = TestNas.backend(time)
    val originalBytes = BackupRig.jpeg(tone = 60)
    backend.put(TestNas.ADMIN_USER, "Archivio/gita.jpg", originalBytes, lastWriteMs = time.now() - 2 * DAY)
    val (rig, _) = phoneWithSafePhotos(backend, count = 1)
    val id = mediaAt(rig, "Archivio/gita.jpg")
    val graph = integrations(rig)
    val sessions = checkNotNull(graph.editSessions()) { "le sessioni di modifica non ci sono" }

    val begin = sessions.begin(id, EditTarget.NAS_CACHE)
    assertWithMessage("inizio: $begin").that(begin).isInstanceOf(EditBegin.Started::class.java)
    val session = (begin as EditBegin.Started).session
    val cacheFile = checkNotNull(graph.shareCache.editFile(checkNotNull(session.cacheName)))
    assertThat(cacheFile.readBytes().toList()).isEqualTo(originalBytes.toList())

    // L'editor (Google Foto, OneDrive) riscrive la copia nell'indirizzo che ha ricevuto.
    val editedBytes = BackupRig.jpeg(tone = 160)
    cacheFile.writeBytes(editedBytes)
    val outcome = sessions.settle(session)
    assertWithMessage("esito: $outcome").that(outcome).isInstanceOf(EditOutcome.NewVersion::class.java)
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    assertThat(checkNotNull(catalog.media(id)).versions).hasSize(2)

    // Il giro del backup porta la versione al solito percorso; l'originale va in Versioni/.
    val last = checkNotNull(checkNotNull(rig.round()).last)
    assertWithMessage("giro: $last").that(last.failed).isEqualTo(0)
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Archivio/gita.jpg")?.toList()).isEqualTo(editedBytes.toList())
    val archived = filesUnder(backend, TestNas.ADMIN_USER, "Versioni")
    assertWithMessage("Versioni/: $archived").that(archived).hasSize(1)
    assertThat(backend.bytes(TestNas.ADMIN_USER, archived.single())?.toList()).isEqualTo(originalBytes.toList())
    // La copia in cache non c'e' piu' (sessione chiusa, permesso revocato).
    assertThat(cacheFile.exists()).isFalse()
  }

  // --- "Condividi senza posizione" ----------------------------------------------------------------------------------------------

  /**
   * D26, L41, F42 su JVM: una foto solo sul NAS con la posizione GPS. "Condividi" con "Rimuovi la posizione" accesa: la copia scaricata in cache,
   * ripulita e ricontrollata dai lettori dell'app; il file consegnato non ha piu' la posizione, l'originale sul NAS non e' toccato; finito l'uso
   * la copia si toglie.
   */
  @Test
  fun condividiSenzaPosizioneConsegnaUnaCopiaSenzaGps() = runBlocking {
    val backend = TestNas.backend(time)
    val withGps = jpegWithGps(BackupRig.jpeg(tone = 120))
    backend.put(TestNas.ADMIN_USER, "Archivio/mare.jpg", withGps, lastWriteMs = time.now() - 2 * DAY)
    val (rig, _) = phoneWithSafePhotos(backend, count = 1)
    val id = mediaAt(rig, "Archivio/mare.jpg")
    val graph = integrations(rig)
    // Lo stesso montaggio dell'app; solo l'indirizzo del FileProvider e' finto (FileProvider non gira su JVM/Windows, nota di P11).
    val share = LocationFreeShare(
      cache = graph.shareCache,
      source = AppShareSource({ rig.gallery.coreIfReady()?.catalog }, rig.gallery.intents, rig.device.context.contentResolver, { rig.device.graph.nasInputs.session.mediaReader() }),
      stripper = DefaultLocationStripper(LocationRechecks.standard(rig.gallery.newExtractor(), AndroidPreviewDecoder(), ZoneId.of("UTC"))),
      uris = CacheUris { file -> Uri.fromFile(file) },
      revoker = UriRevoker { },
    )

    val prepared = share.prepare(listOf(id), removeLocation = true)
    assertWithMessage("condivisione: $prepared").that(prepared).isInstanceOf(SharePreparation.Ready::class.java)
    val ready = prepared as SharePreparation.Ready
    assertThat(ready.stripped).isEqualTo(1)
    val sent = checkNotNull(ready.files.single().file)
    assertThat(ExifInterface(sent.path).getLatLong(FloatArray(2))).isFalse()
    // L'originale sul NAS ha ancora la sua posizione: niente e' stato scritto la' (D2).
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Archivio/mare.jpg")?.toList()).isEqualTo(withGps.toList())
    ready.lease.release()
    assertThat(sent.exists()).isFalse()
  }

  /** [jpeg] con la posizione GPS nell'EXIF (45,5 N, 9,2 E). */
  private fun jpegWithGps(jpeg: ByteArray): ByteArray {
    val file = File(temp.root, "gps-${System.nanoTime()}.jpg").apply { writeBytes(jpeg) }
    ExifInterface(file.path).apply {
      setAttribute(ExifInterface.TAG_GPS_LATITUDE, "45/1,30/1,0/1")
      setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
      setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "9/1,12/1,0/1")
      setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
      saveAttributes()
    }
    check(ExifInterface(file.path).getLatLong(FloatArray(2))) { "la foto di prova non ha la posizione" }
    return file.readBytes()
  }

  // --- Ricevere: "Salva sul NAS" -------------------------------------------------------------------------------------------------

  /**
   * D24, F40 su JVM: un'altra app condivide una foto; la copia in `incoming/`, il tocco su "Salva sul NAS" (conferma per questo piano), la
   * coda nel diario del backup, il lavoro degli arrivi: il file e' sul NAS nella cartella scelta, **riletto** (copia verificata nel catalogo),
   * e solo dopo la copia locale e' tolta. Niente parte prima del tocco (E-S11).
   */
  @Test
  fun riceviSalvaSulNasCaricaVerificaEToglieLaCopiaLocale() = runBlocking {
    val backend = TestNas.backend(time)
    val (rig, _) = phoneWithSafePhotos(backend, count = 1)
    val backup = rig.backup
    val fiveB = backup.fiveB
    val bytes = BackupRig.jpeg(tone = 200)
    // Lo stesso stager dell'app (lo spazio si misura sulla prima cartella che esiste: `incoming/` nasce alla prima copia). Il lettore delle
    // dimensioni di `BitmapFactory` su Robolectric e Windows tiene aperto il file e la rinomina fallirebbe: qui legge le dimensioni il test.
    val stager = IncomingStager(fiveB.incomingRoot, freeBytes = { IncomingSpace.usableBytes(fiveB.incomingRoot) }, bounds = { ImageSize(320, 240) })
    val uploads = DefaultIncomingUploads(stager, fiveB.incomingQueue, backup.clock)

    // L'altra app condivide: la copia c'e', il diario non sa ancora niente (nessun tocco).
    val stage = uploads.stage(listOf(IncomingStream(IncomingOrigin.CONTENT_OF_ANOTHER_APP, "Vacanze.jpg", "image/jpeg", bytes.size.toLong()) { ByteArrayInputStream(bytes) }))
    assertWithMessage("rifiutati: ${stage.rejected}").that(stage.accepted).hasSize(1)
    assertThat(backup.hasOpenIncoming()).isFalse()

    // "Salva sul NAS" nella cartella delle ricevute dell'area privata, con la conferma del foglio.
    val dest = NasDestination(TestNas.ADMIN_USER, SharePath.of("Ricevute"), DestinationArea.PRIVATE)
    val saved = uploads.save(stage, dest, TestConfirmations.confirm(IncomingSavePlan(stage, dest), backup.clock.monotonicMs()))
    assertThat(saved).isEqualTo(IncomingSaveOutcome.Queued(1, userInitiated = false))
    assertThat(backup.hasOpenIncoming()).isTrue()

    // Il lavoro degli arrivi (come `IncomingUploadWorker`).
    val outcome = checkNotNull(BackupStages(rig.device.graph, backup).incoming())
    val last = checkNotNull(outcome.last) { "tappa: ${outcome.stage}" }
    assertWithMessage("giro: $last").that(last.done + last.linked).isEqualTo(1)

    // Sul NAS, byte per byte, con il nome dell'altra app; mai sostituito niente.
    assertThat(backend.bytes(TestNas.ADMIN_USER, "Ricevute/Vacanze.jpg")?.toList()).isEqualTo(bytes.toList())
    // Il catalogo ha la copia **verificata** (riletta), e il media si vede nella Libreria.
    val catalog = checkNotNull(rig.gallery.coreIfReady()).catalog
    val media = checkNotNull(catalog.mediaIdOfLocation(checkNotNull(catalog.nasLocationAt(Location.Nas(AccountId.of(TestNas.ADMIN_USER), TestNas.ADMIN_USER, NasPath.of("Ricevute/Vacanze.jpg")))).locationId))
    val copy = checkNotNull(catalog.media(media)).locations.single { it.isNas }
    assertThat(copy.verifiedAtMillis).isNotNull()
    // La copia locale e' tolta solo adesso; la riga dice "caricato"; il diario non ha piu' arrivi aperti.
    assertThat(fiveB.incomingRoot.walkTopDown().filter { it.isFile }.toList()).isEmpty()
    assertThat(rig.gallery.database.incomingDao().stagesInStates(IncomingStates.KEEP)).isEmpty()
    assertThat(backup.hasOpenIncoming()).isFalse()
  }

  private companion object {
    const val DAY = 24L * 3_600_000L
    val DEBUG_LIKE: FeatureFlags = FeatureFlags.forBuild(debugBuild = true, nasLibrary = true)
  }
}
