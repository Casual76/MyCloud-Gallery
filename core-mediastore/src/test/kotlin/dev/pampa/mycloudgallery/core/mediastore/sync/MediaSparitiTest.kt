package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.AttachedCopy
import dev.pampa.mycloudgallery.core.database.CatalogSource
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreSource
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
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pacchetto F (12/11, prove sul campo): i media cancellati dal telefono escono dal catalogo come presenti anche quando la cancellazione e' avvenuta
 * ad app chiusa, dopo un cambio di versione del MediaStore o con il permesso cambiato, e **mai** per sbaglio: lettura fallita a meta', permesso
 * ridotto, MediaStore che risponde vuoto. Ogni guardia ha la sua mutazione nel resoconto del pacchetto.
 */
@RunWith(AndroidJUnit4::class)
class MediaSparitiTest : PhoneSyncTestBase() {

  /** Come la base, con la soglia della riconciliazione diretta a 2: oltre, decide la scansione completa. */
  override val config = PhoneSyncConfig(batchSize = 3, vanishCap = 2, vanishAlarmMin = 50, reconcileMaxDirect = 2)

  /** Una sincronizzazione sullo stesso catalogo ma su [source]: un MediaStore che a un certo punto si comporta male. */
  private fun syncOn(source: MediaStoreSource) = DefaultPhoneLibrarySync(
    source = source,
    access = access,
    catalog = catalog,
    reads = reads,
    clock = { now },
    monotonicMs = { mono },
    dispatchers = InlineDispatchers,
    config = config,
  )

  private fun photos(ids: LongRange): List<MediaStoreRow> = ids.map { store.put(photo(it, taken = T0 + it * MINUTE)) }

  // --- Le cancellazioni si vedono --------------------------------------------------------------------------------------------------

  @Test
  fun cancellatoAdAppChiusaSiVedeAllaRiaperturaAncheSeIlGiroInBackgroundNonLHaVisto() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true

    // Ad app chiusa: una foto cancellata (generazione +1) e tre file di Quick Share arrivati in una transazione (generazione +1, tre righe).
    store.remove(PRIMARY, 2)
    store.putTogether((10L..12L).map { photo(it, taken = T0 + it * MINUTE, path = "Download/Quick Share/") })
    // Il giro in background legge le righe nuove: la generazione salita (2) sembra "spiegata" dalle tre righe, e la cancellazione non si vede.
    now += 3 * MINUTE
    val worker = sync.syncNow(SyncReason.WORKER)
    assertThat(worker.added).isEqualTo(3)
    assertThat(worker.vanished).isEqualTo(0)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.PRESENT)

    // L'utente riapre l'app: la generazione e' ferma, ma al ritorno gli _ID si confrontano con la cache.
    val back = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(back.vanished).isEqualTo(1)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    listOf(rows[0], rows[2], rows[3]).forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(sync.stats.snapshot.value.reconcileVanished).isEqualTo(1)
  }

  @Test
  fun allaRiaperturaConRigheNuoveInUnaTransazioneLaCancellazioneSiVedeSubito() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true
    store.remove(PRIMARY, 3)
    store.putTogether((10L..12L).map { photo(it, taken = T0 + it * MINUTE, path = "Download/Quick Share/") })
    now += 3 * MINUTE

    // Nessun giro in background: la riapertura vede le righe nuove e, anche se la generazione sembra spiegata, confronta gli _ID.
    val back = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(back.added).isEqualTo(3)
    assertThat(back.vanished).isEqualTo(1)
    assertThat(stateAt(rows[2])).isEqualTo(LocationState.ABSENT)
    // Un cambiamento qualsiasi (non il ritorno) resta com'era: la stima della generazione, al massimo ogni due minuti.
    store.remove(PRIMARY, 1)
    store.putTogether((20L..21L).map { photo(it, taken = T0 + it * MINUTE, path = "Download/Quick Share/") })
    now += 3 * MINUTE
    assertThat(sync.syncNow(SyncReason.CONTENT_CHANGE).vanished).isEqualTo(0)
    assertThat(stateAt(rows[0])).isEqualTo(LocationState.PRESENT)
  }

  @Test
  fun troppiSparitiInsiemeLaScansioneCompletaParteNelloStessoGiro() = runBlocking<Unit> {
    val rows = photos(1L..6L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true
    (1L..3L).forEach { store.remove(PRIMARY, it) }
    now += 3 * MINUTE

    // Tre spariti, oltre la soglia diretta (2): decide la scansione completa, subito e con le sue protezioni.
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.fullScan).isTrue()
    assertThat(report.completed).isTrue()
    assertThat(report.vanished).isEqualTo(3)
    rows.take(3).forEach { assertThat(stateAt(it)).isEqualTo(LocationState.ABSENT) }
    rows.drop(3).forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    val cursor = catalog.mediaStoreCursor(PRIMARY)!!
    assertThat(cursor.scanEpoch).isNull()
    assertThat(cursor.generation).isEqualTo(store.generation(PRIMARY))
  }

  @Test
  fun cancellatiAdAppChiusaConIlMediaStoreRicostruitoSiVedonoAllaRiapertura() = runBlocking<Unit> {
    val rows = photos(1L..5L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    // Ad app chiusa: due foto cancellate, poi il MediaStore si ricostruisce (aggiornamento di One UI): nuova versione, _ID rinumerati.
    store.remove(PRIMARY, 2)
    store.remove(PRIMARY, 4)
    store.bumpVersion(PRIMARY, "v2", renumber = mapOf(1L to 101L, 3L to 103L, 5L to 105L))
    now += MINUTE

    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.fullScan).isTrue()
    assertThat(report.vanished).isEqualTo(2)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[3])).isEqualTo(LocationState.ABSENT)
    listOf(rows[0], rows[2], rows[4]).forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(mediaCount()).isEqualTo(5)
  }

  @Test
  fun allaPrimaAperturaDellaVersioneCorrettaUnaScansioneCompletaUnaVoltaSola() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    newSync().syncNow(SyncReason.APP_START)
    // Il catalogo come l'ha lasciato la versione di prima: senza il segno e con una cancellazione mai vista (la generazione e' gia' oltre, e
    // l'ultimo confronto degli _ID e' di adesso).
    db.openHelper.writableDatabase.execSQL("DELETE FROM catalog_meta WHERE `key` = 'phone_full_scan_once_f1'")
    store.remove(PRIMARY, 2)
    val cursor = catalog.mediaStoreCursor(PRIMARY)!!
    catalog.saveMediaStoreCursor(cursor.copy(generation = store.generation(PRIMARY), deletedGeneration = store.generation(PRIMARY), lastReconcileAt = now))

    // Un giro qualunque (qui quello in background): la scansione completa chiesta una volta vede la cancellazione.
    now += MINUTE
    val sync = newSync()
    val first = sync.syncNow(SyncReason.WORKER)
    assertThat(first.fullScan).isTrue()
    assertThat(first.vanished).isEqualTo(1)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    listOf(rows[0], rows[2], rows[3]).forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    // Una volta sola: il giro dopo e' di nuovo incrementale, anche in un processo nuovo.
    store.put(photo(9, taken = T0 + 9 * MINUTE))
    assertThat(sync.syncNow(SyncReason.WORKER).fullScan).isFalse()
    assertThat(newSync().syncNow(SyncReason.WORKER).fullScan).isFalse()
  }

  // --- ...e mai per sbaglio ---------------------------------------------------------------------------------------------------------

  @Test
  fun permessoRidottoMentreSiLeggonoGliIdNonTogliNiente() = runBlocking<Unit> {
    val rows = photos(1L..5L)
    newSync().syncNow(SyncReason.APP_START)
    // L'utente passa a "foto selezionate" proprio mentre l'app legge gli _ID: il MediaStore risponde con la sola selezione.
    val flipping = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray {
        store.setSelection(PRIMARY, setOf(1L, 2L))
        setAccess(MediaAccess.PARTIAL)
        return store.allIds(volume)
      }
    }
    now += 3 * MINUTE

    val report = syncOn(flipping).syncNow(SyncReason.FOREGROUND)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(report.completed).isFalse()
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
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
    now += 3 * MINUTE

    assertThat(syncOn(saving).syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(stateAt(saved)).isEqualTo(LocationState.PRESENT)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  @Test
  fun unMediaStoreCheRispondeVuotoNonTogliNiente() = runBlocking<Unit> {
    // Due sole: senza la guardia sarebbero sotto la soglia diretta e si segnerebbero subito (con di piu' deciderebbe la scansione completa).
    val rows = photos(1L..2L)
    newSync().syncNow(SyncReason.APP_START)
    val empty = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray = LongArray(0)
    }
    now += 3 * MINUTE

    val sync = syncOn(empty)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(sync.stats.snapshot.value.absencesBlocked).isEqualTo(1)
  }

  @Test
  fun unaLetturaFallitaAMetaNonTogliNienteEIlGiroDopoFinisce() = runBlocking<Unit> {
    val rows = photos(1L..7L)
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    store.remove(PRIMARY, 2)
    store.remove(PRIMARY, 5)
    store.bumpVersion(PRIMARY, "v2")
    // La seconda pagina della scansione completa fallisce (I/O, processo fermato).
    store.failQueryAt = store.queries.size + 2
    now += MINUTE

    val broken = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(broken.completed).isFalse()
    assertThat(broken.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    store.failQueryAt = null
    now += MINUTE
    val done = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(done.completed).isTrue()
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[4])).isEqualTo(LocationState.ABSENT)
    rows.filterIndexed { i, _ -> i != 1 && i != 4 }.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  @Test
  fun permessoRidottoDuranteLaScansioneCompletaNonTogliNiente() = runBlocking<Unit> {
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
    now += MINUTE

    val report = syncOn(flipping).syncNow(SyncReason.FOREGROUND)
    assertThat(report.completed).isFalse()
    assertThat(report.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  @Test
  fun unaScansioneCompletaConGliIdVuotiNonTogliNiente() = runBlocking<Unit> {
    val rows = photos(1L..4L)
    newSync().syncNow(SyncReason.APP_START)
    store.remove(PRIMARY, 2)
    store.bumpVersion(PRIMARY, "v2")
    val empty = object : MediaStoreSource by store {
      override suspend fun allIds(volume: String): LongArray = LongArray(0)
    }
    now += MINUTE

    val sync = syncOn(empty)
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.fullScan).isTrue()
    assertThat(report.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(sync.stats.snapshot.value.absencesBlocked).isEqualTo(1)
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
    now += 3 * MINUTE

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
    now += 3 * MINUTE

    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(1)
    // Non si puo' piu' leggere: non e' "in coda" (non e' fra senza copia e non verificati), e' "perso prima del backup" (E-B4, D4), e il media resta.
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.LOST)
    assertThat(presenceOf(id)).isEqualTo(Presence.NONE.code)
    val counts = catalog.safetyCounts(camera)
    assertThat(counts.noNasCopy + counts.unverified).isEqualTo(1)
    assertThat(counts.lost).isEqualTo(1)
    assertThat(counts.problems).containsExactly("lost_before_backup", 1)
    assertThat(catalog.backupCandidates(camera, dev.pampa.mycloudgallery.core.database.CatalogBackupKind.PHOTOS, null, 10).map { it.mediaId })
      .containsExactly(mediaAt(rows[1]))
    assertThat(mediaCount()).isEqualTo(2)
  }
}
