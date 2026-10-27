package dev.pampa.mycloudgallery.core.nasops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.PauseReason
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.engine.MoveCompletions
import dev.pampa.mycloudgallery.core.nasops.testing.KitEngine
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Campo, gruppo A, difetto 7 (Fase 7 §0 punto 11): dopo uno spostamento tra share il catalogo non si aggiornava fino alla scansione dopo e intanto
 * puntava alla sorgente, gia' nel cestino. L'esecutore vero dice a [MoveCompletions] dove e' finita la copia verificata, **dopo** la pubblicazione e
 * **prima** che la sorgente vada nel cestino. Fase 7 P4 (D29): la porta e' diventata vincolante, un errore del catalogo ferma l'elemento finche'
 * il catalogo non risponde.
 */
class CampoAMovesTest {

  private class Moved(val src: String, val dst: String, val dstShare: String, val size: Long, val sourceStillThere: Boolean, val copyThere: Boolean)

  private val a = byteArrayOf(1, 2, 3, 4, 5)
  private val b = byteArrayOf(9, 8, 7)

  @Test
  fun d7_laPosizioneNuovaSiAnnunciaDopoLaPubblicazioneEPrimaDelCestino() = runTest {
    val calls = CopyOnWriteArrayList<Moved>()
    lateinit var kit: NasOpsTestKit
    kit = NasOpsTestKit(
      engine = KitEngine.PRODUCTION,
      moves = MoveCompletions { srcShare, srcPath, dstShare, dstPath, stamp ->
        calls += Moved(
          src = "$srcShare/${srcPath.value}",
          dst = dstPath.value,
          dstShare = dstShare,
          size = stamp.size,
          sourceStillThere = kit.exists(srcShare, srcPath.value),
          copyThere = kit.bytes(dstShare, dstPath.value) != null,
        )
      },
    )
    kit.putFile("test", "Viaggi/a.jpg", a)
    kit.putFile("test", "Viaggi/b.jpg", b)
    val items = listOf(kit.itemOf("test", "Viaggi/a.jpg"), kit.itemOf("test", "Viaggi/b.jpg"))
    val plan = (kit.operations.plan(OpRequest.Move(items, "test-comune", SharePath.parse("Famiglia"))) as PlanOutcome.Ready).plan
    assertThat(kit.operations.start(plan, kit.confirm(plan))).isInstanceOf(StartOutcome.Started::class.java)

    assertThat(calls.map { it.src to it.dst }).containsExactly("test/Viaggi/a.jpg" to "Famiglia/a.jpg", "test/Viaggi/b.jpg" to "Famiglia/b.jpg")
    assertThat(calls.map { it.dstShare }.toSet()).containsExactly("test-comune")
    assertThat(calls.map { it.size }).containsExactly(a.size.toLong(), b.size.toLong())
    // Al momento dell'annuncio la copia e' pubblicata e la sorgente non e' ancora nel cestino.
    assertThat(calls.all { it.copyThere && it.sourceStillThere }).isTrue()
    // E poi lo spostamento finisce come sempre: le sorgenti nel cestino.
    assertThat(kit.exists("test", "Viaggi/a.jpg")).isFalse()
    assertThat(kit.store.ops(setOf(OpState.DONE))).hasSize(1)
  }

  /**
   * Fase 7 P4 (D29): `MoveCompletions` e' diventata la porta di completamento, vincolante. Il catalogo che non risponde **ferma** l'elemento in
   * `PUBLISHED` con la sorgente al suo posto (prima del campo A la sorgente andava nel cestino comunque); quando il catalogo risponde, la ripresa
   * finisce lo spostamento. Niente si perde in nessuno dei due momenti: la copia pubblicata e la sorgente ci sono entrambe finche' si aspetta.
   */
  @Test
  fun d7_unErroreDelCatalogoFermaLElementoFinoAllaRisposta() = runTest {
    var closed = true
    val kit = NasOpsTestKit(engine = KitEngine.PRODUCTION, moves = MoveCompletions { _, _, _, _, _ -> if (closed) error("catalogo chiuso") })
    kit.putFile("test", "Viaggi/a.jpg", a)
    val plan = (kit.operations.plan(OpRequest.Move(listOf(kit.itemOf("test", "Viaggi/a.jpg")), "test-comune", SharePath.parse("Famiglia"))) as PlanOutcome.Ready).plan
    assertThat(kit.operations.start(plan, kit.confirm(plan))).isInstanceOf(StartOutcome.Started::class.java)

    assertThat(kit.bytes("test-comune", "Famiglia/a.jpg")).isEqualTo(a)
    assertThat(kit.exists("test", "Viaggi/a.jpg")).isTrue()
    assertThat(kit.store.ops(setOf(OpState.PAUSED))).hasSize(1)
    val op = kit.store.ops(setOf(OpState.PAUSED)).single()
    assertThat(kit.store.items(op.id).single().state).isEqualTo(OpItemState.PUBLISHED)
    // Revisione B (I2): il motivo e' "catalogo" (prima "condizioni", che non diceva niente), con il prossimo tentativo nel diario.
    assertThat(op.pauseReason).isEqualTo(PauseReason.CATALOG)

    closed = false
    kit.operations.resumeAll(1.hours)
    assertThat(kit.exists("test", "Viaggi/a.jpg")).isFalse()
    assertThat(kit.store.ops(setOf(OpState.DONE))).hasSize(1)
  }
}
