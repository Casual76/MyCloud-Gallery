package dev.pampa.mycloudgallery.backup

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import org.junit.Test

/**
 * **BK-5** (verifica funzionale): l'avanzamento del primo backup attraverso i lotti da 60 secondi. Il motore pubblica i numeri di **un lotto**
 * (`done` riparte da zero, `total` e' quello che trova aperto, `etaMillis` e' sempre nullo): "1.240 di 6.658 · circa 1 h 20 min" diventava
 * "37 di 980" e la barra della notifica tornava indietro. Il conto vero sta sul catalogo (i media ancora in coda) ed e' cumulativo.
 */
class RunProgressTest {

  private val minute = 60_000L

  /** Lo stato di un lotto, come lo pubblica il motore: contatori del lotto, totale dei soli elementi aperti. */
  private fun batch(done: Int, total: Int = 980, bytesDone: Long = 0L, bytesTotal: Long = 0L) = BackupStatus.Running(
    kind = BackupKind.PHOTOS, done = done, total = total, bytesDone = bytesDone, bytesTotal = bytesTotal, etaMillis = null,
    trigger = BackupTrigger.FIRST_BACKUP,
  )

  @Test
  fun ilLottoRipartitoDaZeroNonFaTornareIndietroIlConto() {
    val progress = RunProgress()
    // Primo lotto: 6.658 media da proteggere, il motore ne ha fatti 37.
    val first = progress.reshape(batch(done = 37), queued = 6_621, nowMs = 0)
    assertThat(first.done).isEqualTo(37)
    assertThat(first.total).isEqualTo(6_658)

    // Un minuto dopo: un lotto nuovo (il motore dice 5 di 980) ma il catalogo ha 6.500 in coda: sono 158 fatti in tutto.
    val second = progress.reshape(batch(done = 5), queued = 6_500, nowMs = minute)
    assertThat(second.done).isEqualTo(158)
    assertThat(second.total).isEqualTo(6_658)

    // I fatti non scendono mai, il totale non cambia: la barra va solo avanti.
    var last = second
    var queued = 6_500
    for (step in 2..30) {
      queued -= 40
      val next = progress.reshape(batch(done = 1), queued = queued, nowMs = step * minute)
      assertThat(next.done).isAtLeast(last.done)
      assertThat(next.total).isEqualTo(6_658)
      last = next
    }
  }

  /**
   * Il catalogo mette un media "al sicuro" prima che il motore lo conti fra i fatti (il motore pubblica ancora le immagini all'indice e chiude
   * il diario). Se il primo sguardo cade li' in mezzo, i media in volo non sono ne' nei fatti del motore ne' nella coda del catalogo: il totale
   * di una campagna da 24 media partiva da 23 (e la barra finiva "23 di 23"). Il giro che parte dice quanti ne ha in coda il catalogo, e i fatti
   * gia' compiuti sono la differenza con la coda di adesso: la stessa fonte per entrambi i numeri.
   */
  @Test
  fun ilCatalogoInAnticipoSulMotore_NonFaPerdereUnMediaNelTotale() {
    val progress = RunProgress()
    progress.roundStarting(queued = 24)
    // Il motore dice 16 fatti, ma il catalogo ne ha gia' 17 al sicuro: il diciassettesimo e' ancora nel suo ultimo passo. In coda ne restano 7.
    val first = progress.reshape(batch(done = 16, total = 24), queued = 7, nowMs = 0)
    assertThat(first.total).isEqualTo(24)
    assertThat(first.done).isEqualTo(17)

    // Poi il motore recupera: il suo conto non conta piu', e la barra arriva a 24 di 24 senza mai tornare indietro.
    var last = first
    for (queued in 6 downTo 0) {
      val next = progress.reshape(batch(done = 24 - queued - 1, total = 24), queued = queued, nowMs = (7 - queued) * 1_000L)
      assertThat(next.total).isEqualTo(24)
      assertThat(next.done).isAtLeast(last.done)
      last = next
    }
    assertThat(last.done).isEqualTo(24)
  }

  @Test
  fun ilMotoreInAnticipoSulCatalogo_NonAllungaIlTotale() {
    val progress = RunProgress()
    progress.roundStarting(queued = 24)
    // Il motore ha contato 5 fatti e il catalogo, letto un attimo prima, ne conosce 4: i fatti sono quelli del catalogo, il totale resta 24.
    val first = progress.reshape(batch(done = 5, total = 24), queued = 20, nowMs = 0)
    assertThat(first.done).isEqualTo(4)
    assertThat(first.total).isEqualTo(24)
  }

  @Test
  fun lAnnuncioDelGiroServeSoloAlPrimoSguardoDiUnaCampagna() {
    val progress = RunProgress()
    progress.roundStarting(queued = 100)
    progress.reshape(batch(done = 0), queued = 100, nowMs = 0)
    progress.reshape(batch(done = 10), queued = 90, nowMs = 10_000)
    // Un lotto nuovo della stessa campagna annuncia la sua coda di adesso: la campagna non riparte, il totale non cambia.
    progress.roundStarting(queued = 90)
    val next = progress.reshape(batch(done = 3, total = 90), queued = 87, nowMs = 20_000)
    assertThat(next.done).isEqualTo(13)
    assertThat(next.total).isEqualTo(100)
    // E l'annuncio non resta per un'altra campagna: finita questa, una nuova parte dal conto del motore (senza un annuncio suo).
    progress.roundStarting(queued = 50)
    progress.idle(queued = 0)
    val fresh = progress.reshape(batch(done = 1, total = 3), queued = 2, nowMs = 30 * minute)
    assertThat(fresh.done).isEqualTo(1)
    assertThat(fresh.total).isEqualTo(3)
  }

  @Test
  fun ilTempoCheMancaNonCeFinoAlSecondoMedia_PoiViene_DalRitmoOsservato() {
    val progress = RunProgress()
    val first = progress.reshape(batch(done = 0), queued = 1_000, nowMs = 0)
    assertThat(first.etaMillis).isNull()
    // Un media fatto in dieci secondi: ancora una speranza, non un tempo.
    val one = progress.reshape(batch(done = 1), queued = 999, nowMs = 10_000)
    assertThat(one.etaMillis).isNull()
    // Due media in venti secondi: dieci secondi a media, 998 ancora da fare.
    val two = progress.reshape(batch(done = 2), queued = 998, nowMs = 20_000)
    assertThat(two.etaMillis).isEqualTo(10_000L * 998)
    // Il ritmo si aggiorna.
    val four = progress.reshape(batch(done = 4), queued = 996, nowMs = 30_000)
    assertThat(four.etaMillis).isEqualTo(30_000L * 996 / 4)
  }

  @Test
  fun ilTempoFermoFraDueTappeNonSiConta() {
    val progress = RunProgress()
    progress.reshape(batch(done = 0), queued = 100, nowMs = 0)
    progress.reshape(batch(done = 2), queued = 98, nowMs = 10_000)
    // Il backup si ferma (le regole, la notte): la campagna resta, il ritmo no.
    progress.idle(queued = 98)
    // Sei ore dopo riparte: quel tempo non e' lavoro.
    val resumed = progress.reshape(batch(done = 0), queued = 98, nowMs = 6 * 60 * minute)
    assertThat(resumed.done).isEqualTo(2)
    assertThat(resumed.total).isEqualTo(100)
    val later = progress.reshape(batch(done = 2), queued = 96, nowMs = 6 * 60 * minute + 20_000)
    assertThat(later.done).isEqualTo(4)
    // 2 media fatti nei primi 10 s, 2 nei 20 s di dopo: 30 s per 4 media, 96 ancora da fare.
    assertThat(later.etaMillis).isEqualTo(30_000L * 96 / 4)
  }

  @Test
  fun leFotoNuoveMentreSiLavoraAllungranoIlTotale_NonFannoTornareIndietroIFatti() {
    val progress = RunProgress()
    progress.reshape(batch(done = 0), queued = 100, nowMs = 0)
    val before = progress.reshape(batch(done = 10), queued = 90, nowMs = 10_000)
    // Arrivano 5 foto nuove: in coda salgono, i fatti no.
    val after = progress.reshape(batch(done = 11), queued = 94, nowMs = 20_000)
    assertThat(after.done).isEqualTo(before.done)
    assertThat(after.total).isEqualTo(before.total + 4)
  }

  @Test
  fun finitaLaCampagnaLAvanzamentoRipartePulito() {
    val progress = RunProgress()
    progress.reshape(batch(done = 0), queued = 50, nowMs = 0)
    progress.reshape(batch(done = 50), queued = 0, nowMs = 60_000)
    progress.idle(queued = 0)
    // Una nuova campagna: tre foto, non "53 di 53".
    val next = progress.reshape(batch(done = 0), queued = 3, nowMs = 10 * 60 * minute)
    assertThat(next.done).isEqualTo(0)
    assertThat(next.total).isEqualTo(3)
  }

  @Test
  fun laMorteDelProcessoNonAzzeraLAvanzamento() {
    val progress = RunProgress()
    progress.reshape(batch(done = 0), queued = 6_658, nowMs = 0)
    progress.reshape(batch(done = 30), queued = 6_628, nowMs = 60_000)
    val saved = checkNotNull(progress.snapshot())

    // Un processo nuovo: il file dice dov'era; il primo lotto nuovo riprende da li'.
    val reborn = RunProgress()
    reborn.restore(saved)
    val resumed = reborn.reshape(batch(done = 0), queued = 6_628, nowMs = 0)
    assertThat(resumed.done).isEqualTo(30)
    assertThat(resumed.total).isEqualTo(6_658)
  }

  @Test
  fun unFileIllegibileVale_Niente_ESenzaCambiamentiNonSiScrive() {
    val progress = RunProgress()
    for (garbage in listOf(null, "", "boh", "1;2;3", "a;b;c;d;e;f", "1;2;3;4;5;-9")) {
      progress.restore(garbage)
      val first = progress.reshape(batch(done = 2), queued = 8, nowMs = 0)
      assertThat(first.done).isEqualTo(2)
      assertThat(first.total).isEqualTo(10)
      progress.idle(queued = 0)
    }
    val quiet = RunProgress()
    quiet.idle(queued = 0)
    assertThat(quiet.snapshot()).isNull()
  }

  @Test
  fun iByteSiSommanoFraILotti_EIlTotaleSiStimaDalRitmoDeiByte() {
    val progress = RunProgress()
    progress.reshape(batch(done = 0, bytesDone = 0, bytesTotal = 1_000), queued = 100, nowMs = 0)
    val a = progress.reshape(batch(done = 10, bytesDone = 1_000, bytesTotal = 1_000), queued = 90, nowMs = 10_000)
    assertThat(a.bytesDone).isEqualTo(1_000L)
    // Un lotto nuovo: i byte del lotto ripartono da zero, i totali no.
    val b = progress.reshape(batch(done = 5, bytesDone = 500, bytesTotal = 1_000), queued = 85, nowMs = 70_000)
    assertThat(b.bytesDone).isEqualTo(1_500L)
    // 15 media fatti per 1.500 byte: 100 byte l'uno, 100 media in tutto.
    assertThat(b.bytesTotal).isEqualTo(10_000L)
  }
}
