package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.PauseReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncStatus
import dev.pampa.mycloudgallery.core.model.LocationState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I permessi: accesso parziale (E-P1), permessi ritirati (E-P15), posizione dei media negata (E-P21), telefono non
 * sbloccato (E-P2, E-P10, E-P13). In nessun caso un permesso che manca diventa un'assenza.
 */
@RunWith(AndroidJUnit4::class)
class AccessTest : PhoneSyncTestBase() {

  @Test
  fun accessoParzialeMostraLaSelezioneENonSegnaMaiAssenze() = runBlocking<Unit> {
    val rows = (1L..3L).map { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    assertThat(sync.partialSelection.value).isNull()

    // "Foto selezionate": il provider restituisce solo la riga 1.
    setAccess(MediaAccess.PARTIAL)
    store.setSelection(PRIMARY, setOf(1L))
    val partial = sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(partial.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    assertThat(sync.partialSelection.value).containsExactly(mediaAt(rows[0]))
    // Il cursore torna a "scansione completa da fare": solo una scansione intera puo' dedurre assenze.
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.generation).isNull()

    // Anche una cancellazione vera, finche' l'accesso e' parziale, non si deduce.
    store.remove(PRIMARY, 3)
    assertThat(sync.syncNow(SyncReason.FOREGROUND).vanished).isEqualTo(0)
    assertThat(stateAt(rows[2])).isEqualTo(LocationState.PRESENT)

    // "Consenti tutto": scansione completa, e solo ora la riga 3 risulta assente.
    setAccess(MediaAccess.FULL)
    store.setSelection(PRIMARY, null)
    now += MINUTE
    val full = sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(full.fullScan).isTrue()
    assertThat(full.vanished).isEqualTo(1)
    assertThat(stateAt(rows[0])).isEqualTo(LocationState.PRESENT)
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.PRESENT)
    assertThat(stateAt(rows[2])).isEqualTo(LocationState.ABSENT)
    assertThat(sync.partialSelection.value).isNull()
  }

  @Test
  fun unaScansioneCompletaInterrottaDallAccessoParzialeNonDeduceAssenze() = runBlocking<Unit> {
    val rows = (1L..6L).map { store.put(photo(it, taken = T0 + it * MINUTE)) }
    newSync().syncNow(SyncReason.APP_START)

    // Versione nuova: scansione completa. Al secondo lotto l'utente passa a "foto selezionate".
    store.bumpVersion(PRIMARY, "v2")
    setAccess(MediaAccess.FULL)
    val sync = newSync()
    store.failQueryAt = store.queries.size + 2
    sync.syncNow(SyncReason.APP_START)
    store.failQueryAt = null
    setAccess(MediaAccess.PARTIAL)
    store.setSelection(PRIMARY, setOf(6L))
    sync.syncNow(SyncReason.PERMISSION_CHANGED)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    // Tornato l'accesso completo: la scansione riparte da capo (epoca nuova) e nessuno sparisce.
    setAccess(MediaAccess.FULL)
    store.setSelection(PRIMARY, null)
    now += 10 * MINUTE
    val report = sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(report.vanished).isEqualTo(0)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
  }

  @Test
  fun permessiRitiratiFermanoTuttoSenzaAssenze() = runBlocking<Unit> {
    val rows = (1L..3L).map { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val sync = newSync()
    sync.syncNow(SyncReason.APP_START)
    val queries = store.queries.size

    setAccess(MediaAccess.NONE)
    store.accessRevoked = true
    val report = sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(report.completed).isFalse()
    assertThat(sync.status.value).isEqualTo(SyncStatus.Paused(PauseReason.NO_PERMISSION))
    assertThat(sync.partialSelection.value).isEmpty()
    assertThat(store.queries.size).isEqualTo(queries)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }

    setAccess(MediaAccess.FULL)
    store.accessRevoked = false
    val back = sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(back.completed).isTrue()
    assertThat(back.vanished).isEqualTo(0)
    assertThat(sync.status.value).isInstanceOf(SyncStatus.Idle::class.java)
  }

  @Test
  fun permessiRitiratiFraLaQueryELAperturaFermanoIlGiroSenzaPerdereRighe() = runBlocking<Unit> {
    val rows = (1L..3L).map { store.put(photo(it, taken = T0 + it * MINUTE)) }
    newSync().syncNow(SyncReason.APP_START)
    val saved = catalog.mediaStoreCursor(PRIMARY)!!.generation
    val newPhoto = store.put(photo(4, taken = T0 + 9 * MINUTE))
    val newVideo = store.put(video(5, taken = T0 + 8 * MINUTE))

    // Il controllo dei permessi diceva ancora si', ma aprire i file e' gia' vietato.
    store.openRevoked = true
    val sync = newSync()
    val stopped = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(stopped.completed).isFalse()
    assertThat(stopped.vanished).isEqualTo(0)
    assertThat(sync.stats.snapshot.value.lastFailure).isEqualTo(SyncFailure.PERMISSION_LOST)
    rows.forEach { assertThat(stateAt(it)).isEqualTo(LocationState.PRESENT) }
    // La generazione non e' andata avanti: al prossimo giro le righe nuove ci sono ancora.
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.generation).isEqualTo(saved)

    store.openRevoked = false
    val next = sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(next.added).isEqualTo(2)
    assertThat(mediaAt(newPhoto)).isNotNull()
    assertThat(mediaAt(newVideo)).isNotNull()
  }

  @Test
  fun posizioneNegataFotoNuoveInAttesaENessunaImprontaOscurata() = runBlocking<Unit> {
    val known = store.put(photo(1, taken = T0))
    newSync().syncNow(SyncReason.APP_START)
    val knownId = mediaAt(known)!!

    // Permesso della posizione ritirato: una foto e un video nuovi.
    setAccess(MediaAccess.FULL, mediaLocation = false)
    val newPhoto = store.put(photo(2, taken = T0 + MINUTE))
    val newVideo = store.put(video(3, taken = T0 + 2 * MINUTE))
    val sync = newSync()
    val denied = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(denied.pendingMediaLocation).isEqualTo(1)
    assertThat(denied.added).isEqualTo(1)
    assertThat(mediaAt(newVideo)).isNotNull()
    assertThat(mediaAt(newPhoto)).isNull()
    assertThat(sync.status.value).isEqualTo(SyncStatus.Paused(PauseReason.MEDIA_LOCATION_DENIED))
    // D9: nessuno ha nemmeno provato ad aprire una foto senza il permesso.
    assertThat(store.redactedOpenAttempts).isEqualTo(0)
    assertThat(stateAt(known)).isEqualTo(LocationState.PRESENT)

    // Dopo un riavvio il conteggio c'e' ancora: la generazione salvata non ha superato la foto in attesa.
    val restarted = newSync().syncNow(SyncReason.APP_START)
    assertThat(restarted.pendingMediaLocation).isEqualTo(1)
    assertThat(store.redactedOpenAttempts).isEqualTo(0)

    // Il permesso torna: la foto si elabora; nessuna versione nuova per cio' che era gia' noto.
    setAccess(MediaAccess.FULL, mediaLocation = true)
    val granted = newSync().syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(granted.pendingMediaLocation).isEqualTo(0)
    assertThat(granted.added).isEqualTo(1)
    assertThat(mediaAt(newPhoto)).isNotNull()
    assertThat(db.versionDao().forMedia(knownId.value)).hasSize(1)
    assertThat(versionCount()).isEqualTo(3)
    assertThat(catalog.mediaStoreCursor(PRIMARY)!!.generation).isEqualTo(store.generation(PRIMARY))
  }

  @Test
  fun unaFotoNotaModificataSenzaPermessoResta() = runBlocking<Unit> {
    val known = store.put(photo(1, taken = T0))
    store.put(photo(2, taken = T0 + MINUTE))
    newSync().syncNow(SyncReason.APP_START)
    val id = mediaAt(known)!!

    // Modificata mentre la posizione e' negata, poi una scansione completa (versione nuova del MediaStore).
    setAccess(MediaAccess.FULL, mediaLocation = false)
    store.put(edited(known))
    store.bumpVersion(PRIMARY, "v2")
    now += MINUTE
    val report = newSync().syncNow(SyncReason.APP_START)
    assertThat(report.fullScan).isTrue()
    assertThat(report.vanished).isEqualTo(0)
    assertThat(report.pendingMediaLocation).isEqualTo(1)
    // Non letta (niente byte oscurati) ma viva: mai un'assenza per una foto che c'e'.
    assertThat(stateAt(known)).isEqualTo(LocationState.PRESENT)
    assertThat(store.redactedOpenAttempts).isEqualTo(0)

    // Con il permesso: la modifica vera diventa una versione dello stesso media.
    setAccess(MediaAccess.FULL, mediaLocation = true)
    newSync().syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(mediaAt(known)).isEqualTo(id)
    assertThat(db.versionDao().forMedia(id.value)).hasSize(2)
  }

  @Test
  fun primaDelPrimoSbloccoNonSiLeggeNulla() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    val sync = newSync(storageReady = { false })
    val report = sync.syncNow(SyncReason.WORKER)
    assertThat(report.completed).isFalse()
    assertThat(sync.status.value).isEqualTo(SyncStatus.Paused(PauseReason.STORAGE_LOCKED))
    assertThat(store.queries).isEmpty()
  }
  @Test
  fun laSelezioneAvvisaIlProprietarioPrimaDiTornare() = runBlocking<Unit> {
    val row = store.put(photo(1, taken = T0))
    setAccess(MediaAccess.PARTIAL)
    store.setSelection(PRIMARY, setOf(1L))
    var calls = 0
    val sync = newSync(selectionChanged = { calls++ })
    sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(calls).isEqualTo(1)
    assertThat(sync.partialSelection.value).containsExactly(mediaAt(row))
    setAccess(MediaAccess.NONE)
    sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(calls).isEqualTo(2)
    assertThat(sync.partialSelection.value).isEmpty()
  }

}
