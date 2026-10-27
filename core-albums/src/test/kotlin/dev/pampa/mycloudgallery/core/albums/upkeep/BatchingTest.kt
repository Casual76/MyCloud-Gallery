package dev.pampa.mycloudgallery.core.albums.upkeep

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * AS-3 e VG-1: i due strumenti che tengono al passo gli album. Il raccoglitore non perde cio' che arriva **mentre** un giro lavora (prima il giro
 * successivo partiva solo alla novita' dopo, che poteva non venire mai); il "fermo da tanto" riparte a ogni segno e non annulla l'azione gia' partita.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatchingTest {

  @Test
  fun ciCheArrivaDuranteUnGiroFaPartireUnGiroDopo() = runTest {
    val delivered = ArrayList<List<Int>>()
    val inFirstRound = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val batcher = ChangeBatcher<Int>(backgroundScope, 5.seconds) { ids ->
      delivered += ids
      if (delivered.size == 1) {
        inFirstRound.complete(Unit)
        release.await()
      }
    }

    batcher.add(listOf(1, 2))
    advanceTimeBy(5.seconds + 1.milliseconds)
    runCurrent()
    inFirstRound.await()
    assertThat(delivered).containsExactly(listOf(1, 2))

    // Arriva una novita' mentre il primo giro lavora: prima restava ferma senza un giro.
    batcher.add(listOf(3))
    release.complete(Unit)
    advanceTimeBy(5.seconds + 1.milliseconds)
    runCurrent()

    assertThat(delivered).containsExactly(listOf(1, 2), listOf(3)).inOrder()
    assertThat(batcher.waiting).isEqualTo(0)
  }

  @Test
  fun lePiuNovitaDiUnaStessaFinestraVannoInUnGiroSolo() = runTest {
    val delivered = ArrayList<List<Int>>()
    val batcher = ChangeBatcher<Int>(backgroundScope, 5.seconds) { delivered += it }

    batcher.add(listOf(1))
    advanceTimeBy(2.seconds)
    batcher.add(listOf(2, 1))
    advanceTimeBy(2.seconds)
    batcher.add(listOf(3))
    advanceTimeBy(2.seconds)
    runCurrent()

    assertThat(delivered).containsExactly(listOf(1, 2, 3))
  }

  @Test
  fun unGiroCheLanciaNonFermaIGiriDopo() = runTest {
    var calls = 0
    val seen = ArrayList<Int>()
    val batcher = ChangeBatcher<Int>(backgroundScope, 1.seconds) { ids ->
      calls++
      if (calls == 1) error("guasto")
      seen += ids
    }

    batcher.add(listOf(1))
    advanceTimeBy(1.seconds + 1.milliseconds)
    runCurrent()
    batcher.add(listOf(2))
    advanceTimeBy(1.seconds + 1.milliseconds)
    runCurrent()

    assertThat(seen).containsExactly(2)
  }

  @Test
  fun ilFermoDaTantoRipartePerOgniSegnoENonAnnullaLAzioneGiaPartita() = runTest {
    var runs = 0
    val inAction = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    val debouncer = Debouncer(backgroundScope, 60.seconds) {
      runs++
      inAction.complete(Unit)
      finish.await()
    }

    debouncer.poke()
    advanceTimeBy(50.seconds)
    debouncer.poke()
    advanceTimeBy(50.seconds)
    // Cento secondi dopo il primo segno ma solo cinquanta dopo l'ultimo: ancora ferma.
    assertThat(runs).isEqualTo(0)

    advanceTimeBy(11.seconds)
    runCurrent()
    inAction.await()
    assertThat(runs).isEqualTo(1)

    // Un segno mentre l'azione lavora ne prepara un'altra, e non annulla quella in corso.
    debouncer.poke()
    finish.complete(Unit)
    advanceTimeBy(61.seconds)
    runCurrent()
    assertThat(runs).isEqualTo(2)
  }
}
