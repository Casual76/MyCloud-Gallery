package dev.pampa.mycloudgallery.core.ai.compute

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.ai.contract.AiScope
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Revisione A della Fase 6, il calcolo: R5 (un risultato calcolato sull'anteprima di un altro dispositivo non diventa "proprio" e non si
 * ripubblica in un'altra share), IX-6 (le voci di un lotto nell'outbox insieme: M3 una volta per lotto), IX-7 (un errore passeggero del
 * motore non segna il media "non supportato" per sempre).
 */
class RevisioneACalcoloTest {
  @get:Rule val tmp = TemporaryFolder()

  private val forever = Duration.INFINITE

  /** Risultati e corpi che ricordano la provenienza, come l'indice locale vero (`LocalAiIndex.Derivation`). */
  private class OriginResults : AiResults, AiOriginResults, AiLocalBodiesFor {
    val vectors = HashMap<ContentKey, MutableMap<AiInputOrigin, VecBody>>()
    val texts = HashMap<ContentKey, MutableMap<AiInputOrigin, OcrBody>>()

    override suspend fun saveVectors(key: ContentKey, body: VecBody) = saveVectors(key, body, AiInputOrigin.Own)

    override suspend fun saveText(key: ContentKey, body: OcrBody) = saveText(key, body, AiInputOrigin.Own)

    override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> = savedFor(key, recipe, null)

    override suspend fun saveVectors(key: ContentKey, body: VecBody, from: AiInputOrigin) {
      vectors.getOrPut(key) { HashMap() }[from] = body
    }

    override suspend fun saveText(key: ContentKey, body: OcrBody, from: AiInputOrigin) {
      texts.getOrPut(key) { HashMap() }[from] = body
    }

    override suspend fun savedFor(key: ContentKey, recipe: Int, share: String?): Set<AiClass> = buildSet {
      if (vectors[key].orEmpty().any { (o, b) -> o.validFor(share) && b.recipe >= recipe }) add(AiClass.VEC)
      if (texts[key].orEmpty().any { (o, b) -> o.validFor(share) && b.recipe >= recipe }) add(AiClass.OCR)
    }

    override suspend fun vectors(key: ContentKey): VecBody? = vectorsFor(key, null)

    override suspend fun text(key: ContentKey): OcrBody? = textFor(key, null)

    override suspend fun vectorsFor(key: ContentKey, share: String?): VecBody? = vectors[key].orEmpty().entries.firstOrNull { it.key.validFor(share) }?.value

    override suspend fun textFor(key: ContentKey, share: String?): OcrBody? = texts[key].orEmpty().entries.firstOrNull { it.key.validFor(share) }?.value
  }

  /**
   * R5 (scenario della revisione): la stessa foto nell'area comune e nell'area privata di Anna; nell'area comune l'anteprima l'ha preparata
   * un altro dispositivo (Bruno, magari compromesso). Il calcolo dell'area comune si fa su quella anteprima e vale li'; per l'area privata
   * quell'anteprima non vale: niente si ripubblica firmato da Anna, il media aspetta un ingresso suo (anteprima della privata o telefono).
   */
  @Test
  fun r5_unAnteprimaDiUnAltroNonDiventaPropria() = runTest {
    val f = TestFamily()
    val key = f.nas.add(TestFamily.COMMON, concepts = "documento")
    f.nas.of(TestFamily.COMMON).previewBy[key to SegmentClass.ANALYSIS] = "telefono-bruno"
    f.nas.add(TestFamily.ANNA_PRIVATE, preview = false, key = key)
    val anna = SimDevice(f.nas, "telefono-anna", "anna", listOf(TestFamily.ANNA_PRIVATE, TestFamily.COMMON), buffer = DiskAiBuffer(tmp.newFolder("buffer")))
    val results = OriginResults()
    anna.resultsOverride = results
    anna.bodiesOverride = results
    anna.restart()

    anna.engine.run(AiScope.Share(TestFamily.COMMON), forever)
    assertThat(results.vectors[key]!!.keys).containsExactly(AiInputOrigin.Preview(TestFamily.COMMON, "telefono-bruno"))
    assertThat(f.published(TestFamily.COMMON, anna.id)).containsExactly(key)

    val privata = anna.engine.run(AiScope.Share(TestFamily.ANNA_PRIVATE), forever)
    assertThat(anna.publisher.queued(TestFamily.ANNA_PRIVATE)).isEmpty()
    assertThat(f.published(TestFamily.ANNA_PRIVATE, anna.id)).isEmpty()
    assertThat(privata.waitingForPreparation).isEqualTo(1)

    // Con l'anteprima giusta nella privata (preparata da un dispositivo di Anna) il media si calcola per la privata.
    f.nas.of(TestFamily.ANNA_PRIVATE).previews[key to SegmentClass.ANALYSIS] = SimBytes.preview(dev.pampa.mycloudgallery.core.model.MediaKind.PHOTO, "foto vera")
    f.nas.of(TestFamily.ANNA_PRIVATE).previewBy[key to SegmentClass.ANALYSIS] = "tablet-anna"
    anna.engine.run(AiScope.Share(TestFamily.ANNA_PRIVATE), forever)
    assertThat(f.published(TestFamily.ANNA_PRIVATE, anna.id)).containsExactly(key)
    assertThat(results.vectors[key]!!.keys).contains(AiInputOrigin.Preview(TestFamily.ANNA_PRIVATE, "tablet-anna"))
  }

  /** R5: un buffer che ricorda la provenienza dimentica le anteprime di un dispositivo revocato (e quelle di un dispositivo non noto). */
  @Test
  fun r5_ilBufferDimenticaLeAnteprimeDiUnRevocato() = runTest {
    val buffer = DiskAiBuffer(tmp.newFolder("b"))
    val a = ContentKey("a".repeat(64))
    val b = ContentKey("b".repeat(64))
    val c = ContentKey("c".repeat(64))
    buffer.putFrom(a, SegmentClass.ANALYSIS, byteArrayOf(1), AiInputOrigin.Preview("comune", "dev-b"))
    buffer.putFrom(b, SegmentClass.ANALYSIS, byteArrayOf(2), AiInputOrigin.Own)
    buffer.putFrom(c, SegmentClass.HQ, byteArrayOf(3), AiInputOrigin.Preview("comune", null))
    assertThat(buffer.getFor(a, SegmentClass.ANALYSIS, "privata")).isNull()
    assertThat(buffer.getFor(a, SegmentClass.ANALYSIS, "COMUNE")!!.bytes).isEqualTo(byteArrayOf(1))
    assertThat(buffer.getFor(b, SegmentClass.ANALYSIS, null)!!.bytes).isEqualTo(byteArrayOf(2))
    buffer.forgetDevice("dev-b")
    assertThat(buffer.contains(a, SegmentClass.ANALYSIS)).isFalse()
    assertThat(buffer.contains(c, SegmentClass.HQ)).isFalse()
    assertThat(buffer.contains(b, SegmentClass.ANALYSIS)).isTrue()
    // La provenienza resta dopo un riavvio del processo.
    buffer.putFrom(a, SegmentClass.ANALYSIS, byteArrayOf(4), AiInputOrigin.Preview("comune", "dev-c"))
    val again = DiskAiBuffer(tmp.root.resolve("b"))
    assertThat(again.getFor(a, SegmentClass.ANALYSIS, "privata")).isNull()
    assertThat(again.getFor(a, SegmentClass.ANALYSIS, "comune")!!.from).isEqualTo(AiInputOrigin.Preview("comune", "dev-c"))
  }

  /** IX-7: due errori di memoria del motore di fila non segnano il media "non supportato": resta da fare e la tappa dopo lo calcola. */
  @Test
  fun ix7_unErrorePassseggeroDelMotoreNonSegnaIlMedia() = runTest {
    val f = TestFamily()
    val key = f.nas.add(TestFamily.COMMON)
    val anna = f.anna()
    anna.images.failNext += listOf(AiEngineError.OUT_OF_MEMORY, AiEngineError.OUT_OF_MEMORY)
    repeat(2) {
      val run = anna.engine.run(AiScope.Share(TestFamily.COMMON), forever)
      assertThat(run.computed).isEqualTo(0)
    }
    assertThat(anna.unsupported).isEmpty()
    anna.engine.run(AiScope.Share(TestFamily.COMMON), forever)
    assertThat(f.published(TestFamily.COMMON, anna.id)).containsExactly(key)
    // Un errore della libreria che il motore chiama BAD_INPUT, dopo che l'immagine e' passata da ModelInputs, e' passeggero anche lui.
    val other = f.nas.add(TestFamily.COMMON)
    anna.images.failNext += AiEngineError.BAD_INPUT
    anna.engine.run(AiScope.Share(TestFamily.COMMON), forever)
    assertThat(anna.unsupported).isEmpty()
    anna.engine.run(AiScope.Share(TestFamily.COMMON), forever)
    assertThat(f.published(TestFamily.COMMON, anna.id)).containsExactly(key, other)
  }

  /** IX-7: un media che fa fallire il motore sempre non si riprova senza fine: dopo tre volte nello stesso processo vale come non valido. */
  @Test
  fun ix7_unMediaCheFallisceSempreSiFermaDopoTreVolte() = runTest {
    val f = TestFamily()
    f.nas.add(TestFamily.COMMON)
    val anna = f.anna()
    repeat(3) {
      anna.images.failNext += listOf(AiEngineError.OUT_OF_MEMORY)
      anna.engine.run(AiScope.Share(TestFamily.COMMON), forever)
    }
    assertThat(anna.unsupported).hasSize(1)
  }

  /** IX-6: le voci di un lotto vanno nell'outbox con una chiamata sola (la regola M3 rilegge la share una volta per lotto, non per media). */
  @Test
  fun ix6_leVociDiUnLottoVannoNellOutboxInsieme() = runTest {
    val f = TestFamily()
    repeat(20) { f.nas.add(TestFamily.COMMON) }
    val anna = f.anna()
    anna.engine.run(AiScope.Share(TestFamily.COMMON), forever)
    assertThat(f.published(TestFamily.COMMON, anna.id)).hasSize(20)
    assertThat(anna.publisher.stageCalls).isEqualTo(1)
  }
}
