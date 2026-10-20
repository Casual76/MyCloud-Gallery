package dev.pampa.mycloudgallery.feature.integrations.edit

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.EditBegin
import dev.pampa.mycloudgallery.core.backup.contract.EditOutcome
import dev.pampa.mycloudgallery.core.backup.contract.EditSession
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionRules
import dev.pampa.mycloudgallery.core.backup.contract.EditSessionState
import dev.pampa.mycloudgallery.core.backup.contract.EditTarget
import dev.pampa.mycloudgallery.core.backup.contract.EditUnavailable
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.versions.DirectoryStagedFiles
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaStore
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.feature.integrations.edit.testing.FakeEditCatalog
import dev.pampa.mycloudgallery.feature.integrations.edit.testing.FakeGalleryDownloads
import dev.pampa.mycloudgallery.feature.integrations.edit.testing.FakeNasCopies
import dev.pampa.mycloudgallery.feature.integrations.edit.testing.InMemoryEditSessionStore
import dev.pampa.mycloudgallery.feature.integrations.edit.testing.RecordingStagedVersionPlanner
import dev.pampa.mycloudgallery.feature.integrations.share.EditCounter
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ShareCache
import dev.pampa.mycloudgallery.feature.integrations.share.testing.FakeCacheUris
import dev.pampa.mycloudgallery.feature.integrations.share.testing.RecordingUriRevoker
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fase 5B, P11 (D18, D25, D31; E-E2, E-E3, E-E5, E-E7, E-X1, E-X3; §6.1): le sessioni di "Modifica con…" sul **telefono finto** (`FakeMediaStore`) e
 * con un catalogo, un NAS e un database finti. Cosa e' di una sessione e cosa no: la riga cambiata e' una versione; una copia nella stessa cartella con
 * la stessa data di scatto, nata durante la sessione, e' una versione dello stesso media; ogni altra cosa non lo e'. Nel dubbio non si unisce. Il risultato
 * di un editor e' input non fidato: un file che non e' un media dello stesso genere si rifiuta e l'originale resta com'era.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultEditSessionsTest {

  @get:Rule val folder = TemporaryFolder()

  private val phone = FakeMediaStore()
  private val catalog = FakeEditCatalog()
  private val store = InMemoryEditSessionStore()
  private val nas = FakeNasCopies()
  private val downloads = FakeGalleryDownloads()
  private val planner = RecordingStagedVersionPlanner()
  private val clock = FakeBackupClock()
  private val uris = FakeCacheUris()
  private val revoker = RecordingUriRevoker()
  private lateinit var staged: DirectoryStagedFiles
  private lateinit var cache: ShareCache
  private lateinit var sessions: DefaultEditSessions

  private val mediaId = MediaId.random()
  private val v1 = VersionId.random()
  private val taken = 1_710_238_530_000L
  private val folderPath = "DCIM/Camera/"

  @Before
  fun setUp() {
    FlowCounters.reset()
    phone.addVolume("external_primary", "v1")
    staged = DirectoryStagedFiles(File(folder.root, "versions"))
    cache = ShareCache(File(folder.root, "cache"))
    sessions = service()
  }

  private fun service(fence: (String) -> Boolean = { true }) = DefaultEditSessions(
    phone = phone, rows = phone, catalog = catalog, store = store, nasCopies = nas, downloads = downloads, staged = staged, planner = planner,
    cache = cache, clock = clock, uris = uris, revoker = revoker, phoneFolderAllowed = fence, io = Dispatchers.Unconfined,
  )

  private fun row(id: Long, taken: Long? = this.taken, path: String = folderPath, type: RowType = RowType.IMAGE, size: Long = 5_000, modified: Long = 1_000_000L, name: String = "IMG_$id.jpg") =
    MediaStoreRow("external_primary", id, type, name, path, if (type == RowType.VIDEO) "video/mp4" else "image/jpeg", size, modified, taken)

  private fun ref(id: Long) = PhoneRef("external_primary", id, "v1")

  private fun photoOnPhone(rowId: Long = 10L, mine: Boolean = true, isVideo: Boolean = false): PhoneRef {
    phone.put(row(rowId, type = if (isVideo) RowType.VIDEO else RowType.IMAGE), ReceiveBytes.jpeg())
    val ref = ref(rowId)
    catalog.media[mediaId] = EditMedia(mediaId, mine, isVideo, v1, listOf(ref), hasNasCopy = true, takenAtUtcMs = taken)
    catalog.current[mediaId] = EditCurrent(v1, VersionKind.ORIGINAL)
    catalog.mediaOfRow[ref] = mediaId
    return ref
  }

  private fun begin(target: EditTarget = EditTarget.PHONE_GALLERY): EditSession =
    (runBlocking { sessions.begin(mediaId, target) } as EditBegin.Started).session

  private fun settle(session: EditSession) = runBlocking { sessions.settle(session) }

  private fun stateOf(session: EditSession) = runBlocking { store.find(session.id)!!.session.state }

  /** Una riga nuova nel telefono (come la scrive un editor): nasce dopo l'inizio della sessione, quindi con una generazione maggiore. */
  private fun editorSavesCopy(id: Long, taken: Long? = this.taken, path: String = folderPath, type: RowType = RowType.IMAGE, modified: Long = 2_000_000L): PhoneRef {
    phone.put(row(id, taken, path, type, modified = modified), ReceiveBytes.jpeg(seed = id.toInt()))
    return ref(id)
  }

  // --- begin -----------------------------------------------------------------------------------------------------------------

  @Test
  fun unaSessioneNascePerUnMediaDelTelefonoEDuraTrentaMinutiDiOraDelNas() {
    val ref = photoOnPhone()

    val session = begin()

    assertThat(session.state).isEqualTo(EditSessionState.OPEN)
    assertThat(session.phone).isEqualTo(ref)
    assertThat(session.target).isEqualTo(EditTarget.PHONE_GALLERY)
    assertThat(session.versionId).isEqualTo(v1)
    assertThat(session.expiresAtNasMillis - session.startedAtNasMillis).isEqualTo(EditSessionRules.WINDOW_MS)
    assertThat(session.startedAtNasMillis).isEqualTo(clock.nasNowMs())
    val snapshot = runBlocking { store.find(session.id)!!.snapshot }
    assertThat(snapshot.relativePath).isEqualTo(folderPath)
    assertThat(snapshot.takenAtUtcMs).isEqualTo(taken)
    assertThat(snapshot.startGeneration).isNotNull()
    assertThat(FlowCounters.edit.get(EditCounter.BEGUN)).isEqualTo(1L)
  }

  @Test
  fun quandoUnaSessioneNonSiPuoAprireDiceIlMotivo_E_X1() {
    photoOnPhone()

    clock.nasNow = null // nessuna sessione NAS ha ancorato l'ora: una copia dal NAS non si apre (MC-6: una foto del telefono si', sotto)
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.NAS_CACHE) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.NAS_UNREACHABLE))
    clock.nasNow = FakeBackupClock.DEFAULT_NAS_NOW

    // Un media che non e' di questo account, o che il catalogo non conosce.
    catalog.media[mediaId] = EditMedia(mediaId, false, false, v1, listOf(ref(10)), true)
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.PHONE_GALLERY) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.NOT_MINE))
    assertThat(runBlocking { sessions.begin(MediaId.random(), EditTarget.PHONE_GALLERY) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.NOT_MINE))

    // La riga e' sparita dal telefono, o il MediaStore ha cambiato versione (gli `_ID` non valgono piu').
    catalog.media[mediaId] = EditMedia(mediaId, true, false, v1, listOf(ref(10)), true, taken)
    phone.remove("external_primary", 10)
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.PHONE_GALLERY) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.GONE_FROM_PHONE))
    phone.put(row(10), ReceiveBytes.jpeg())
    phone.bumpVersion("external_primary", "v2")
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.PHONE_GALLERY) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.GONE_FROM_PHONE))
    assertThat(FlowCounters.edit.get(EditCounter.BEGIN_UNAVAILABLE)).isEqualTo(5L)
    assertThat(store.sessions).isEmpty()
  }

  /**
   * MC-6 della verifica funzionale 5B: fuori casa a processo freddo l'ora del NAS non c'e'. Prima "Modifica con…" di una foto del **telefono**
   * rispondeva "Il NAS non risponde"; adesso la sessione nasce e dura 30 minuti di orologio monotono di questo processo. Un riavvio la chiude
   * (il monotono riparte da zero): non si attribuisce a una sessione un cambiamento che non si puo' datare.
   */
  @Test
  fun mc6_unaFotoDelTelefonoSiModificaAncheSenzaLOraDelNas() {
    photoOnPhone()
    clock.nasNow = null
    val session = begin()
    assertThat(session.state).isEqualTo(EditSessionState.OPEN)
    // Dentro la mezz'ora monotona la sessione resta aperta (niente cambiato: resta in attesa).
    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN)
    // L'ora del NAS arriva dopo (a casa): non fa scadere la sessione monotona.
    clock.nasNow = FakeBackupClock.DEFAULT_NAS_NOW
    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged)
    // Oltre la mezz'ora di monotono: scaduta.
    clock.monotonic += EditSessionRules.WINDOW_MS + 1
    assertThat(settle(session)).isEqualTo(EditOutcome.Expired)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.EXPIRED)

    // Un'altra sessione monotona e un riavvio: il processo nuovo non la conosce e la chiude.
    clock.nasNow = null
    val again = begin()
    sessions = service()
    runBlocking { sessions.expireStale() }
    assertThat(stateOf(again)).isEqualTo(EditSessionState.EXPIRED)
  }

  @Test
  fun inDebugConGliAccountDiProvaSiModificanoSoloLeCartelleRecintate_D13() {
    photoOnPhone()
    sessions = service(fence = { it.startsWith("DCIM/mcg-prova/") })

    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.PHONE_GALLERY) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.GONE_FROM_PHONE))

    phone.put(row(10, path = "DCIM/mcg-prova/"), ReceiveBytes.jpeg())
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.PHONE_GALLERY) }).isInstanceOf(EditBegin.Started::class.java)
  }

  @Test
  fun unaNuovaSessioneSostituisceLaVecchiaDelloStessoMedia() {
    photoOnPhone()
    val first = begin()
    val second = begin()

    assertThat(stateOf(first)).isEqualTo(EditSessionState.CANCELLED)
    assertThat(stateOf(second)).isEqualTo(EditSessionState.OPEN)
    assertThat(runBlocking { sessions.settle(first) }).isEqualTo(EditOutcome.Expired) // una sessione chiusa non si legge piu'
  }

  @Test
  fun unTentativoCheNonRiesceNonChiudeLaSessioneCheFunziona() {
    photoOnPhone()
    val first = begin()

    // La riga sparisce: una nuova sessione non puo' aprirsi. Quella che c'era resta aperta.
    phone.remove("external_primary", 10)
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.PHONE_GALLERY) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.GONE_FROM_PHONE))

    assertThat(stateOf(first)).isEqualTo(EditSessionState.OPEN)
  }

  @Test
  fun unVolumeCheSparisceNonFaCadereIlProgramma_E_P6() {
    photoOnPhone()
    val session = begin()

    phone.setMounted("external_primary", false)

    assertThat(settle(session)).isEqualTo(EditOutcome.Unreadable)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN)
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.PHONE_GALLERY) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.GONE_FROM_PHONE))
  }

  // --- Salva sopra (riga cambiata), Ripristina originale -------------------------------------------------------------------------------

  @Test
  fun salvaSopra_LaRigaCambiataEUnaVersione_D18() {
    photoOnPhone()
    val session = begin()
    val v2 = VersionId.random()
    catalog.onRefresh = {
      phone.rewrite("external_primary", 10, ReceiveBytes.jpeg(seed = 99), dateModifiedMs = 2_000_000L)
      catalog.current[mediaId] = EditCurrent(v2, VersionKind.EDITED) // la lettura del telefono ha riconosciuto la modifica
    }

    val outcome = settle(session)

    assertThat(outcome).isEqualTo(EditOutcome.NewVersion(v2))
    assertThat(stateOf(session)).isEqualTo(EditSessionState.SETTLED)
    assertThat(catalog.refreshes).isEqualTo(1)
    assertThat(catalog.links).isEmpty() // nessun ricollegamento: e' la stessa riga
    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged) // l'esito e' gia' stato dato
    assertThat(FlowCounters.edit.get(EditCounter.SETTLED_NEW_VERSION)).isEqualTo(1L)
  }

  @Test
  fun ripristinaOriginaleInSamsungGallery_NessunCaricamento_E_E2() {
    photoOnPhone()
    val session = begin()
    val original = VersionId.random()
    catalog.current[mediaId] = EditCurrent(original, VersionKind.ORIGINAL)

    assertThat(settle(session)).isEqualTo(EditOutcome.RestoredOriginal)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.SETTLED)
  }

  @Test
  fun unaCorrezioneDellAppNelFrattempoNonEUnEsitoDellEditor() {
    photoOnPhone()
    val session = begin()
    catalog.current[mediaId] = EditCurrent(VersionId.random(), VersionKind.CORRECTED)

    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN)
  }

  @Test
  fun senzaCambiamentiLaSessioneResteApertaEPoiSiPuoSaldare() {
    photoOnPhone()
    val session = begin()

    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN) // l'editor puo' essere ancora aperto
    assertThat(catalog.links).isEmpty()

    // Dopo, l'utente salva: la sessione lo vede.
    val v2 = VersionId.random()
    catalog.current[mediaId] = EditCurrent(v2, VersionKind.EDITED)
    assertThat(settle(session)).isEqualTo(EditOutcome.NewVersion(v2))
  }

  // --- Salva copia (E-X3) ------------------------------------------------------------------------------------------------------------------

  @Test
  fun salvaCopia_UnaRigaNuovaNellaStessaCartellaConLaStessaDataEUnaVersioneDelloStessoMedia_E_X3() {
    photoOnPhone()
    val session = begin()
    val copyRef = editorSavesCopy(11)
    val copyMedia = MediaId.random()
    catalog.mediaOfRow[copyRef] = copyMedia
    val v2 = VersionId.random()
    catalog.linkResult = v2

    val outcome = settle(session)

    assertThat(outcome).isEqualTo(EditOutcome.NewVersionFromCopy(v2))
    assertThat(catalog.links).containsExactly(Triple(mediaId, copyMedia, v1)) // la copia diventa figlia della versione di partenza
    assertThat(stateOf(session)).isEqualTo(EditSessionState.SETTLED)
    assertThat(FlowCounters.edit.get(EditCounter.SETTLED_FROM_COPY)).isEqualTo(1L)
  }

  @Test
  fun unaRigaNuovaCheNonSiRiconoscePerDataCartellaGenereOMomentoNonELaCopia() {
    photoOnPhone()
    val session = begin()
    // Un'altra foto scattata nel frattempo (altra data), la stessa data ma in un'altra cartella, un video con la stessa data (altro genere).
    editorSavesCopy(20, taken = taken + 60_000)
    editorSavesCopy(21, path = "Pictures/WhatsApp/")
    editorSavesCopy(22, type = RowType.VIDEO)
    editorSavesCopy(23, taken = null)
    for (id in 20L..23L) catalog.mediaOfRow[ref(id)] = MediaId.random()

    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged)

    assertThat(catalog.links).isEmpty()
    assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN)
  }

  @Test
  fun unaRigaEsistenteDaPrimaConLaStessaDataNonEUnaCopiaDellaSessione() {
    photoOnPhone()
    // Un gemello nato prima della sessione (stessa data, stessa cartella): una raffica, una copia fatta ieri.
    phone.put(row(30), ReceiveBytes.jpeg(seed = 30))
    val session = begin()
    catalog.mediaOfRow[ref(30)] = MediaId.random()

    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged)
    assertThat(catalog.links).isEmpty()
  }

  @Test
  fun nelDubbioNonSiUnisce_PiuCandidateOIlCatalogoChePrefersice() {
    photoOnPhone()
    val session = begin()
    val a = editorSavesCopy(11)
    val b = editorSavesCopy(12)
    catalog.mediaOfRow[a] = MediaId.random()
    catalog.mediaOfRow[b] = MediaId.random()
    catalog.linkResult = VersionId.random()

    // Due candidate: nessuna attribuita, nemmeno chiesto al catalogo.
    assertThat(settle(session)).isEqualTo(EditOutcome.NewMedia)
    assertThat(catalog.links).isEmpty()

    // Una sola candidata ma il catalogo non la ricollega (hash che dimostrano altro, media nascosto): resta un media nuovo.
    phone.remove("external_primary", 12)
    catalog.linkResult = null
    val session2 = begin()
    val c = editorSavesCopy(13)
    catalog.mediaOfRow[c] = MediaId.random()
    assertThat(settle(session2)).isEqualTo(EditOutcome.NewMedia)
    assertThat(catalog.links).hasSize(1)
  }

  @Test
  fun seLaScansioneNonHaAncoraVistoLaCopiaSiRiprova() {
    photoOnPhone()
    val session = begin()
    editorSavesCopy(11) // il catalogo non la conosce ancora

    assertThat(settle(session)).isEqualTo(EditOutcome.Unreadable)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN) // la sessione resta aperta: si riprova

    catalog.mediaOfRow[ref(11)] = MediaId.random()
    catalog.linkResult = VersionId.random()
    assertThat(settle(session)).isInstanceOf(EditOutcome.NewVersionFromCopy::class.java)
  }

  @Test
  fun senzaGenerazioniAndroid10SiConfrontaLaDataDiModificaDelMediaStore() {
    phone.supportsGenerations = false
    photoOnPhone()
    val session = begin()
    assertThat(runBlocking { store.find(session.id)!!.snapshot.startGeneration }).isNull()
    // Nata prima (data di modifica minore di quella di partenza): non conta. Nata dopo: conta.
    phone.put(row(31, modified = 500_000L), ReceiveBytes.jpeg(seed = 31))
    val copy = editorSavesCopy(32, modified = 2_000_000L)
    catalog.mediaOfRow[ref(31)] = MediaId.random()
    catalog.mediaOfRow[copy] = MediaId.random()
    catalog.linkResult = VersionId.random()

    assertThat(settle(session)).isInstanceOf(EditOutcome.NewVersionFromCopy::class.java)
    assertThat(catalog.links.single().second).isEqualTo(catalog.mediaOfRow[copy])
  }

  @Test
  fun seLOriginaleESparitoSenzaCopiaNonSiLeggeEsito() {
    photoOnPhone()
    val session = begin()
    phone.remove("external_primary", 10)

    assertThat(settle(session)).isEqualTo(EditOutcome.Unreadable)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN)
  }

  // --- Il tempo (D31, E-E5) ---------------------------------------------------------------------------------------------------------------

  @Test
  fun oltreTrentaMinutiUnCambiamentoNonEPiuAttribuito_E_E5() {
    photoOnPhone()
    val session = begin()
    val copy = editorSavesCopy(11)
    catalog.mediaOfRow[copy] = MediaId.random()
    catalog.linkResult = VersionId.random()

    clock.advance(EditSessionRules.WINDOW_MS) // esattamente alla fine: vale ancora
    assertThat(clock.nasNowMs()).isEqualTo(session.expiresAtNasMillis)
    clock.advance(1) // un millisecondo oltre
    val outcome = settle(session)

    assertThat(outcome).isEqualTo(EditOutcome.Expired)
    assertThat(stateOf(session)).isEqualTo(EditSessionState.EXPIRED)
    assertThat(catalog.refreshes).isEqualTo(0) // oltre la finestra non si guarda nemmeno
    assertThat(catalog.links).isEmpty()
  }

  @Test
  fun allaFineEsattaDellaFinestraLaSessioneVale() {
    photoOnPhone()
    val session = begin()
    val copy = editorSavesCopy(11)
    catalog.mediaOfRow[copy] = MediaId.random()
    catalog.linkResult = VersionId.random()
    clock.advance(EditSessionRules.WINDOW_MS)

    assertThat(settle(session)).isInstanceOf(EditOutcome.NewVersionFromCopy::class.java)
  }

  @Test
  fun laSessioneSopravvivealLaMorteDelProcesso() {
    photoOnPhone()
    val session = begin()
    val copy = editorSavesCopy(11)
    catalog.mediaOfRow[copy] = MediaId.random()
    catalog.linkResult = VersionId.random()

    // Il processo muore e rinasce: stesso database (lo store), stesso telefono. Un servizio nuovo riprende la sessione.
    sessions = service()

    assertThat(settle(session)).isInstanceOf(EditOutcome.NewVersionFromCopy::class.java)
  }

  // --- Media solo-NAS: copia in cache (E-E3) -------------------------------------------------------------------------------------------

  private val original = ReceiveBytes.jpeg(seed = 1)

  private fun nasOnly(id: MediaId = mediaId): EditSession {
    catalog.media[id] = EditMedia(id, true, false, v1, emptyList(), hasNasCopy = true, takenAtUtcMs = taken)
    catalog.current[id] = EditCurrent(v1, VersionKind.ORIGINAL)
    nas.bytes[id] = original
    return (runBlocking { sessions.begin(id, EditTarget.NAS_CACHE) } as EditBegin.Started).session
  }

  private fun cacheFile(session: EditSession): File = cache.editFile(session.cacheName!!)!!

  private fun sha(bytes: ByteArray) = Sha256(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })

  @Test
  fun unSoloNasSiScaricaInCacheConIlSuoHash() {
    val session = nasOnly()

    assertThat(session.target).isEqualTo(EditTarget.NAS_CACHE)
    assertThat(session.phone).isNull()
    assertThat(cacheFile(session).readBytes()).isEqualTo(original)
    assertThat(runBlocking { store.find(session.id)!!.snapshot.cacheSha256 }).isEqualTo(sha(original))
    // Il nome della copia e' opaco: il FileProvider serve solo `edit/<token>`.
    assertThat(session.cacheName).matches("[0-9a-f]{32}")
  }

  @Test
  fun NasCheNonRispondeOTelefonoPienoNonLasciaNiente_E_X1() {
    catalog.media[mediaId] = EditMedia(mediaId, true, false, v1, emptyList(), hasNasCopy = true, takenAtUtcMs = taken)
    nas.bytes[mediaId] = original

    nas.unreachable = true
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.NAS_CACHE) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.NAS_UNREACHABLE))
    nas.unreachable = false
    nas.full = true
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.NAS_CACHE) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.PHONE_FULL))
    nas.full = false
    nas.bytes.clear()
    assertThat(runBlocking { sessions.begin(mediaId, EditTarget.NAS_CACHE) }).isEqualTo(EditBegin.Unavailable(EditUnavailable.NOT_AVAILABLE))

    assertThat(cache.editRoot.listFiles().orEmpty().toList()).isEmpty()
    assertThat(store.sessions).isEmpty()
  }

  @Test
  fun ilRisultatoDiUnEditorDiventaUnaVersioneNelloStessoOrdineDiP8_E_E3() {
    val session = nasOnly()
    val edited = ReceiveBytes.jpeg(seed = 77, size = 6_000)
    cacheFile(session).writeBytes(edited) // l'editor ha scritto nella copia
    val v2 = VersionId.random()
    catalog.addEditedResult = v2

    val outcome = settle(session)

    assertThat(outcome).isEqualTo(EditOutcome.NewVersion(v2))
    // 1. il file e' tenuto come file preparato col suo hash; 2. la versione e' nel catalogo; 3. il lavoro VERSION e' pianificato.
    val sha = sha(edited)
    assertThat(staged.file(sha)!!.readBytes()).isEqualTo(edited)
    assertThat(catalog.edited).containsExactly(Triple(mediaId, v1, sha))
    assertThat(planner.planned).containsExactly(Triple(mediaId, v2, sha))
    // La sessione e' chiusa, la copia in cache e' tolta, il permesso sull'indirizzo e' revocato.
    assertThat(stateOf(session)).isEqualTo(EditSessionState.SETTLED)
    assertThat(cacheFile(session).exists()).isFalse()
    assertThat(revoker.revoked).hasSize(1)
  }

  @Test
  fun unFileOstileODannatoNonDiventaMaiUnaVersione_L_OriginaleResta() {
    val session = nasOnly()
    val hostile = mapOf(
      "vuoto" to ByteArray(0),
      "html" to "<!DOCTYPE html><html>errore</html>".toByteArray(),
      "pdf" to "%PDF-1.7\n".toByteArray() + ByteArray(200) { 0xFF.toByte() },
      "zip" to byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(200) { 0x90.toByte() },
      "azzerato" to ByteArray(3_000),
      // Una foto che l'editor ha trasformato in video: altro genere, non e' la stessa cosa.
      "video" to ReceiveBytes.mp4(),
    )
    for ((name, bytes) in hostile) {
      cacheFile(session).writeBytes(bytes)

      val outcome = settle(session)

      assertThat(outcome).isEqualTo(EditOutcome.Unreadable)
      assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN)
      assertThat(staged.pending()).isEmpty()
      assertThat(planner.planned).isEmpty()
      assertThat(name.isNotEmpty()).isTrue()
    }
    assertThat(catalog.edited).isEmpty() // il catalogo non e' stato toccato: l'originale resta com'era
    assertThat(FlowCounters.edit.get(EditCounter.RESULT_REFUSED)).isEqualTo(hostile.size.toLong())
  }

  @Test
  fun seIlCatalogoNonHaPiuIlMediaIlFilePreparatoSiTogliEL_EsitoNonSiLegge() {
    val session = nasOnly()
    cacheFile(session).writeBytes(ReceiveBytes.jpeg(seed = 5, size = 4_500))
    catalog.addEditedResult = null

    assertThat(settle(session)).isEqualTo(EditOutcome.Unreadable)
    assertThat(staged.pending()).isEmpty()
    assertThat(stateOf(session)).isEqualTo(EditSessionState.OPEN)
  }

  @Test
  fun iByteNonCambiatiSonoUnchanged_EUnaCopiaSalvataAltroveSiRicollega() {
    val session = nasOnly()
    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged)

    // Google Foto non scrive nell'indirizzo che gli abbiamo dato: salva un file nuovo, con la stessa data di scatto (a meno di un secondo).
    val copy = editorSavesCopy(50, taken = taken + 400, path = "Pictures/Google Foto/")
    catalog.mediaOfRow[copy] = MediaId.random()
    val v2 = VersionId.random()
    catalog.linkResult = v2

    assertThat(settle(session)).isEqualTo(EditOutcome.NewVersionFromCopy(v2))
    assertThat(catalog.links).containsExactly(Triple(mediaId, catalog.mediaOfRow[copy]!!, v1))
    assertThat(cacheFile(session).exists()).isFalse() // sessione chiusa: la copia in cache non serve piu'
  }

  @Test
  fun unaFotoDiUnAltroGiornoSalvataNelFrattempoNonEUnaCopiaDelSoloNas() {
    val session = nasOnly()
    val other = editorSavesCopy(51, taken = taken + 3_600_000)
    catalog.mediaOfRow[other] = MediaId.random()

    assertThat(settle(session)).isEqualTo(EditOutcome.Unchanged)
    assertThat(catalog.links).isEmpty()
  }

  @Test
  fun pulireAllAvvioChiudeLeScaduteEDiceQualiCopieTenere() {
    val older = nasOnly(MediaId.random())
    clock.advance(10 * 60_000L)
    val newer = nasOnly(MediaId.random())
    // La prima ha 30 minuti dal suo inizio, la seconda dal suo (dieci minuti dopo): a questo punto la prima e' scaduta e la seconda no.
    clock.advance(EditSessionRules.WINDOW_MS - 10 * 60_000L + 1)

    val keep = runBlocking { sessions.expireStale() }

    assertThat(keep).containsExactly(newer.cacheName)
    assertThat(stateOf(older)).isEqualTo(EditSessionState.EXPIRED)
    assertThat(stateOf(newer)).isEqualTo(EditSessionState.OPEN)
    assertThat(cacheFile(older).exists()).isFalse() // la copia di quella scaduta e' tolta
    assertThat(cacheFile(newer).exists()).isTrue()
    assertThat(FlowCounters.edit.get(EditCounter.EXPIRED)).isEqualTo(1L)
  }

  @Test
  fun leSessioniAncoraApertePortanoLeLoroCopieAllaPuliziaDellAvvio() {
    val session = nasOnly()

    val keep = runBlocking { sessions.expireStale() }
    cache.sweepAtStart(keep)

    assertThat(keep).containsExactly(session.cacheName)
    assertThat(cacheFile(session).readBytes()).isEqualTo(original) // viva: la sua copia resta
    clock.advance(EditSessionRules.WINDOW_MS + 1)
    cache.sweepAtStart(runBlocking { sessions.expireStale() })
    assertThat(cacheFile(session).exists()).isFalse()
  }

  @Test
  fun annullareUnaSessioneTogliLaCopiaERevocaIPermessi() {
    val session = nasOnly()

    runBlocking { sessions.cancel(session) }

    assertThat(stateOf(session)).isEqualTo(EditSessionState.CANCELLED)
    assertThat(cacheFile(session).exists()).isFalse()
    assertThat(revoker.revoked).hasSize(1)
    assertThat(settle(session)).isEqualTo(EditOutcome.Expired)
  }

  // --- Scarica e modifica in Galleria (E-E7) ---------------------------------------------------------------------------------------------

  @Test
  fun scaricaEModifica_LaModificaDellaCopiaInPicturesEUnaVersioneDelMediaDelNas_E_E7() {
    // Il media e' solo sul NAS: nessuna riga. Il download lo mette in Pictures/MyCloud Gallery, legato al media (F4-D20).
    catalog.media[mediaId] = EditMedia(mediaId, true, false, v1, emptyList(), hasNasCopy = true, takenAtUtcMs = taken)
    catalog.current[mediaId] = EditCurrent(v1, VersionKind.ORIGINAL)
    phone.put(row(60, path = "Pictures/MyCloud Gallery/"), original)
    val downloaded = ref(60)
    downloads.result = GalleryDownload.Saved(downloaded)
    catalog.media[mediaId] = EditMedia(mediaId, true, false, v1, listOf(downloaded), hasNasCopy = true, takenAtUtcMs = taken)

    val session = (runBlocking { sessions.begin(mediaId, EditTarget.NAS_DOWNLOAD) } as EditBegin.Started).session
    assertThat(session.target).isEqualTo(EditTarget.NAS_DOWNLOAD)
    assertThat(session.phone).isEqualTo(downloaded)
    assertThat(downloads.requests).isEqualTo(1)

    val v2 = VersionId.random()
    catalog.onRefresh = { catalog.current[mediaId] = EditCurrent(v2, VersionKind.EDITED) }
    val outcome = settle(session)

    assertThat(outcome).isEqualTo(EditOutcome.NewVersion(v2)) // una versione del media del NAS...
    assertThat(catalog.links).isEmpty() // ...e nessun media nuovo, nessun ricollegamento
  }

  @Test
  fun scaricaEModificaDiceIlMotivoSeIlDownloadNonRiesce() {
    catalog.media[mediaId] = EditMedia(mediaId, true, false, v1, emptyList(), hasNasCopy = true, takenAtUtcMs = taken)
    for ((result, reason) in listOf(
      GalleryDownload.Unreachable to EditUnavailable.NAS_UNREACHABLE,
      GalleryDownload.Full to EditUnavailable.PHONE_FULL,
      GalleryDownload.Unavailable to EditUnavailable.NOT_AVAILABLE,
    )) {
      downloads.result = result
      assertThat(runBlocking { sessions.begin(mediaId, EditTarget.NAS_DOWNLOAD) }).isEqualTo(EditBegin.Unavailable(reason))
    }
    assertThat(store.sessions).isEmpty()
  }
}

/** Byte di prova con la firma vera: un JPEG e un MP4. */
internal object ReceiveBytes {
  fun jpeg(size: Int = 5_000, seed: Int = 1): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(size - 4) { ((it * 31 + seed) and 0x7F or 0x80).toByte() }

  fun mp4(size: Int = 4_000): ByteArray =
    byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0, 0, 2, 0) +
      "isomiso2".toByteArray() + ByteArray(size - 24) { 0x90.toByte() }
}
