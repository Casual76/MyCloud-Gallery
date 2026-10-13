package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.model.LocationState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'incrementale per generazione, `deleted_files` come accelerazione (con la riconciliazione degli `_ID` come verita'),
 * `IS_PENDING` e il cestino di sistema.
 */
@RunWith(AndroidJUnit4::class)
class IncrementalSyncTest : PhoneSyncTestBase() {

  @Test
  fun incrementaleLeggeSoloLeRigheCambiateDopoLaGenerazioneSalvata() = runBlocking<Unit> {
    val a = store.put(photo(1, taken = T0))
    val b = store.put(photo(2, taken = T0 + MINUTE))
    store.put(video(3, taken = T0 + 2 * MINUTE))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val saved = catalog.mediaStoreCursor(PRIMARY)!!.generation!!
    val opensAfterFirst = store.opens

    // Una foto nuova e una modificata (stesso _ID, stessa versione: continuita', quindi una versione nuova).
    store.put(photo(4, taken = T0 + 3 * MINUTE))
    store.put(edited(b))
    val report = sync.syncNow(SyncReason.CONTENT_CHANGE)

    assertThat(report.fullScan).isFalse()
    assertThat(store.queries.last { it.sinceGeneration != null }.sinceGeneration).isEqualTo(saved)
    assertThat(store.opens - opensAfterFirst).isEqualTo(2)
    assertThat(report.added).isEqualTo(1)
    assertThat(report.updated).isEqualTo(1)
    assertThat(mediaCount()).isEqualTo(4)
    assertThat(db.versionDao().forMedia(mediaAt(b)!!.value)).hasSize(2)
    assertThat(db.versionDao().forMedia(mediaAt(a)!!.value)).hasSize(1)
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.generation).isEqualTo(store.generation(PRIMARY))
  }

  @Test
  fun senzaCambiamentiNonSiInterrogaNulla() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val queries = store.queries.size
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(store.queries.size).isEqualTo(queries)
    assertThat(report.seen).isEqualTo(0)
    assertThat(report.completed).isTrue()
  }

  @Test
  fun deletedFilesAccelereLeAssenzeELaRiconciliazioneEDaVerita() = runBlocking<Unit> {
    val rows = (1L..5L).map { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)

    // Con deleted_files: l'incrementale lo vede subito.
    store.remove(PRIMARY, 1)
    val fast = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(fast.vanished).isEqualTo(1)
    assertThat(stateAt(rows[0])).isEqualTo(LocationState.ABSENT)
    assertThat(sync.stats.snapshot.value.deletedFilesAvailable).isTrue()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.lastReconcileAt).isNull()

    // Senza (estensione assente): la generazione e' salita senza righe, quindi la riconciliazione rapida confronta gli _ID.
    store.supportsDeletedFiles = false
    store.remove(PRIMARY, 2)
    val quick = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(quick.vanished).isEqualTo(1)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    assertThat(sync.stats.snapshot.value.deletedFilesAvailable).isFalse()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.lastReconcileAt).isEqualTo(now)

    // Non piu' spesso di ogni due minuti: la cancellazione successiva aspetta la manutenzione (o il prossimo giro utile).
    store.remove(PRIMARY, 3)
    assertThat(sync.syncNow(SyncReason.CONTENT_CHANGE).vanished).isEqualTo(0)
    assertThat(stateAt(rows[2])).isEqualTo(LocationState.PRESENT)
    now += 3 * MINUTE
    val daily = sync.syncNow(SyncReason.MAINTENANCE)
    assertThat(daily.vanished).isEqualTo(1)
    assertThat(stateAt(rows[2])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[3])).isEqualTo(LocationState.PRESENT)
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.lastReconcileAt).isEqualTo(now)
  }

  @Test
  fun deletedFilesVuotaPerLeAppComeSullEmulatoreNonNascondeLeCancellazioni() = runBlocking<Unit> {
    val rows = (1L..3L).map { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    store.deletedFilesEmptyForApps = true

    store.remove(PRIMARY, 2)
    val report = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(sync.stats.snapshot.value.deletedFilesAvailable).isTrue()
    assertThat(report.vanished).isEqualTo(1)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
    assertThat(stateAt(rows[0])).isEqualTo(LocationState.PRESENT)
  }

  @Test
  fun unaRigaCancellataERicreataConLoStessoIdNonDiventaAssente() = runBlocking<Unit> {
    val row = store.put(photo(1, taken = T0))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val id = mediaAt(row)!!

    // Svuotato e ripristinato con lo stesso _ID (URI stabili): deleted_files lo elenca ma la riga c'e'.
    val trashed = store.samsungTrash(PRIMARY, 1)
    store.samsungRestore(trashed, newId = 1)
    val report = sync.syncNow(SyncReason.CONTENT_CHANGE)

    assertThat(report.vanished).isEqualTo(0)
    assertThat(stateAt(row)).isEqualTo(LocationState.PRESENT)
    assertThat(mediaAt(row)).isEqualTo(id)
  }

  @Test
  fun leRigheInScritturaNonEntranoFinchéNonSonoPubblicate() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)

    val pending = store.putPending(photo(2, taken = T0 + MINUTE))
    val during = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(during.added).isEqualTo(0)
    assertThat(mediaAt(pending)).isNull()

    store.publish(PRIMARY, 2)
    val after = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(after.added).isEqualTo(1)
    assertThat(mediaAt(pending)).isNotNull()
  }

  @Test
  fun ilCestinoDiSistemaRendeAssenteEIlRipristinoRicollegaLoStessoMedia() = runBlocking<Unit> {
    val row = store.put(photo(1, taken = T0))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val id = mediaAt(row)!!

    store.trash(PRIMARY, 1)
    val trashed = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(trashed.vanished).isEqualTo(1)
    assertThat(stateAt(row)).isEqualTo(LocationState.ABSENT)
    assertThat(presenceOf(id)).isEqualTo("none")

    store.untrash(PRIMARY, 1)
    sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(stateAt(row)).isEqualTo(LocationState.PRESENT)
    assertThat(mediaAt(row)).isEqualTo(id)
    assertThat(mediaCount()).isEqualTo(1)
    assertThat(versionCount()).isEqualTo(1)
  }

  @Test
  fun generazioniTornateIndietroSenzaCambioDiVersioneDannoUnaScansioneCompleta() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    // Stessa versione, generazioni ripartite (un ripristino del database del MediaProvider).
    val cursor = catalog.mediaStoreCursor(PRIMARY)!!
    catalog.saveMediaStoreCursor(cursor.copy(generation = cursor.generation!! + 1_000))
    val report = sync.syncNow(SyncReason.FOREGROUND)
    assertThat(report.fullScan).isTrue()
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.generation).isEqualTo(store.generation(PRIMARY))
  }

  @Test
  fun suApi29SenzaGenerazioniOgniGiroEUnConfrontoCompleto() = runBlocking<Unit> {
    store.supportsGenerations = false
    val row = store.put(photo(1, taken = T0))
    store.put(photo(2, taken = T0 + MINUTE))
    val sync = newSync()
    assertThat(sync.syncNow(SyncReason.APP_START).fullScan).isTrue()
    store.remove(PRIMARY, 1)
    now += MINUTE
    val report = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(report.fullScan).isTrue()
    assertThat(stateAt(row)).isEqualTo(LocationState.ABSENT)
  }
}
