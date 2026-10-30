package dev.pampa.mycloudgallery.debugtools.eval.memories

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import java.util.UUID

/**
 * Il banco di prova dello strumento "Valutazione dei ricordi": una porta finta con ricordi **inventati** (nessuna foto, nessun titolo vero, nessun nome), le stesse
 * regole che P13 deve rispettare ([MemoriesEvalEnvironment]): un media in [hidden] non e' piu' visibile e non esce. Gli id sono quelli di `memoryId` e `media`.
 */
internal class MemoriesEvalTestRig(private val count: Int = 8) : MemoriesEvalEnvironment {
  /** I media diventati nascosti dopo la corsa (il test li sceglie). */
  val hidden = LinkedHashSet<String>()

  var generateCalls = 0
    private set

  var lastDays = 0
    private set

  override val today: LocalDate = LocalDate.of(2026, 10, 30)

  override suspend fun generate(days: Int): List<EvalMemory> {
    generateCalls++
    lastDays = days
    // Un ricordo per giorno, a partire da giorni fa; dai tipi a rotazione; da 6 a 8 foto. Il primo e' di tipo "otd".
    val kinds = listOf("otd", "trip", "person", "month", "event")
    return List(count) { n ->
      val size = 6 + n % 3
      val members = List(size) { media(n * 100 + it) }
      EvalMemory(memoryId(n), kinds[n % kinds.size], today.minusDays((count - n).toLong()), members.first(), members, "Titolo inventato $n")
    }
  }

  override suspend fun visibleAmong(ids: Collection<String>): Set<String> = ids.filter { it !in hidden }.toSet()

  override suspend fun thumbnail(id: MediaId): ImageBitmap? = null

  companion object {
    /** L'ennesimo id di ricordo: 26 caratteri dell'alfabeto di Crockford, con il numero in fondo. */
    fun memoryId(n: Int): String = "0".repeat(20) + "%06d".format(n)

    fun media(n: Int): String = UUID(0x6d00_0000_0000_0000L, n.toLong()).toString()
  }
}
