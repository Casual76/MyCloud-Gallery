package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.testing.ReferencePicker
import org.junit.Test

/**
 * La parita' con il riferimento (§5.2: "stessi elementi e stessa copertina salvo i casi a +-1e-4 dalle soglie") e il determinismo ("stessa
 * entrata = stessa scelta, stesso ordine"). Gli ingressi sono casuali e ripetibili dal seme; la scelta vera fa gli stessi conti del
 * riferimento nello stesso ordine, quindi qui ci si aspetta l'uguaglianza esatta, non solo entro la tolleranza.
 */
class PickParityTest {

  private val reference = ReferencePicker()
  private val picker = MemoryPhotoPicker()

  @Test
  fun stessiElementiStessaCopertinaStessiConteggiDelRiferimento() {
    var accepted = 0
    var withVectors = 0
    for (seed in 1L..700L) {
      val input = RandomPick.of(seed, size = 30 + (seed % 6).toInt() * 30)
      val spec = MemoryRules.spec(input.kind)
      val expected = reference.pick(input.candidates, spec, input.signals)
      val actual = picker.pick(input.candidates, spec, input.signals)
      assertThat(actual.members).isEqualTo(expected.members)
      assertThat(actual.cover).isEqualTo(expected.cover)
      assertThat(actual.dropped).isEqualTo(expected.dropped)
      if (expected.accepted) accepted++
      if (input.signals.vectors.isNotEmpty()) withVectors++
    }
    // La prova deve davvero scegliere (non solo rifiutare) e passare dai vettori.
    assertThat(accepted).isGreaterThan(350)
    assertThat(withVectors).isGreaterThan(250)
  }

  @Test
  fun stessaEntrataStessaScelta() {
    for (seed in 1L..80L) {
      val input = RandomPick.of(seed)
      val spec = MemoryRules.spec(input.kind)
      val first = picker.pick(input.candidates, spec, input.signals)
      for (shuffle in 1..4) {
        val again = picker.pick(input.candidates.shuffled(java.util.Random(seed * 10 + shuffle)), spec, input.signals)
        assertThat(again.members).isEqualTo(first.members)
        assertThat(again.cover).isEqualTo(first.cover)
        assertThat(again.dropped).isEqualTo(first.dropped)
      }
    }
  }

  @Test
  fun chiamateRipetuteDannoLoStessoRisultato() {
    val input = RandomPick.of(42)
    val spec = MemoryRules.spec(input.kind)
    val a = picker.pick(input.candidates, spec, input.signals)
    val other = RandomPick.of(43)
    picker.pick(other.candidates, MemoryRules.spec(other.kind), other.signals)
    val b = picker.pick(input.candidates, spec, input.signals)
    assertThat(b.members).isEqualTo(a.members)
    assertThat(b.cover).isEqualTo(a.cover)
  }
}
