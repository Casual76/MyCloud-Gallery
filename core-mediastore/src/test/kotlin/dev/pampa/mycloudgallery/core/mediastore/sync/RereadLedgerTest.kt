package dev.pampa.mycloudgallery.core.mediastore.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.RereadLedger
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.model.LocationState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * XL-1, il lato della rilettura del telefono: dichiara a chi aspetta un avviso di Android **solo** un giro **finito bene con l'accesso completo**, con il
 * numero d'ordine degli avvisi letto **all'inizio** del giro (prima di toccare il MediaStore) e il `change_seq` del catalogo letto **alla fine**. Un giro
 * senza cambiamenti dichiara lo stesso (XS5-2): e' il caso piu' comune.
 */
@RunWith(AndroidJUnit4::class)
class RereadLedgerTest : PhoneSyncTestBase() {

  /** Il registro di prova: [order] e' il numero d'ordine che [noticeOrder] da' (e fa salire dopo ogni lettura se [bumpAfterRead]); [declared] le dichiarazioni. */
  private class Ledger(var order: Long = 0L, private val bumpAfterRead: Boolean = false, private val failing: Boolean = false) : RereadLedger {
    val declared = ArrayList<Pair<Long, Long>>()
    var reads = 0

    override fun noticeOrder(): Long {
      reads++
      val value = order
      if (bumpAfterRead) order++
      return value
    }

    override fun rereadFinished(order: Long, catalogSeq: Long) {
      if (failing) throw IllegalStateException("registro rotto")
      declared += order to catalogSeq
    }
  }

  @Test
  fun unGiroFinitoBeneDichiaraLOrdineDiInizioEIlChangeSeqDiFine() = runBlocking<Unit> {
    (1L..3L).forEach { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val ledger = Ledger(order = 3)
    val report = newSync(rereads = ledger).syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(report.completed).isTrue()
    // Un solo giro, un solo numero letto, e il `change_seq` e' quello del catalogo **dopo** le scritture del giro.
    assertThat(ledger.reads).isEqualTo(1)
    assertThat(catalog.changeSeq()).isGreaterThan(0L)
    assertThat(ledger.declared).containsExactly(3L to catalog.changeSeq())
  }

  @Test
  fun ilNumeroSiLeggeAllInizioNonAllaFine() = runBlocking<Unit> {
    store.put(photo(1, taken = T0))
    // Ogni lettura fa salire il numero: un avviso arrivato **durante** il giro non e' coperto dal giro, che dichiara il numero con cui e' partito.
    val ledger = Ledger(order = 5, bumpAfterRead = true)
    newSync(rereads = ledger).syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(ledger.order).isEqualTo(6L)
    assertThat(ledger.declared.map { it.first }).containsExactly(5L)
  }

  @Test
  fun unGiroFinitoSenzaCambiamentiDichiaraLoStessoConLoStessoChangeSeq() = runBlocking<Unit> {
    (1L..2L).forEach { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val ledger = Ledger(order = 1)
    val sync = newSync(rereads = ledger)
    sync.syncNow(SyncReason.APP_START)
    val seq = catalog.changeSeq()
    ledger.order = 2
    // Un avviso ridondante: la generazione non e' cambiata, il catalogo non si tocca, e il giro dichiara lo stesso.
    val report = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(report.completed).isTrue()
    assertThat(report.added + report.updated + report.vanished).isEqualTo(0)
    assertThat(catalog.changeSeq()).isEqualTo(seq)
    assertThat(ledger.declared).containsExactly(1L to seq, 2L to seq).inOrder()
  }

  @Test
  fun unGiroCheVedeUnaFotoToltaDichiaraDopoLaScritturaDellAssenza() = runBlocking<Unit> {
    val rows = (1L..3L).map { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val ledger = Ledger(order = 1)
    val sync = newSync(rereads = ledger)
    sync.syncNow(SyncReason.APP_START)
    val before = catalog.changeSeq()
    store.remove(PRIMARY, 2)
    ledger.order = 2
    val report = sync.syncNow(SyncReason.CONTENT_CHANGE)
    assertThat(report.vanished).isEqualTo(1)
    // Il change_seq dichiarato **include** la scrittura dell'assenza: l'istantanea che lo raggiunge non ha piu' la foto.
    val seq = ledger.declared.last().second
    assertThat(seq).isGreaterThan(before)
    assertThat(seq).isEqualTo(catalog.changeSeq())
    assertThat(stateAt(rows[1])).isEqualTo(LocationState.ABSENT)
  }

  @Test
  fun unGiroFallitoOInterrottoNonDichiara() = runBlocking<Unit> {
    (1L..6L).forEach { store.put(photo(it, taken = T0 + it * MINUTE)) }
    newSync().syncNow(SyncReason.APP_START)
    // Versione nuova: scansione completa; il secondo lotto fallisce (errore del MediaStore): il giro non e' completo.
    store.bumpVersion(PRIMARY, "v2")
    val ledger = Ledger(order = 1)
    val sync = newSync(rereads = ledger)
    store.failQueryAt = store.queries.size + 2
    val failed = sync.syncNow(SyncReason.CONTENT_CHANGE)
    store.failQueryAt = null
    assertThat(failed.completed).isFalse()
    assertThat(ledger.declared).isEmpty()
    // Il giro dopo, riuscito, dichiara.
    now += MINUTE
    assertThat(sync.syncNow(SyncReason.CONTENT_CHANGE).completed).isTrue()
    assertThat(ledger.declared.map { it.first }).containsExactly(1L)
  }

  @Test
  fun unGiroSenzaAccessoCompletoNonDichiara() = runBlocking<Unit> {
    (1L..2L).forEach { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val ledger = Ledger(order = 1)
    val sync = newSync(rereads = ledger)
    // Accesso parziale: il giro rilegge solo la selezione e non puo' dedurre assenze: non copre un avviso di rimozioni.
    setAccess(MediaAccess.PARTIAL)
    store.setSelection(PRIMARY, setOf(1L))
    assertThat(sync.syncNow(SyncReason.PERMISSION_CHANGED).completed).isTrue()
    assertThat(ledger.declared).isEmpty()
    // Nessun accesso: pausa, niente da dichiarare.
    setAccess(MediaAccess.NONE)
    store.accessRevoked = true
    sync.syncNow(SyncReason.PERMISSION_CHANGED)
    assertThat(ledger.declared).isEmpty()
    // Accesso completo: il giro (scansione intera) dichiara.
    setAccess(MediaAccess.FULL)
    store.accessRevoked = false
    store.setSelection(PRIMARY, null)
    now += MINUTE
    assertThat(sync.syncNow(SyncReason.PERMISSION_CHANGED).completed).isTrue()
    assertThat(ledger.declared.map { it.first }).containsExactly(1L)
  }

  @Test
  fun unRegistroCheLanciaNonCambiaIlGiro() = runBlocking<Unit> {
    (1L..2L).forEach { store.put(photo(it, taken = T0 + it * MINUTE)) }
    val sync = newSync(rereads = Ledger(failing = true))
    val report = sync.syncNow(SyncReason.APP_START)
    assertThat(report.completed).isTrue()
    assertThat(report.added).isEqualTo(2)
    assertThat(mediaCount()).isEqualTo(2)
  }
}
