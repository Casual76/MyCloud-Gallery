package dev.pampa.mycloudgallery.gallery

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.TimelineIndexBuilder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * XL (nastro delle foto in analisi): le regole della barriera, una per una e senza orologio. Le istantanee sono vuote con un `seq` scelto dalla prova: conta solo il numero.
 * - **XS5-1**: dopo un avviso di Android il nastro riapre solo quando una rilettura **partita dopo l'avviso** e finita bene e l'istantanea pubblicata ha raggiunto il `change_seq` di
 *   quella rilettura: un'istantanea vecchia (di `seq` minore) che arriva dopo l'avviso non riapre niente.
 * - **XS5-2**: una rilettura finita bene **senza cambiamenti** riapre lo stesso (l'istantanea e' gia' al `change_seq`).
 * - **XS5-3**: una transizione («Nascondi») chiude all'inizio e riapre alla fine, comunque finisca; un contatore, non un flag.
 */
class AnalysisVisibilityBoundaryTest {

  private fun snap(seq: Long): TimelineSnapshot = TimelineIndexBuilder(16).build(seq)

  /** La barriera con permessi completi e l'istantanea che la prova sceglie ([current]); [notified] conta i richiami ai listener. */
  private class Rig(initial: TimelineSnapshot) {
    @Volatile var current: TimelineSnapshot? = initial
    @Volatile var authorized = true
    val notified = AtomicInteger()
    val boundary = AnalysisVisibilityBoundary({ authorized }, { current }).also { it.register { notified.incrementAndGet() } }
  }

  @Test
  fun senzaAvvisiNeTransizioniIlNastroPuoRisolvere() {
    val rig = Rig(snap(10))
    assertThat(rig.boundary.canResolve()).isTrue()
    assertThat(rig.boundary.noticeOrder()).isEqualTo(0L)
  }

  @Test
  fun unAvvisoChiudeSubitoENumeraGliAvvisi() {
    val rig = Rig(snap(10))
    rig.boundary.contentChanged()
    assertThat(rig.notified.get()).isEqualTo(1)
    assertThat(rig.boundary.canResolve()).isFalse()
    assertThat(rig.boundary.noticeOrder()).isEqualTo(1L)
    rig.boundary.contentChanged()
    assertThat(rig.boundary.noticeOrder()).isEqualTo(2L)
  }

  @Test
  fun unIstantaneaNuovaMaVecchiaNonRiapreDopoUnAvviso() {
    val rig = Rig(snap(10))
    rig.boundary.contentChanged()
    // S1: un'istantanea in viaggio, precedente alla rilettura: e' un oggetto diverso da quello catturato all'avviso, ma non e' il risultato della rilettura.
    rig.current = snap(11)
    assertThat(rig.boundary.canResolve()).isFalse()
    // La rilettura (partita dopo l'avviso) finisce e dichiara il change_seq 12: l'istantanea e' ancora a 11, il nastro resta chiuso.
    rig.boundary.rereadFinished(rig.boundary.noticeOrder(), catalogSeq = 12)
    assertThat(rig.boundary.canResolve()).isFalse()
    // L'istantanea che tiene conto della rilettura (12) riapre.
    rig.current = snap(12)
    assertThat(rig.boundary.canResolve()).isTrue()
    // E non richiude da sola: una istantanea ancora piu' nuova, o l'ordine che non cambia, non cambiano niente.
    rig.current = snap(15)
    assertThat(rig.boundary.canResolve()).isTrue()
  }

  @Test
  fun unaRiletturaPartitaPrimaDellAvvisoNonLoCopre() {
    val rig = Rig(snap(10))
    val startedBefore = rig.boundary.noticeOrder()
    rig.boundary.contentChanged()
    // Il giro era partito prima dell'avviso: ha letto il telefono prima del cambiamento, qualunque istantanea abbia raggiunto.
    rig.boundary.rereadFinished(startedBefore, catalogSeq = 10)
    assertThat(rig.boundary.canResolve()).isFalse()
    // Il giro dopo (partito dopo l'avviso) lo copre.
    rig.boundary.rereadFinished(rig.boundary.noticeOrder(), catalogSeq = 10)
    assertThat(rig.boundary.canResolve()).isTrue()
  }

  @Test
  fun unaRiletturaFinitaSenzaCambiamentiRiapreSubito() {
    val rig = Rig(snap(10))
    rig.boundary.contentChanged()
    val before = rig.notified.get()
    // Nessun cambiamento nel catalogo: l'istantanea e' gia' al change_seq della rilettura. Riapre e chi ascolta ricalcola (non c'e' un'istantanea nuova che lo faccia).
    rig.boundary.rereadFinished(rig.boundary.noticeOrder(), catalogSeq = 10)
    assertThat(rig.boundary.canResolve()).isTrue()
    assertThat(rig.notified.get()).isEqualTo(before + 1)
  }

  @Test
  fun unNuovoAvvisoDopoLaRiletturaRichiude() {
    val rig = Rig(snap(10))
    rig.boundary.contentChanged()
    rig.boundary.rereadFinished(rig.boundary.noticeOrder(), catalogSeq = 10)
    assertThat(rig.boundary.canResolve()).isTrue()
    rig.boundary.contentChanged()
    assertThat(rig.boundary.canResolve()).isFalse()
    // Il giro dell'avviso vecchio non basta; serve uno partito dopo il nuovo.
    rig.boundary.rereadFinished(1L, catalogSeq = 10)
    assertThat(rig.boundary.canResolve()).isFalse()
    rig.boundary.rereadFinished(2L, catalogSeq = 10)
    assertThat(rig.boundary.canResolve()).isTrue()
  }

  @Test
  fun unaRiletturaDichiarataSenzaAvvisoNonFaNulla() {
    val rig = Rig(snap(10))
    rig.boundary.rereadFinished(0L, catalogSeq = 99)
    assertThat(rig.notified.get()).isEqualTo(0)
    assertThat(rig.boundary.canResolve()).isTrue()
    // Nemmeno richiude se l'istantanea e' piu' indietro del change_seq dichiarato: senza avviso non c'e' niente da aspettare.
    assertThat(rig.current!!.seq).isLessThan(99L)
  }

  @Test
  fun ilNastroNonRiapreSenzaPermessiComunqueVadaLaRilettura() {
    val rig = Rig(snap(10))
    rig.boundary.contentChanged()
    rig.boundary.rereadFinished(rig.boundary.noticeOrder(), catalogSeq = 10)
    rig.authorized = false
    assertThat(rig.boundary.canResolve()).isFalse()
    rig.authorized = true
    assertThat(rig.boundary.canResolve()).isTrue()
    rig.boundary.retire()
    assertThat(rig.boundary.canResolve()).isFalse()
  }

  @Test
  fun senzaIstantaneaIlNastroRestaChiusoDopoUnAvviso() {
    val rig = Rig(snap(10))
    rig.boundary.contentChanged()
    rig.boundary.rereadFinished(rig.boundary.noticeOrder(), catalogSeq = 10)
    rig.current = null
    assertThat(rig.boundary.canResolve()).isFalse()
    rig.current = snap(10)
    assertThat(rig.boundary.canResolve()).isTrue()
  }

  // ---- XS5-3: le transizioni ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun unaTransizioneChiudeAllInizioERiapreAllaFine() = runBlocking<Unit> {
    val rig = Rig(snap(10))
    val seenInside = ArrayList<Pair<Boolean, Int>>()
    val result = rig.boundary.transition {
      // Dentro il blocco (dove la foto esce dalla Libreria e si scrive il catalogo) il nastro e' gia' chiuso e chi ascolta e' gia' stato avvisato.
      seenInside += rig.boundary.canResolve() to rig.notified.get()
      42
    }
    assertThat(result).isEqualTo(42)
    assertThat(seenInside).containsExactly(false to 1)
    // Alla fine riapre e chi ascolta ricalcola.
    assertThat(rig.boundary.canResolve()).isTrue()
    assertThat(rig.notified.get()).isEqualTo(2)
  }

  @Test
  fun unaTransizioneFallitaRiapreComunqueEPropagaLErrore() = runBlocking<Unit> {
    val rig = Rig(snap(10))
    val failure = try {
      rig.boundary.transition<Unit> { throw IllegalStateException("scrittura fallita") }
      null
    } catch (e: IllegalStateException) {
      e
    }
    assertThat(failure).hasMessageThat().isEqualTo("scrittura fallita")
    assertThat(rig.boundary.canResolve()).isTrue()
  }

  @Test
  fun unaTransizioneAnnullataRiapreComunque() = runBlocking<Unit> {
    val rig = Rig(snap(10))
    val entered = CompletableDeferred<Unit>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      rig.boundary.transition { entered.complete(Unit); CompletableDeferred<Unit>().await() }
    }
    entered.await()
    assertThat(rig.boundary.canResolve()).isFalse()
    job.cancel()
    job.join()
    assertThat(rig.boundary.canResolve()).isTrue()
  }

  @Test
  fun piuTransizioniInsiemeRiapronoSoloQuandoLUltimaEFinita() = runBlocking<Unit> {
    val rig = Rig(snap(10))
    val firstIn = CompletableDeferred<Unit>()
    val secondIn = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val releaseSecond = CompletableDeferred<Unit>()
    val first = launch(start = CoroutineStart.UNDISPATCHED) { rig.boundary.transition { firstIn.complete(Unit); releaseFirst.await() } }
    val second = launch(start = CoroutineStart.UNDISPATCHED) { rig.boundary.transition { secondIn.complete(Unit); releaseSecond.await() } }
    firstIn.await()
    secondIn.await()
    assertThat(rig.boundary.canResolve()).isFalse()
    releaseFirst.complete(Unit)
    first.join()
    // La prima e' finita, la seconda no: resta chiuso (un flag avrebbe riaperto).
    assertThat(rig.boundary.canResolve()).isFalse()
    releaseSecond.complete(Unit)
    second.join()
    assertThat(rig.boundary.canResolve()).isTrue()
  }

  @Test
  fun laFineDiUnaTransizioneNonRiapreUnAvvisoAncoraInAttesa() = runBlocking<Unit> {
    val rig = Rig(snap(10))
    rig.boundary.contentChanged()
    rig.boundary.transition { }
    // La transizione e' finita ma la rilettura dell'avviso no: il nastro resta chiuso.
    assertThat(rig.boundary.canResolve()).isFalse()
    rig.boundary.rereadFinished(rig.boundary.noticeOrder(), catalogSeq = 10)
    assertThat(rig.boundary.canResolve()).isTrue()
  }
}
