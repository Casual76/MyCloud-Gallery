package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PickResult
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PixelProbe
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * I Nascosti (D31, E-H8, C27): un media nascosto non entra mai in un ricordo e non e' mai copertina, anche se e' il migliore di tutti e anche se
 * si nasconde un attimo prima della fine. Fallire chiusi: nel dubbio e' nascosto.
 */
class PickHiddenTest {

  private val picker = MemoryPhotoPicker()
  private val cells = Cells()

  private fun visible(mask: HiddenMask) = VisiblePhotoPicker(picker, mask)

  /** Un nascosto perfetto: preferito, modificato, in un album, con una persona, luce e nitidezza buone. */
  private fun perfect(minute: Int) = cells.cell(minute, flags = CellFlags.FAVORITE or CellFlags.EDITED)

  /** Proprieta': su ingressi casuali con nascosti perfetti sparsi, un nascosto non esce mai (ne' fra gli elementi ne' come copertina). */
  @Test
  fun unNascostoPerfettoNonEsceMai() = runTest {
    var accepted = 0
    for (seed in 1L..400L) {
      val base = RandomPick.of(seed)
      val random = Random(seed)
      val spec = MemoryRules.spec(base.kind)
      // Ai candidati veri si aggiungono nascosti perfetti, e si nascondono anche alcuni dei veri (a caso, i migliori compresi).
      val hiddenPerfect = List(2 + random.nextInt(6)) { perfect(random.nextInt(600)) }
      val hiddenReal = base.candidates.filter { random.nextInt(100) < 15 }.map { it.id }
      val hidden = (hiddenPerfect.map { it.id } + hiddenReal).toSet()
      val signals = PickSignals(
        vectors = base.signals.vectors,
        lookAlike = base.signals.lookAlike,
        pixels = base.signals.pixels + hiddenPerfect.associate { it.id to PixelProbe(900f, 0.5f) },
        albums = base.signals.albums + hiddenPerfect.map { it.id },
        people = base.signals.people,
        choices = base.signals.choices,
      )
      val result = visible { ids -> ids.filter { it in hidden }.toSet() }.pick(base.candidates + hiddenPerfect, spec, signals)
      assertThat(result.members.toSet().intersect(hidden)).isEmpty()
      assertThat(result.cover == null || result.cover !in hidden).isTrue()
      if (result.accepted) accepted++
    }
    assertThat(accepted).isGreaterThan(100)
  }

  /** Il nascosto non toglie il posto a nessuno: un suo gemello nascosto e migliore non fa uscire la foto visibile come "doppione". */
  @Test
  fun unNascostoNonFaUscireUnDoppione() = runTest {
    val photos = cells.spaced(8, from = 600, every = 20)
    val shown = cells.cell(0)
    val hiddenTwin = cells.cell(1, flags = CellFlags.FAVORITE or CellFlags.EDITED)
    val vector = SyntheticVectors.concept(1)
    val vectors = photos.withIndex().associate { (i, c) -> c.id to SyntheticVectors.concept(50L + i) } +
      mapOf(shown.id to vector, hiddenTwin.id to vector)
    val result = visible { ids -> ids.filter { it == hiddenTwin.id }.toSet() }
      .pick(photos + shown + hiddenTwin, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals(vectors = vectors))
    assertThat(result.members).contains(shown.id)
    assertThat(result.members).doesNotContain(hiddenTwin.id)
  }

  /** Un media che diventa nascosto dopo la scelta (E-MEM5, E-NT7): esce, si sceglie di nuovo e la copertina non e' lui. */
  @Test
  fun nascostoDellUltimoMomento() = runTest {
    val photos = cells.spaced(12, from = 600, every = 20)
    val star = perfect(300)
    var asked = 0
    val mask = HiddenMask { ids ->
      asked++
      // La prima richiesta (sui candidati) non nasconde niente; la seconda (sugli scelti) nasconde la stella.
      if (asked == 1) emptySet() else ids.filter { it == star.id }.toSet()
    }
    val result = visible(mask).pick(photos + star, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    assertThat(result.accepted).isTrue()
    assertThat(result.members).doesNotContain(star.id)
    assertThat(result.cover).isNotEqualTo(star.id)
    assertThat(asked).isEqualTo(3)
  }

  /** Se i nascosti si moltiplicano a ogni controllo e la scelta non si stabilizza, il ricordo non nasce (mai un nascosto "per stanchezza"). */
  @Test
  fun seNonSiStabilizzaNonNasce() = runTest {
    val photos = cells.spaced(30, from = 0, every = 20)
    var calls = 0
    val mask = HiddenMask { ids ->
      calls++
      if (calls == 1) emptySet() else ids.take(1).toSet()
    }
    val result = visible(mask).pick(photos, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    assertThat(result.accepted).isFalse()
    assertThat(result.members).isEmpty()
    assertThat(result.cover).isNull()
    assertThat(calls).isEqualTo(1 + VisiblePhotoPicker.MAX_ROUNDS)
  }

  /** Chi nasconde tutto: nessun ricordo. */
  @Test
  fun tuttoNascostoNessunRicordo() = runTest {
    val photos = cells.spaced(20, from = 0, every = 20)
    val result = visible { ids -> ids.toSet() }.pick(photos, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    assertThat(result.accepted).isFalse()
    assertThat(result.members).isEmpty()
  }

  /** Fallire chiusi (D22): se la maschera lancia un errore prima o dopo la scelta, il ricordo non nasce. */
  @Test
  fun seLaMascheraFallisceNonNasce() = runTest {
    val photos = cells.spaced(20, from = 0, every = 20)
    val spec = MemoryRules.spec(MemoryKind.ON_THIS_DAY)
    val always = visible { error("guasto di lettura") }.pick(photos, spec, PickSignals())
    assertThat(always.accepted).isFalse()
    assertThat(always.members).isEmpty()
    var calls = 0
    val later = visible { _ -> if (++calls == 1) emptySet() else throw IllegalStateException("guasto dopo la scelta") }.pick(photos, spec, PickSignals())
    assertThat(later.accepted).isFalse()
    assertThat(later.members).isEmpty()
    assertThat(later.cover).isNull()
  }

  /** Una cancellazione non e' un guasto: passa. */
  @Test
  fun laCancellazionePassa() = runTest {
    val photos = cells.spaced(20, from = 0, every = 20)
    var thrown: Throwable? = null
    try {
      visible { throw CancellationException("annullato") }.pick(photos, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    } catch (e: CancellationException) {
      thrown = e
    }
    assertThat(thrown).isInstanceOf(CancellationException::class.java)
  }

  /** Senza nascosti la maschera non cambia niente: stesso risultato della scelta nuda, e solo due richieste (candidati e scelti). */
  @Test
  fun senzaNascostiNonCambiaNiente() = runTest {
    var asked = 0
    val input = RandomPick.of(11, size = 150)
    val spec = MemoryRules.spec(input.kind)
    val plain = picker.pick(input.candidates, spec, input.signals)
    val guarded = visible { asked++; emptySet() }.pick(input.candidates, spec, input.signals)
    assertThat(guarded.members).isEqualTo(plain.members)
    assertThat(guarded.cover).isEqualTo(plain.cover)
    assertThat(guarded.dropped).isEqualTo(plain.dropped)
    assertThat(asked).isEqualTo(if (plain.accepted) 2 else 1)
  }

  /** A scelta rifiutata non si chiede altro alla maschera; e la maschera non vede gli id che i candidati non hanno. */
  @Test
  fun laMascheraVedeSoloGliIdDeiCandidatiEDegliScelti() = runTest {
    val photos = cells.spaced(20, from = 0, every = 20)
    val seen = ArrayList<Collection<MediaId>>()
    val result = visible { ids -> seen += ids.toList(); emptySet() }.pick(photos, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    assertThat(seen.first()).containsExactlyElementsIn(photos.map { it.id })
    assertThat(seen.last()).containsExactlyElementsIn(result.members)
  }

  /** Un nascosto fra i candidati non e' un candidato per nessuna porta di scelta: la scelta nuda non lo vede nemmeno. */
  @Test
  fun ilNascostoNonRaggiungeLaScelta() = runTest {
    val photos = cells.spaced(10, from = 0, every = 20)
    val star = perfect(5)
    val received = ArrayList<List<CandidateCell>>()
    val spy = PhotoPicker { candidates, spec, signals -> received += candidates; picker.pick(candidates, spec, signals) }
    VisiblePhotoPicker(spy, HiddenMask { ids -> ids.filter { it == star.id }.toSet() })
      .pick(photos + star, MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    assertThat(received).isNotEmpty()
    assertThat(received.flatten().map { it.id }).doesNotContain(star.id)
  }

  /** Il risultato rifiutato porta solo conteggi, come ogni altro. */
  @Test
  fun ilRifiutoPortaSoloConteggi() = runTest {
    val result: PickResult = visible { ids -> ids.toSet() }.pick(cells.spaced(20), MemoryRules.spec(MemoryKind.ON_THIS_DAY), PickSignals())
    assertThat(result.dropped.values.all { it > 0 }).isTrue()
  }
}
