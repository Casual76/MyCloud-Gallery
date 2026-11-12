package dev.pampa.mycloudgallery.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * XS2-5 (Pacchetto XF, giro 2): la lettura dello stato di un media e' **legata alla sua versione**. Si legge la chiave corrente, poi cio' che costa (indice,
 * segni di "non supportato") e infine si **rilegge la chiave**: se la versione e' cambiata mentre si leggeva, il risultato e' di una versione di prima e si
 * rifiuta. Le prove usano una **barriera** vera: la lettura dell'indice resta ferma mentre il test cambia la versione.
 *
 * Mutazione eseguita: togliere la rilettura della chiave (`if (currentKey(id) == key)`): cade `unaLetturaChePassaSuUnaVersioneCambiataSiRifiuta`, perche' il
 * "pronto" della versione vecchia verrebbe attaccato alla nuova.
 */
class ViewerAnalysisResolverTest {

  private fun key(n: Int) = ContentKey(n.toString(16).padStart(64, '0'))

  private val id = MediaId(UUID(0L, 7L).toString())

  @Test
  fun unaVersioneStabileSiLeggeUnaVolta() = runBlocking<Unit> {
    val resolver = ViewerAnalysisResolver(currentKey = { key(1) }, isDone = { true }, isUnsupported = { _, _ -> false })
    val read = resolver.resolve(id)
    assertThat(read.current).isEqualTo(key(1))
    assertThat(read.ready).isTrue()
    assertThat(read.unsupported).isFalse()
  }

  @Test
  fun unCatalogoChePerOraNonLaSaDiceNonSiSa() = runBlocking<Unit> {
    val resolver = ViewerAnalysisResolver(currentKey = { null }, isDone = { error("non va letta") }, isUnsupported = { _, _ -> error("non va letta") })
    val read = resolver.resolve(id)
    assertThat(read.current).isNull()
    assertThat(read.ready).isFalse()
  }

  /**
   * La versione cambia **mentre** si legge l'indice: il "pronto" della versione vecchia non vale per la nuova. Si rilegge per la nuova (che non e' pronta).
   */
  @Test
  fun unaLetturaChePassaSuUnaVersioneCambiataSiRifiuta() = runBlocking<Unit> {
    var current: ContentKey? = key(1)
    val gate = CompletableDeferred<Unit>()
    val resolver = ViewerAnalysisResolver(
      currentKey = { current },
      isDone = { k ->
        if (k == key(1)) {
          gate.await()
          true // la versione vecchia e' pronta...
        } else {
          false // ...la nuova no
        }
      },
      isUnsupported = { _, _ -> false },
    )
    val reading = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve(id) }
    // La lettura e' ferma sulla barriera con la chiave 1: il media cambia versione.
    current = key(2)
    gate.complete(Unit)
    val read = reading.await()
    assertThat(read.current).isEqualTo(key(2))
    assertThat(read.ready).isFalse()
  }

  /** Una versione che continua a cambiare a ogni lettura non si stabilizza: "non si sa", mai lo stato di una versione di prima. */
  @Test
  fun unaVersioneCheNonSiStabilizzaDiceNonSiSa() = runBlocking<Unit> {
    var n = 0
    val resolver = ViewerAnalysisResolver(currentKey = { key(++n) }, isDone = { true }, isUnsupported = { _, _ -> false })
    val read = resolver.resolve(id)
    assertThat(read.current).isNull()
    assertThat(read.ready).isFalse()
    assertThat(read.unsupported).isFalse()
  }
}
