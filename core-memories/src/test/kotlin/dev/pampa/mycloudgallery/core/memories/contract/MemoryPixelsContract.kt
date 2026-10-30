package dev.pampa.mycloudgallery.core.memories.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPixels
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryPixels
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto della porta delle misure dei pixel (Fase 9, P7; D6, E-MEM15, §5.2): gira sul fake e, quando P13 li lega, sulle
 * miniature locali di `core-imaging` in Robolectric e strumentati. Chi la estende dice solo come si costruisce una porta che sa misurare i
 * media di [known].
 */
abstract class MemoryPixelsContract {

  protected abstract fun port(known: Map<MediaId, PixelProbe>): MemoryPixels

  private fun id(n: Long) = MediaId(UUID(6L, n).toString())

  private val known: Map<MediaId, PixelProbe> = (1L..8L).associate { id(it) to PixelProbe(sharpness = it * 40f, luma = it / 10f) }

  /** Risponde solo per gli id chiesti e che sa misurare: gli altri mancano (media solo sul NAS, E-MEM15), mai un numero inventato. */
  @Test
  fun rispondeSoloPerGliIdChiestiEMisurabili() = runTest {
    val result = port(known).probe(listOf(id(1), id(2), id(99)), 10.seconds)
    assertThat(result.keys).containsExactly(id(1), id(2))
    assertThat(result.getValue(id(2))).isEqualTo(known.getValue(id(2)))
  }

  @Test
  fun nessunIdNessunaMisura() = runTest {
    assertThat(port(known).probe(emptyList(), 10.seconds)).isEmpty()
    assertThat(port(emptyMap()).probe(listOf(id(1)), 10.seconds)).isEmpty()
  }

  /** Ogni misura e' nei limiti di `PixelProbe` (luce 0..1, nitidezza non negativa): il tipo lo impone, la porta non lo aggira. */
  @Test
  fun misureNeiLimiti() = runTest {
    for (probe in port(known).probe(known.keys, 10.seconds).values) {
      assertThat(probe.sharpness).isAtLeast(0f)
      assertThat(probe.luma in 0f..1f).isTrue()
    }
  }

  /** La stessa richiesta due volte da' la stessa misura (la cache locale non cambia i numeri). */
  @Test
  fun stessaRichiestaStessaMisura() = runTest {
    val port = port(known)
    assertThat(port.probe(known.keys, 10.seconds)).isEqualTo(port.probe(known.keys, 10.seconds))
  }

  /** Il budget limita il lavoro e non inventa: con meno budget la risposta e' un sottoinsieme, con gli stessi numeri. */
  @Test
  fun ilBudgetNonInventaMisure() = runTest {
    val port = port(known)
    val full = port.probe(known.keys, 10.seconds)
    val none = port.probe(known.keys, Duration.ZERO)
    for ((id, probe) in none) assertThat(full[id]).isEqualTo(probe)
    assertThat(none.size).isAtMost(full.size)
  }
}

/** La suite sul fake (§5.2). */
class FakeMemoryPixelsContractTest : MemoryPixelsContract() {
  override fun port(known: Map<MediaId, PixelProbe>): MemoryPixels = FakeMemoryPixels(known.toMutableMap())

  private fun id(n: Long) = MediaId(UUID(6L, n).toString())

  /** Con un costo per misura il fake si ferma quando il budget finisce: le prime misure restano, le altre no. */
  @Test
  fun ilFakeSiFermaQuandoIlBudgetFinisce() = runTest {
    val probes = (1L..5L).associate { id(it) to PixelProbe(200f, 0.5f) }.toMutableMap()
    val fake = FakeMemoryPixels(probes, costPerId = 10.milliseconds)
    val result = fake.probe(probes.keys, 25.milliseconds)
    assertThat(result.keys).containsExactly(id(1), id(2)).inOrder()
    assertThat(fake.probe(probes.keys, Duration.ZERO)).isEmpty()
    assertThat(fake.calls).isEqualTo(2)
    assertThat(fake.asked).isEqualTo(10)
  }

  /** Un id sconosciuto non costa budget: il fake non "misura" quello che non c'e'. */
  @Test
  fun unIdSconosciutoNonCostaBudget() = runTest {
    val probes = mutableMapOf(id(1) to PixelProbe(200f, 0.5f), id(2) to PixelProbe(200f, 0.5f))
    val fake = FakeMemoryPixels(probes, costPerId = 10.milliseconds)
    assertThat(fake.probe(listOf(id(90), id(91), id(1), id(2)), 20.milliseconds).keys).containsExactly(id(1), id(2))
  }
}

/** Il null-object non misura niente: nessuna penalita' di nitidezza e la luce viene dal `ThumbHash` (E-MEM15). */
class NoMemoryPixelsTest {
  @Test
  fun nonMisuraNiente() = runTest {
    assertThat(NoMemoryPixels.probe(listOf(MediaId(UUID(6L, 1L).toString())), 5.seconds)).isEmpty()
  }
}
