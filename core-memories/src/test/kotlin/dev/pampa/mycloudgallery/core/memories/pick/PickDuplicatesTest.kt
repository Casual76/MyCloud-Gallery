package dev.pampa.mycloudgallery.core.memories.pick

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.LookAlike
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MemoryRules
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickWeights
import dev.pampa.mycloudgallery.core.memories.testing.SyntheticVectors
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import org.junit.Test

/** I doppioni (D7, E-MEM16): con i vettori (coseno >= 0,92), con la firma visiva e, in mancanza di entrambi, con la finestra di 3 minuti. */
class PickDuplicatesTest {

  private val picker = MemoryPhotoPicker()
  private val cells = Cells()

  private fun pick(candidates: List<CandidateCell>, signals: PickSignals) =
    picker.pick(candidates, MemoryRules.spec(MemoryKind.ON_THIS_DAY), signals)

  /** Otto foto a 20 minuti l'una dall'altra, ciascuna di un concetto diverso: nessun doppione fra loro. */
  private fun distinct(): Pair<List<CandidateCell>, Map<MediaId, FloatArray>> {
    val photos = cells.spaced(8, from = 600, every = 20)
    return photos to photos.withIndex().associate { (i, c) -> c.id to SyntheticVectors.concept(100L + i) }
  }

  // --- Vettori ---

  @Test
  fun ilCosenoDiSogliaDaUnaParteEDallAltra() {
    val (photos, vectors) = distinct()
    val base = vectors.getValue(photos[0].id)
    for ((cosine, isDuplicate) in listOf(0.99 to true, 0.94 to true, 0.90 to false, 0.5 to false)) {
      val twin = cells.cell(1_000)
      val withTwin = vectors + (twin.id to SyntheticVectors.withCosine(base, cosine, 5))
      val result = pick(photos + twin, PickSignals(vectors = withTwin))
      assertThat(result.members.contains(twin.id)).isEqualTo(!isDuplicate)
      assertThat(result.dropped.getOrDefault(DropReason.DUPLICATE, 0)).isEqualTo(if (isDuplicate) 1 else 0)
      // La soglia e' quella di D7: la prova non la sposta senza accorgersene.
      assertThat(PickWeights.DUPLICATE_COSINE).isEqualTo(0.92)
    }
  }

  /** Resta quella col punteggio piu' alto, anche se e' la piu' recente. */
  @Test
  fun restaLaMigliore() {
    val (photos, vectors) = distinct()
    val twin = cells.cell(900, flags = CellFlags.FAVORITE)
    val withTwin = vectors + (twin.id to vectors.getValue(photos[0].id))
    val result = pick(photos + twin, PickSignals(vectors = withTwin))
    assertThat(result.members).contains(twin.id)
    assertThat(result.members).doesNotContain(photos[0].id)
  }

  /** A parita' di punteggio resta lo scatto piu' vecchio. */
  @Test
  fun aParitaRestaLoScattoPiuVecchio() {
    val (photos, vectors) = distinct()
    val later = cells.cell(1_000)
    val withTwin = vectors + (later.id to vectors.getValue(photos[0].id))
    val result = pick(photos + later, PickSignals(vectors = withTwin))
    assertThat(result.members).contains(photos[0].id)
    assertThat(result.members).doesNotContain(later.id)
  }

  /** Si confronta con le foto TENUTE: A~B e B~C ma A non ~C, allora C resta (B e' uscita e non conta piu'). */
  @Test
  fun ilConfrontoESuLeTenute() {
    val (photos, vectors) = distinct()
    val a = SyntheticVectors.concept(500)
    val b = SyntheticVectors.withCosine(a, 0.93, 1)
    val c = SyntheticVectors.withCosine(b, 0.93, 2)
    assertThat(PhotoScore.cosine(a, b)).isAtLeast(0.92)
    assertThat(PhotoScore.cosine(b, c)).isAtLeast(0.92)
    assertThat(PhotoScore.cosine(a, c)).isLessThan(0.92)
    val ca = cells.cell(1_000, flags = CellFlags.FAVORITE or CellFlags.EDITED)
    val cb = cells.cell(1_020, flags = CellFlags.FAVORITE)
    val cc = cells.cell(1_040)
    val result = pick(photos + listOf(ca, cb, cc), PickSignals(vectors = vectors + mapOf(ca.id to a, cb.id to b, cc.id to c)))
    assertThat(result.members).containsAtLeast(ca.id, cc.id)
    assertThat(result.members).doesNotContain(cb.id)
  }

  /** Solo i media con un vettore si confrontano coi vettori: chi non ne ha ricade sulla firma o sulla finestra. */
  @Test
  fun unoSenzaVettoreRicadeSullaFinestra() {
    val (photos, vectors) = distinct()
    val near = cells.cell(601)
    val result = pick(photos + near, PickSignals(vectors = vectors))
    assertThat(result.members).doesNotContain(near.id)
    assertThat(result.dropped[DropReason.DUPLICATE]).isEqualTo(1)
  }

  // --- Firma visiva ---

  @Test
  fun laFirmaVisivaDecideSeRisponde() {
    val photos = cells.spaced(8, from = 600, every = 20)
    val a = cells.cell(0)
    val b = cells.cell(300) // lontana: la finestra non la farebbe mai doppione
    val similar = LookAlike { x, y -> setOf(x, y) == setOf(a.id, b.id) }
    val result = pick(photos + a + b, PickSignals(lookAlike = similar))
    assertThat(result.members.count { it == a.id || it == b.id }).isEqualTo(1)
    assertThat(result.dropped[DropReason.DUPLICATE]).isEqualTo(1)
  }

  /** "No" della firma vale piu' della finestra: due scatti a 1 minuto, ma la firma dice che non sono lo stesso, restano entrambi. */
  @Test
  fun laFirmaDiceDiNoPiuDellaFinestra() {
    val photos = cells.spaced(8, from = 600, every = 20)
    val a = cells.cell(0)
    val b = cells.cell(1)
    val result = pick(photos + a + b, PickSignals(lookAlike = LookAlike { _, _ -> false }))
    assertThat(result.members).containsAtLeast(a.id, b.id)
    assertThat(result.dropped).isEmpty()
  }

  /** "Non lo so" della firma (null): vale la finestra. */
  @Test
  fun laFirmaCheNonSaLasciaLaFinestra() {
    val photos = cells.spaced(8, from = 600, every = 20)
    val a = cells.cell(0)
    val b = cells.cell(1)
    val c = cells.cell(100)
    val result = pick(photos + a + b + c, PickSignals(lookAlike = LookAlike { _, _ -> null }))
    assertThat(result.members.count { it == a.id || it == b.id }).isEqualTo(1)
    assertThat(result.members).contains(c.id)
  }

  /** I vettori vincono sulla firma quando ci sono tutti e due. */
  @Test
  fun iVettoriVincono() {
    val (photos, vectors) = distinct()
    val a = cells.cell(0)
    val b = cells.cell(1)
    val withBoth = vectors + mapOf(a.id to SyntheticVectors.concept(900), b.id to SyntheticVectors.concept(901))
    val result = pick(photos + a + b, PickSignals(vectors = withBoth, lookAlike = LookAlike { _, _ -> true }))
    assertThat(result.members).containsAtLeast(a.id, b.id)
  }

  // --- Finestra di 3 minuti ---

  @Test
  fun laFinestraDiTreMinutiAiDueLati() {
    val photos = cells.spaced(8, from = 600, every = 20)
    val base = cells.cell(0)
    val exactly = base.copy(id = cells.cell(0).id, timeMs = base.timeMs + PickWeights.DUPLICATE_WINDOW_MS)
    val justOver = base.copy(id = cells.cell(0).id, timeMs = base.timeMs + PickWeights.DUPLICATE_WINDOW_MS + 1)
    val onTheLine = pick(photos + base + exactly, PickSignals())
    assertThat(onTheLine.members.count { it == base.id || it == exactly.id }).isEqualTo(1)
    val over = pick(photos + base + justOver, PickSignals())
    assertThat(over.members).containsAtLeast(base.id, justOver.id)
  }

  /** Nella finestra resta la migliore, non la prima. */
  @Test
  fun nellaFinestraRestaLaMigliore() {
    val photos = cells.spaced(8, from = 600, every = 20)
    val plain = cells.cell(0)
    val favorite = cells.cell(2, flags = CellFlags.FAVORITE)
    val result = pick(photos + plain + favorite, PickSignals())
    assertThat(result.members).contains(favorite.id)
    assertThat(result.members).doesNotContain(plain.id)
  }

  // --- Proprieta' ---

  /** Dopo la scelta nessuna coppia di elementi e' un doppione secondo la stessa regola (vettori, firma o finestra). */
  @Test
  fun proprietaNessunDoppioneFraGliScelti() {
    for (seed in 1L..200L) {
      val input = RandomPick.of(seed)
      val result = picker.pick(input.candidates, MemoryRules.spec(input.kind), input.signals)
      val byId = input.candidates.associateBy { it.id }
      val chosen = result.members.map { byId.getValue(it) }
      for (i in chosen.indices) for (j in chosen.indices) {
        if (i >= j) continue
        val a = chosen[i]
        val b = chosen[j]
        val va = input.signals.vectors[a.id]
        val vb = input.signals.vectors[b.id]
        val duplicate = when {
          va != null && vb != null -> PhotoScore.cosine(va, vb) >= PickWeights.DUPLICATE_COSINE
          else -> input.signals.lookAlike?.similar(a.id, b.id) ?: (kotlin.math.abs(a.timeMs - b.timeMs) <= PickWeights.DUPLICATE_WINDOW_MS)
        }
        assertThat(duplicate).isFalse()
      }
    }
  }
}
