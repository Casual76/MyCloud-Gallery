package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.model.LocationState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La scansione completa (docs/fase3-progetto.md §2.1): dai piu' recenti, a lotti, riprendibile dopo un riavvio (E-P4,
 * E-P8) senza rileggere cio' che e' gia' fatto, con le assenze solo alla fine e con il tetto del catalogo.
 */
@RunWith(AndroidJUnit4::class)
class FullScanTest : PhoneSyncTestBase() {

  /** Soglia d'allarme bassa: l'ondata sospetta si vede con poche righe. */
  override val config = PhoneSyncConfig(batchSize = 3, vanishCap = 2, vanishAlarmMin = 10)

  @Test
  fun primaScansioneDaiPiuRecentiERipresaAMetaDopoUnRiavvio() = runBlocking<Unit> {
    // Sette foto, scattate in ordine sparso rispetto agli _ID.
    val taken = listOf(5L, 1L, 7L, 3L, 6L, 2L, 4L)
    val rows = taken.mapIndexed { i, t -> store.put(photo(id = 100L + i, taken = T0 + t * MINUTE)) }
    val newestFirst = rows.sortedByDescending { it.dateTakenMs }
    val g0 = store.generation(PRIMARY)

    // Il primo processo muore alla seconda query: un lotto (i tre piu' recenti) e' gia' nel catalogo.
    store.failQueryAt = 2
    val first = newSync().syncNow(SyncReason.APP_START)
    assertThat(first.completed).isFalse()
    assertThat(first.fullScan).isTrue()
    assertThat(mediaCount()).isEqualTo(3)
    newestFirst.take(3).forEach { assertThat(mediaAt(it)).isNotNull() }
    newestFirst.drop(3).forEach { assertThat(mediaAt(it)).isNull() }
    val paused = catalog.mediaStoreCursor(PRIMARY)!!
    assertThat(paused.scanEpoch).isNotNull()
    assertThat(paused.scanResumeAfterId).isEqualTo(newestFirst[2].id)
    assertThat(store.opens).isEqualTo(3)

    // Il processo nuovo riprende dopo l'ultimo _ID fatto, con la stessa epoca: nessuna rilettura.
    store.failQueryAt = null
    val queriesBefore = store.queries.size
    now += 10 * MINUTE
    val second = newSync().syncNow(SyncReason.APP_START)
    assertThat(second.completed).isTrue()
    assertThat(second.fullScan).isTrue()
    assertThat(store.queries[queriesBefore].afterId).isEqualTo(newestFirst[2].id)
    assertThat(store.opens).isEqualTo(7)
    assertThat(mediaCount()).isEqualTo(7)
    assertThat(second.added).isEqualTo(4)
    assertThat(second.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    val done = catalog.mediaStoreCursor(PRIMARY)!!
    assertThat(done.scanEpoch).isNull()
    assertThat(done.scanResumeAfterId).isNull()
    assertThat(done.lastFullScanAt).isEqualTo(now)
    // La generazione e' quella letta PRIMA della scansione: cio' che cambia durante lo vede l'incrementale.
    assertThat(done.generation).isEqualTo(g0)
    assertThat(done.deletedGeneration).isEqualTo(g0)
    assertThat(done.msVersion).isEqualTo("v1")
  }

  @Test
  fun lAvanzamentoSiVedeEAllaFineLaLibreriaEFerma() = runBlocking<Unit> {
    repeat(4) { i -> store.put(photo(id = 10L + i, taken = T0 + i * MINUTE)) }
    val sync = newSync()
    val report = sync.syncNow(SyncReason.APP_START)
    assertThat(report.seen).isEqualTo(4)
    assertThat(sync.status.value).isEqualTo(SyncStatus.Idle(now))
    assertThat(sync.stats.snapshot.value.fullScansCompleted).isEqualTo(1)
  }

  @Test
  fun leAssenzeNonSiSegnanoAFineScansioneMaAlConfrontoDopoEConIlTetto() = runBlocking<Unit> {
    val rows = (1L..5L).map { store.put(photo(id = it, taken = T0 + it * MINUTE)) }
    newSync().syncNow(SyncReason.APP_START)
    assertThat(mediaCount()).isEqualTo(5)

    // Tre file spariti senza traccia in deleted_files, poi un aggiornamento del MediaStore: scansione completa.
    store.supportsDeletedFiles = false
    listOf(1L, 3L, 5L).forEach { store.remove(PRIMARY, it) }
    store.bumpVersion(PRIMARY, "v2")
    advance(60 * MINUTE)
    val sync = newSync()
    val scan = sync.syncNow(SyncReason.APP_START)

    // Pacchetto F (FS-1): la fine della lettura non scrive assenze; le non viste sono annotate.
    assertThat(scan.fullScan).isTrue()
    assertThat(scan.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    // Seconda osservazione, cinque minuti dopo: il controllo puntuale non le trova.
    advance(6 * MINUTE)
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.vanished).isEqualTo(3)
    // Tetto di 2 per chiamata: tre assenze in due chiamate, ognuna con la sua transazione.
    assertThat(stateAt(rows[0])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[2])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[4])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.PRESENT)
    assertThat(stateAt(rows[3])).isEqualTo(LocationState.PRESENT)
    // Assenti, non cancellati: le identita' restano (E-P11).
    assertThat(mediaCount()).isEqualTo(5)
  }

  @Test
  fun unMediaStoreCheRispondeVuotoNonSvuotaLaLibreria() = runBlocking<Unit> {
    val rows = (1L..4L).map { store.put(photo(id = it, taken = T0 + it * MINUTE)) }
    newSync().syncNow(SyncReason.APP_START)

    // Una scansione completa in cui il provider non restituisce nulla (permessi ritirati sotto i piedi, E-P15).
    store.bumpVersion(PRIMARY, "v2")
    store.accessRevoked = true
    val sync = newSync()
    val report = sync.syncNow(SyncReason.APP_START)

    assertThat(report.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(sync.stats.snapshot.value.absencesBlocked).isEqualTo(1)
  }

  @Test
  fun unOndataDiAssenzeSospetteNonScriveNiente() = runBlocking<Unit> {
    // 50 posizioni, ne restano vive 3: la soglia e' max(10, vivi) = 10.
    val rows = (1L..50L).map { store.put(photo(id = it, taken = T0 + it * MINUTE)) }
    newSync().syncNow(SyncReason.APP_START)
    store.supportsDeletedFiles = false
    (4L..50L).forEach { store.remove(PRIMARY, it) }
    store.bumpVersion(PRIMARY, "v2")
    advance(MINUTE)
    val sync = newSync()
    assertThat(sync.syncNow(SyncReason.APP_START).vanished).isEqualTo(0)
    advance(6 * MINUTE)
    val report = sync.syncNow(SyncReason.FOREGROUND)

    // Pacchetto F (FS-1): prima si fermava dopo averne segnate 10; adesso l'ondata si guarda prima di scrivere, e non si scrive niente.
    assertThat(report.vanished).isEqualTo(0)
    assertThat(sync.stats.snapshot.value.absencesBlocked).isEqualTo(1)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }
}
