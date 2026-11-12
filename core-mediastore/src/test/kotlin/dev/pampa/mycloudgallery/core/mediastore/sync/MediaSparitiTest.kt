package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.AttachedCopy
import dev.pampa.mycloudgallery.core.database.CatalogBackupKind
import dev.pampa.mycloudgallery.core.database.CatalogSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccessReader
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pacchetto F (12/11, prove sul campo e critica FS-1, FS-2, FS-4, FS-5): un media cancellato dal telefono esce dal catalogo come presente anche
 * quando la cancellazione e' avvenuta ad app chiusa o dopo un cambio di versione del MediaStore, ma **mai alla prima osservazione** e mai per
 * sbaglio: risposta parziale (anche oltre 500 righe), ondata sospetta, lettura fallita a meta', permesso cambiato, volume che sparisce, riga in
 * scrittura. Una posizione segnata assente che ricompare torna presente anche a generazione ferma. Ogni guardia ha la sua mutazione nel resoconto.
 */
@RunWith(AndroidJUnit4::class)
class MediaSparitiTest : PhoneSyncTestBase() {

  /** Come la base: la seconda osservazione dopo [PhoneSyncConfig.absenceConfirmAfter] (5 minuti), il confronto al ritorno ogni 2. */
  override val config = PhoneSyncConfig(batchSize = 3, vanishCap = 2, vanishAlarmMin = 50)

  /** Piu' del tempo della seconda osservazione e del limite del confronto al ritorno. */
  private val wait = 6 * MINUTE

  /** Una sincronizzazione sullo stesso catalogo ma su [source] (un MediaStore che a un certo punto si comporta male). */
  private fun syncOn(
    source: MediaStoreSource,
    pointCheck: MediaStorePointCheck? = store,
    config: PhoneSyncConfig = this.config,
  ) = DefaultPhoneLibrarySync(
    source = source,
    access = access,
    catalog = catalog,
    reads = reads,
    clock = { now },
    monotonicMs = { mono },
    dispatchers = InlineDispatchers,
    config = config,
    pointCheck = pointCheck,
  )

  private fun photos(ids: LongRange): List<MediaStoreRow> = ids.map { store.put(photo(it, taken = T0 + it * MINUTE)) }

  /** Il MediaStore che elenca gli `_ID` senza quelli di [hidden] (un provider in ricostruzione): righe e controllo puntuale restano veri. */
  private fun listingWithout(hidden: Set<Long>) = object : MediaStoreSource by store {
    override suspend fun allIds(volume: String): LongArray = store.allIds(volume).filter { it !in hidden }.toLongArray()
  }

  private fun suspected(sync: DefaultPhoneLibrarySync) = sync.stats.snapshot.value.suspectedAbsences

  // --- Le cancellazioni si vedono, al secondo confronto ------------------------------------------------------------------------------

  @Test
  fun cancellatoAdAppChiusaSiVedeAlSecondoConfrontoNonAlPrimo() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true

    // Ad app chiusa: una foto cancellata (generazione +1) e tre file di Quick Share arrivati in una transazione (generazione +1, tre righe).
    store.remove(PRIMARY, 2)
    store.putTogether((10L..12L).map { photo(it, taken = T0 + it * MINUTE, path = "Download/Quick Share/") })
    // Il giro in background legge le righe nuove: la generazione salita (2) sembra "spiegata" dalle tre righe; la cancellazione non si vede.
    advance(3 * MINUTE)
    val worker = sync.syncNow(SyncReason.WORKER)
    assertThat(worker.added).isEqualTo(3)
    assertThat(worker.vanished).isEqualTo(0)

    // L'utente riapre l'app: la generazione e' ferma, ma al ritorno gli _ID si confrontano. Prima osservazione: si annota e basta.
    val first = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(first.vanished).isEqualTo(0)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.PRESENT)
    assertThat(suspected(sync)).isEqualTo(1)

    // Tre minuti dopo: il confronto rifatto, ma la seconda osservazione vuole cinque minuti dalla prima.
    advance(3 * MINUTE)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.PRESENT)

    // Passati: manca ancora, il controllo puntuale non la trova, diventa assente. Le altre restano.
    advance(3 * MINUTE)
    val second = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(second.vanished).isEqualTo(1)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    listOf(rows[0], rows[2], rows[3]).forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(suspected(sync)).isEqualTo(0)
    assertThat(store.pointChecks).isEqualTo(1)
  }

  @Test
  fun allaRiaperturaConRigheNuoveInUnaTransazioneSiConfrontanoGliId() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true
    store.remove(PRIMARY, 3)
    store.putTogether((10L..12L).map { photo(it, taken = T0 + it * MINUTE, path = "Download/Quick Share/") })
    advance(3 * MINUTE)

    // La riapertura vede le righe nuove e, anche se la generazione sembra spiegata, confronta gli _ID: prima osservazione.
    val back = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(back.added).isEqualTo(3)
    assertThat(back.vanished).isEqualTo(0)
    assertThat(suspected(sync)).isEqualTo(1)
    // Un cambiamento qualsiasi (non il ritorno) resta com'era: la stima della generazione decide, e qui non chiede il confronto.
    store.putTogether((20L..21L).map { photo(it, taken = T0 + it * MINUTE, path = "Download/Quick Share/") })
    advance(wait)
    assertThat(sync.syncNow(SyncReason.CONTENT_CHANGE).vanished).isEqualTo(0)
    assertThat(stateAt(rows[2])).isEqualTo(LocationState.PRESENT)
    // Il ritorno dopo: seconda osservazione.
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(1)
    assertThat(stateAt(rows[2])).isEqualTo(LocationState.ABSENT)
  }

  @Test
  fun cancellatiAdAppChiusaConIlMediaStoreRicostruitoSiVedonoAlSecondoConfronto() = runBlocking<Unit> {
    val rows = photos(1L..5L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    // Ad app chiusa: due foto cancellate, poi il MediaStore si ricostruisce (aggiornamento di One UI): nuova versione, _ID rinumerati.
    store.remove(PRIMARY, 2)
    store.remove(PRIMARY, 4)
    store.bumpVersion(PRIMARY, "v2", renumber = mapOf(1L to 101L, 3L to 103L, 5L to 105L))
    advance(MINUTE)

    // La scansione completa rilegge tutto e si chiude, ma la fine della lettura non scrive assenze: le non viste sono annotate.
    val scan = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(scan.fullScan).isTrue()
    assertThat(scan.completed).isTrue()
    assertThat(scan.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(suspected(sync)).isEqualTo(2)
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.scanEpoch).isNull()

    // Seconda osservazione: il loro _ID e' della versione vecchia, decide il controllo puntuale per posto.
    advance(wait)
    val second = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(second.vanished).isEqualTo(2)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[3])).isEqualTo(LocationState.ABSENT)
    listOf(rows[0], rows[2], rows[4]).forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(mediaCount()).isEqualTo(5)
  }

  @Test
  fun unaPosizioneRimastaConLIdVecchioMaPresenteImparaQuelloNuovo() = runBlocking<Unit> {
    val rows = photos(1L..3L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    store.bumpVersion(PRIMARY, "v2", renumber = mapOf(1L to 101L, 2L to 102L, 3L to 103L))
    // La scansione completa non vede la riga 102 (un provider che la mostra in ritardo): resta presente con l'_ID della versione vecchia.
    var hide = true
    val late = object : MediaStoreSource by store {
      override suspend fun query(volume: String, sinceGeneration: Long?, afterId: Long?, limit: Int): List<MediaStoreRow> =
        store.query(volume, sinceGeneration, afterId, limit).filter { !hide || it.id != 102L }
    }
    advance(MINUTE)
    val s = syncOn(late)
    assertThat(s.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(suspected(s)).isEqualTo(1)

    // Seconda osservazione: il controllo puntuale la trova per posto e la rilegge: _ID nuovo, nessuna assenza.
    hide = false
    advance(wait)
    assertThat(s.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    val id = mediaAt(rows[1])!!
    assertThat(catalog.phoneRefs(listOf(id)).getValue(id).mediaStoreId).isEqualTo(102L)
    assertThat(suspected(s)).isEqualTo(0)
  }

  // --- ...e mai per sbaglio: risposte parziali, ondate, letture fallite -----------------------------------------------------------------

  @Test
  fun unaRispostaParzialeNonVuotaNonTogliNienteNeSubitoNeDopo() = runBlocking<Unit> {
    val rows = photos(1L..6L)
    newSync().syncNow(SyncReason.APP_START)
    // Il provider (in ricostruzione dopo un riavvio) elenca 3 _ID su 6; le righe ci sono.
    val partial = syncOn(listingWithout(setOf(4L, 5L, 6L)))
    advance(3 * MINUTE)
    assertThat(partial.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(suspected(partial)).isEqualTo(3)

    // Anche alla seconda osservazione l'elenco e' parziale: il controllo puntuale le trova e restano.
    advance(wait)
    assertThat(partial.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(suspected(partial)).isEqualTo(0)
  }

  @Test
  fun unaRispostaParzialeOltreLeVecchie500RigheNonTogliNiente() = runBlocking<Unit> {
    val big = config.copy(batchSize = 500)
    val ids = 1L..1_050L
    store.putTogether(ids.map { photo(it, taken = T0 + it * MINUTE) })
    syncOn(store, config = big).syncNow(SyncReason.APP_START)
    assertThat(mediaCount()).isEqualTo(1_050)
    // 510 _ID fuori dall'elenco (prima: oltre 500 decideva la scansione completa); 540 vivi, quindi non e' un'ondata.
    val hidden = (541L..1_050L).toSet()
    val partial = syncOn(listingWithout(hidden), config = big)
    advance(3 * MINUTE)
    assertThat(partial.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(suspected(partial)).isEqualTo(510)
    advance(wait)
    assertThat(partial.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(db.locationDao().phonePositionsOnVolume(PRIMARY).count { it.state == LocationState.ABSENT.code }).isEqualTo(0)
  }

  @Test
  fun unOndataSospettaNonScriveNienteNemmenoAlSecondoConfronto() = runBlocking<Unit> {
    // 50 posizioni, ne restano vive 3: 47 mancanti, piu' dei vivi e oltre la soglia (10). Che siano cancellazioni vere o un provider che
    // risponde a meta', un'ondata cosi' non si scrive da sola.
    val sharp = config.copy(vanishAlarmMin = 10)
    val rows = photos(1L..50L)
    syncOn(store, config = sharp).syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true
    (4L..50L).forEach { store.remove(PRIMARY, it) }
    val sync = syncOn(store, config = sharp)
    advance(3 * MINUTE)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    advance(wait)
    val second = sync.syncNow(SyncReason.FOREGROUND)

    // Nessuna scrittura, nemmeno le prime: prima si guarda l'ondata, poi (se mai) si scrive. Restano annotate.
    assertThat(second.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(sync.stats.snapshot.value.absencesBlocked).isEqualTo(1)
    assertThat(suspected(sync)).isEqualTo(47)
    assertThat(store.pointChecks).isEqualTo(0)
  }

  @Test
  fun unaPosizioneSegnataAssenteCheRicompareTornaPresenteAGenerazioneFerma() = runBlocking<Unit> {
    val rows = photos(1L..5L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val ids = rows.map { mediaAt(it)!! }
    // Il provider mostra solo due righe, anche al controllo puntuale, per piu' della seconda osservazione: le altre diventano assenti.
    store.setSelection(PRIMARY, setOf(1L, 2L))
    advance(3 * MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    advance(wait)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(3)
    rows.drop(2).forEach { assertThat(stateAt(it)).isEqualTo(LocationState.ABSENT) }

    // Il provider torna completo senza che la generazione cambi: al ritorno dopo il confronto le righe tornano, stesso media.
    val generation = store.generation(PRIMARY)
    store.setSelection(PRIMARY, null)
    advance(3 * MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    assertThat(store.generation(PRIMARY)).isEqualTo(generation)
    rows.forEachIndexed { i, row ->
      assertThat(stateAt(row)).isEqualTo(LocationState.PRESENT)
      assertThat(mediaAt(row)).isEqualTo(ids[i])
    }
    assertThat(mediaCount()).isEqualTo(5)
  }

  @Test
  fun unaRigaCheDiventaInScritturaNonEUnaCancellazione() = runBlocking<Unit> {
    val rows = photos(1L..3L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    // Un'app riapre la foto 2 in scrittura (IS_PENDING): sparisce dalle query, ma il file c'e'.
    store.putPending(rows[1])
    advance(3 * MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    assertThat(suspected(sync)).isEqualTo(1)
    advance(wait)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.PRESENT)
    assertThat(store.pointChecks).isEqualTo(1)
  }

  @Test
  fun unaLetturaFallitaAMetaNonChiudeLaScansioneNeTogliNiente() = runBlocking<Unit> {
    val rows = photos(1L..7L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val scans = sync.stats.snapshot.value.fullScansCompleted
    store.remove(PRIMARY, 2)
    store.remove(PRIMARY, 5)
    store.bumpVersion(PRIMARY, "v2")
    // La seconda pagina della scansione completa fallisce (I/O, processo fermato).
    store.failQueryAt = store.queries.size + 2
    advance(MINUTE)

    val broken = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(broken.completed).isFalse()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.scanEpoch).isNotNull()
    assertThat(sync.stats.snapshot.value.fullScansCompleted).isEqualTo(scans)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    // Il giro dopo riprende e chiude la scansione; le due cancellate diventano assenti solo alla seconda osservazione.
    store.failQueryAt = null
    advance(MINUTE)
    val done = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(done.completed).isTrue()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.scanEpoch).isNull()
    assertThat(done.vanished).isEqualTo(0)
    advance(wait)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(2)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[4])).isEqualTo(LocationState.ABSENT)
    rows.filterIndexed { i, _ -> i != 1 && i != 4 }.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  // --- FS-2 e FS-5: la fine della scansione completa -------------------------------------------------------------------------------

  @Test
  fun ilPermessoCheCambiaNellUltimaLetturaDegliIdNonChiudeLaScansione() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    newSync().syncNow(SyncReason.APP_START)
    store.remove(PRIMARY, 2)
    store.bumpVersion(PRIMARY, "v2")
    // La prima lettura degli _ID e' per l'avanzamento; la seconda e' quella della fine: li' l'utente passa a "foto selezionate".
    var calls = 0
    val flipping = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray {
        calls++
        if (calls == 2) {
          store.setSelection(PRIMARY, setOf(1L))
          setAccess(MediaAccess.PARTIAL)
        }
        return store.allIds(volume)
      }
    }
    advance(MINUTE)
    val sync = syncOn(flipping)
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(calls).isEqualTo(2)
    assertThat(report.completed).isFalse()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.scanEpoch).isNotNull()
    assertThat(sync.stats.snapshot.value.fullScansCompleted).isEqualTo(0)
    assertThat(suspected(sync)).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    // L'accesso torna completo: il giro dopo chiude la scansione (ripete solo la fine).
    setAccess(MediaAccess.FULL)
    store.setSelection(PRIMARY, null)
    advance(MINUTE)
    val done = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(done.completed).isTrue()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.scanEpoch).isNull()
    assertThat(sync.stats.snapshot.value.fullScansCompleted).isEqualTo(1)
  }

  @Test
  fun unaRispostaVuotaAFineScansioneNonLaConclude() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    newSync().syncNow(SyncReason.APP_START)
    store.remove(PRIMARY, 2)
    store.bumpVersion(PRIMARY, "v2")
    var calls = 0
    val empty = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray {
        calls++
        return if (calls == 2) LongArray(0) else store.allIds(volume)
      }
    }
    advance(MINUTE)
    val sync = syncOn(empty)
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.completed).isFalse()
    assertThat(sync.stats.snapshot.value.absencesBlocked).isEqualTo(1)
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.scanEpoch).isNotNull()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.lastFullScanAt).isNotEqualTo(now)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    // Il giro dopo, con un MediaStore che risponde, conclude.
    advance(MINUTE)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).completed).isTrue()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.lastFullScanAt).isEqualTo(now)
  }

  @Test
  fun permessoRidottoDuranteLaScansioneCompletaNonLaChiude() = runBlocking<Unit> {
    val rows = photos(1L..5L)
    newSync().syncNow(SyncReason.APP_START)
    store.remove(PRIMARY, 1)
    store.bumpVersion(PRIMARY, "v2")
    // Alla seconda pagina l'utente passa a "foto selezionate": il MediaStore risponde solo con la selezione, e la scansione finisce "presto".
    var pages = 0
    val flipping = object : MediaStoreSource by store {
      override suspend fun query(volume: String, sinceGeneration: Long?, afterId: Long?, limit: Int): List<MediaStoreRow> {
        pages++
        if (pages == 2) {
          store.setSelection(PRIMARY, setOf(5L))
          setAccess(MediaAccess.PARTIAL)
        }
        return store.query(volume, sinceGeneration, afterId, limit)
      }
    }
    advance(MINUTE)
    val sync = syncOn(flipping)
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.completed).isFalse()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.scanEpoch).isNotNull()
    assertThat(suspected(sync)).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  // --- Il confronto degli _ID: permesso, risposta vuota, volume, salvataggi --------------------------------------------------------------

  @Test
  fun permessoRidottoMentreSiLeggonoGliIdNonAnnotaNiente() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    newSync().syncNow(SyncReason.APP_START)
    // L'utente passa a "foto selezionate" proprio mentre l'app legge gli _ID: il MediaStore risponde con la sola selezione.
    val flipping = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray {
        store.setSelection(PRIMARY, setOf(1L, 2L))
        setAccess(MediaAccess.PARTIAL)
        return store.allIds(volume)
      }
    }
    advance(3 * MINUTE)
    val sync = syncOn(flipping)
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(report.completed).isFalse()
    // Nemmeno annotate: una lista ridotta dal permesso non e' un'osservazione.
    assertThat(suspected(sync)).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  @Test
  fun unVolumeToltoMentreSiLeggonoGliIdNonAnnotaNiente() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    newSync().syncNow(SyncReason.APP_START)
    val unmounting = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray {
        val ids = store.allIds(volume).filter { it <= 2L }.toLongArray()
        store.setMounted(PRIMARY, false)
        return ids
      }
    }
    advance(3 * MINUTE)
    val sync = syncOn(unmounting)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).completed).isFalse()
    assertThat(suspected(sync)).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  @Test
  fun unVolumeToltoDuranteIlControlloPuntualeNonTogliNiente() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    newSync().syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true
    store.remove(PRIMARY, 2)
    // Al secondo confronto il volume sparisce proprio durante il controllo puntuale (e quindi non risponde niente).
    val unmounting = object : MediaStorePointCheck {
      override suspend fun stillThere(volume: String, ids: Collection<Long>, places: Collection<PhonePlace>): List<PointRow> {
        store.setMounted(PRIMARY, false)
        return emptyList()
      }
    }
    val sync = syncOn(store, pointCheck = unmounting)
    advance(3 * MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    assertThat(suspected(sync)).isEqualTo(1)
    advance(wait)
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(report.completed).isFalse()
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.PRESENT)
    // Il giro dopo vede il volume tolto: non raggiungibili, mai assenti (E-P6).
    advance(MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.UNAVAILABLE) }
  }

  @Test
  fun unVolumeToltoDuranteLaScansioneCompletaNonTogliNiente() = runBlocking<Unit> {
    val rows = photos(1L..5L)
    newSync().syncNow(SyncReason.APP_START)
    store.bumpVersion(PRIMARY, "v2")
    var pages = 0
    val unmounting = object : MediaStoreSource by store {
      override suspend fun query(volume: String, sinceGeneration: Long?, afterId: Long?, limit: Int): List<MediaStoreRow> {
        pages++
        if (pages == 2) store.setMounted(PRIMARY, false)
        return store.query(volume, sinceGeneration, afterId, limit)
      }
    }
    advance(MINUTE)
    val sync = syncOn(unmounting)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).completed).isFalse()
    advance(MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.UNAVAILABLE) }
  }

  @Test
  fun unFileSalvatoDallAppMentreSiLeggonoGliIdNonSembraSparito() = runBlocking<Unit> {
    val rows = photos(1L..3L)
    newSync().syncNow(SyncReason.APP_START)
    // Mentre la lettura confronta gli _ID, l'app salva una foto sul telefono (dal NAS): la riga nasce nel MediaStore e il catalogo la registra
    // subito, fuori dal giro. Gli _ID letti prima del salvataggio non la contengono.
    val saved = photo(50, taken = T0 + 50 * MINUTE, path = "Pictures/MyCloud/")
    val saving = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray {
        val before = store.allIds(volume)
        val row = store.put(saved)
        val content = ByteArray(row.size.toInt()) { (it % 251).toByte() }
        val fingerprint = Fingerprint.ofContent(content)
        catalog.record(Observation(row.phoneLocation(store.version(volume)), FileStamp(fingerprint.size, row.dateModifiedMs), fingerprint, MediaKind.PHOTO, MediaSource.OTHER))
        return before
      }
    }
    advance(3 * MINUTE)
    val sync = syncOn(saving)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    // Nemmeno annotata: le posizioni si leggono prima degli _ID.
    assertThat(suspected(sync)).isEqualTo(0)
    assertThat(stateAt(saved)).isEqualTo(LocationState.PRESENT)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  @Test
  fun unMediaStoreCheRispondeVuotoNonAnnotaNiente() = runBlocking<Unit> {
    val rows = photos(1L..2L)
    newSync().syncNow(SyncReason.APP_START)
    val empty = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray = LongArray(0)
    }
    advance(3 * MINUTE)
    val sync = syncOn(empty)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(sync.stats.snapshot.value.absencesBlocked).isEqualTo(1)
    assertThat(suspected(sync)).isEqualTo(0)
  }

  @Test
  fun lAccessoSiRicontrollaPrimaDiOgniLottoDiAssenze() = runBlocking<Unit> {
    val rows = photos(1L..5L)
    newSync().syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true
    listOf(2L, 3L, 4L).forEach { store.remove(PRIMARY, it) }
    // Un lettore dei permessi che passa a "foto selezionate" alla rilettura numero [flipAt] del giro.
    var refreshes = 0
    var flipAt = Int.MAX_VALUE
    val counting = object : PhoneAccessReader {
      private val flow = MutableStateFlow(PhoneAccess(MediaAccess.FULL, mediaLocation = true, volumes = setOf(PRIMARY)))
      override val state: StateFlow<PhoneAccess> = flow

      override fun refresh() {
        refreshes++
        if (refreshes == flipAt) flow.value = PhoneAccess(MediaAccess.PARTIAL, mediaLocation = true, volumes = setOf(PRIMARY))
      }
    }
    val sync = DefaultPhoneLibrarySync(
      source = store, access = counting, catalog = catalog, reads = reads, clock = { now }, monotonicMs = { mono },
      dispatchers = InlineDispatchers, config = config,
    )
    advance(3 * MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    assertThat(suspected(sync)).isEqualTo(3)
    advance(wait)
    // Il giro di conferma rilegge i permessi: all'inizio (1), dopo gli _ID (2), dopo il controllo puntuale (3), prima di ogni lotto di due
    // assenze (4, 5). Al secondo lotto l'accesso e' cambiato: quel lotto non si scrive.
    refreshes = 0
    flipAt = 5
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.vanished).isEqualTo(2)
    assertThat(report.completed).isFalse()
    assertThat(rows.subList(1, 4).count { stateAt(it) == LocationState.ABSENT }).isEqualTo(2)
    assertThat(stateAt(rows[0])).isEqualTo(LocationState.PRESENT)
    assertThat(stateAt(rows[4])).isEqualTo(LocationState.PRESENT)
  }

  @Test
  fun senzaControlloPuntualeNessunaAssenzaDedotta() = runBlocking<Unit> {
    val rows = photos(1L..3L)
    newSync().syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true
    store.remove(PRIMARY, 2)
    val sync = syncOn(store, pointCheck = null)
    advance(3 * MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    advance(wait)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.PRESENT)
  }

  // --- Che cosa resta di un media sparito ------------------------------------------------------------------------------------------

  @Test
  fun unMediaSparitoConLaCopiaSulNasRestaSulNasConTuttoIlResto() = runBlocking<Unit> {
    catalog.registerShare(NasShare(ME, "alessio", NasArea.PRIVATE))
    val rows = photos(1L..2L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val id = mediaAt(rows[0])!!
    catalog.setPersonalFlags(id, PersonalFlags(favorite = true))
    val version = catalog.media(id)!!.currentVersionId
    val copy = Location.Nas(ME, "alessio", NasPath.of("Backup/S25/IMG_1.jpg"), null)
    catalog.attachCopies(listOf(AttachedCopy(id, version, copy, FileStamp(rows[0].size, T0, null), null)))
    store.deletedFilesEmptyForApps = true
    store.remove(PRIMARY, 1)
    advance(3 * MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    advance(wait)

    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(1)
    assertThat(stateAt(rows[0])).isEqualTo(LocationState.ABSENT)
    // Lo stesso media (stesso id, stessa versione, preferito), con la copia del NAS al suo posto: il backup e' solo aggiunte.
    val media = catalog.media(id)!!
    assertThat(media.currentVersionId).isEqualTo(version)
    assertThat(media.locations.filter { it.isNas && it.isPresent }.map { it.location }).containsExactly(copy)
    assertThat(presenceOf(id)).isEqualTo(Presence.NAS_ONLY.code)
    assertThat(db.personalDao().get(id.value)!!.favorite).isTrue()
    assertThat(mediaCount()).isEqualTo(2)
  }

  @Test
  fun unMediaSparitoSenzaCopiaNonEPiuInCodaMaPersoPrimaDelBackup() = runBlocking<Unit> {
    val rows = photos(1L..2L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val id = mediaAt(rows[0])!!
    val camera = setOf<CatalogSource>(CatalogSource.Camera)
    assertThat(catalog.safetyCounts(camera).noNasCopy).isEqualTo(2)
    store.deletedFilesEmptyForApps = true
    store.remove(PRIMARY, 1)
    advance(3 * MINUTE)
    sync.syncNow(SyncReason.FOREGROUND)
    advance(wait)

    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(1)
    // Non si puo' piu' leggere: non e' "in coda" (non e' fra senza copia e non verificati), e' "perso prima del backup" (E-B4, D4), e il media resta.
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.LOST)
    assertThat(presenceOf(id)).isEqualTo(Presence.NONE.code)
    val counts = catalog.safetyCounts(camera)
    assertThat(counts.noNasCopy + counts.unverified).isEqualTo(1)
    assertThat(counts.lost).isEqualTo(1)
    assertThat(counts.problems).containsExactly("lost_before_backup", 1)
    assertThat(catalog.backupCandidates(camera, CatalogBackupKind.PHOTOS, null, 10).map { it.mediaId }).containsExactly(mediaAt(rows[1]))
    assertThat(mediaCount()).isEqualTo(2)
  }

  /**
   * FS-4: una foto mai salvata e cancellata dal telefono resta "persa prima del backup" anche dopo i 60 giorni della tolleranza: la manutenzione
   * non dimentica la sua posizione del telefono finche' il problema c'e' (senza, uscirebbe dalle righe del backup con il suo avviso). Una foto
   * con una copia sul NAS, invece, perde la posizione del telefono come prima.
   */
  @Test
  fun unaFotoPersaPrimaDelBackupNonSparisceDaiProblemiDopo60Giorni() = runBlocking<Unit> {
    catalog.registerShare(NasShare(ME, "alessio", NasArea.PRIVATE))
    val rows = photos(1L..2L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val withCopy = mediaAt(rows[1])!!
    catalog.attachCopies(
      listOf(AttachedCopy(withCopy, catalog.media(withCopy)!!.currentVersionId, Location.Nas(ME, "alessio", NasPath.of("Backup/S25/IMG_2.jpg"), null), FileStamp(rows[1].size, T0, null), null)),
    )
    // Tutte e due cancellate in Galleria: `deleted_files` lo dice, assenti subito (nessun diario del backup: mai caricate da qui).
    store.remove(PRIMARY, 1)
    store.remove(PRIMARY, 2)
    sync.syncNow(SyncReason.CONTENT_CHANGE)
    val camera = setOf<CatalogSource>(CatalogSource.Camera)
    assertThat(catalog.safetyCounts(camera).problems).containsExactly("lost_before_backup", 1)

    advance(61 * 24 * 60 * MINUTE)
    sync.syncNow(SyncReason.MAINTENANCE)

    assertThat(stateAt(rows[0])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[1])).isNull()
    assertThat(sync.stats.snapshot.value.purgedAbsences).isEqualTo(1)
    val counts = catalog.safetyCounts(camera)
    assertThat(counts.problems).containsExactly("lost_before_backup", 1)
    assertThat(counts.lost).isEqualTo(1)
  }
}
