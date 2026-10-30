package dev.pampa.mycloudgallery.core.memories.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryVectors
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryVectors
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto della porta dei vettori (Fase 9, P7; `docs/fase9-progetto.md` D7, §5.2): gira sul fake e, quando P13 li lega, sugli
 * adattatori veri (`SearchData.vectors`) in Robolectric e strumentati. Chi la estende dice solo come si costruisce una porta che conosce i
 * vettori dati ([known]).
 */
abstract class MemoryVectorsContract {

  /** Una porta che conosce esattamente i vettori di [known] (di lunghezza 1) e nessun altro. */
  protected abstract fun port(known: Map<MediaId, FloatArray>): MemoryVectors

  private fun id(n: Long) = MediaId(UUID(5L, n).toString())

  private val known: Map<MediaId, FloatArray> = (1L..6L).associate { id(it) to SyntheticVectors.concept(it) }

  /** Risponde solo per gli id chiesti e conosciuti: un id sconosciuto manca, non e' un errore. */
  @Test
  fun rispondeSoloPerGliIdChiestiEConosciuti() = runTest {
    val port = port(known)
    val asked = listOf(id(1), id(2), id(99))
    val result = port.vectors(asked)
    assertThat(result.keys).containsExactly(id(1), id(2))
    assertThat(result.getValue(id(1))).isEqualTo(known.getValue(id(1)))
  }

  @Test
  fun nessunIdNessunVettore() = runTest {
    assertThat(port(known).vectors(emptyList())).isEmpty()
    assertThat(port(emptyMap()).vectors(listOf(id(1)))).isEmpty()
  }

  /** Un id ripetuto nella richiesta non rompe niente e non raddoppia niente. */
  @Test
  fun unIdRipetuto() = runTest {
    val result = port(known).vectors(listOf(id(3), id(3), id(3)))
    assertThat(result.keys).containsExactly(id(3))
  }

  /** Tutti i vettori di una risposta stanno nello stesso spazio (stessa lunghezza) e hanno solo numeri finiti. */
  @Test
  fun stessoSpazioENumeriFiniti() = runTest {
    val result = port(known).vectors(known.keys)
    assertThat(result).hasSize(known.size)
    assertThat(result.values.map { it.size }.distinct()).hasSize(1)
    for (vector in result.values) {
      assertThat(vector).isNotEmpty()
      assertThat(vector.all { it.isFinite() }).isTrue()
    }
  }

  /** Le risposte sono copie: scriverci sopra non cambia la risposta di dopo (un adattatore legge da un database, non da una cache condivisa). */
  @Test
  fun leRisposteSonoCopie() = runTest {
    val port = port(known)
    val first = port.vectors(listOf(id(1))).getValue(id(1))
    val original = first.copyOf()
    first.fill(0f)
    assertThat(port.vectors(listOf(id(1))).getValue(id(1))).isEqualTo(original)
  }

  /** La stessa richiesta due volte da' la stessa risposta. */
  @Test
  fun stessaRichiestaStessaRisposta() = runTest {
    val port = port(known)
    val a = port.vectors(known.keys)
    val b = port.vectors(known.keys)
    assertThat(a.keys).isEqualTo(b.keys)
    for ((id, vector) in a) assertThat(b.getValue(id)).isEqualTo(vector)
  }
}

/** La suite sul fake (§5.2). */
class FakeMemoryVectorsContractTest : MemoryVectorsContract() {
  override fun port(known: Map<MediaId, FloatArray>): MemoryVectors = FakeMemoryVectors(known.toMutableMap())

  @Test
  fun ilFakeContaLeRichieste() = runTest {
    val fake = FakeMemoryVectors(mutableMapOf(MediaId(UUID(5L, 1L).toString()) to SyntheticVectors.concept(1)))
    fake.vectors(listOf(MediaId(UUID(5L, 1L).toString()), MediaId(UUID(5L, 2L).toString())))
    assertThat(fake.calls).isEqualTo(1)
    assertThat(fake.asked).isEqualTo(2)
  }
}

/** Il null-object risponde "niente" a tutto (nessun vettore: i doppioni si cercano senza, E-MEM16). */
class NoMemoryVectorsTest {
  @Test
  fun nonHaVettori() = runTest {
    assertThat(NoMemoryVectors.vectors(listOf(MediaId(UUID(5L, 1L).toString())))).isEmpty()
  }
}
