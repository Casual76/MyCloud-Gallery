package dev.pampa.mycloudgallery.core.memories.pick

import dev.pampa.mycloudgallery.core.memories.contract.CandidateCell
import dev.pampa.mycloudgallery.core.memories.contract.MemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.contract.MemoryExclusions
import dev.pampa.mycloudgallery.core.memories.contract.MemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPixels
import dev.pampa.mycloudgallery.core.memories.contract.MemoryVectors
import dev.pampa.mycloudgallery.core.memories.contract.PhotoScore
import dev.pampa.mycloudgallery.core.memories.contract.PickSignals
import dev.pampa.mycloudgallery.core.memories.contract.PickSpec
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryAlbumSignals
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryLookAlikes
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPeople
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryPixels
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryVectors
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Raccoglie i segnali di un ricordo dalle porte (D6, D7, §2.1 passo 5) **una volta** per tutti i candidati: vettori, firma visiva, album,
 * persone e misure dei pixel. Ogni porta e' opzionale (E-MEM15, E-MEM16): se manca o fallisce, quel segnale manca e la scelta usa il ripiego
 * (la firma visiva, la finestra di 3 minuti, nessuna penalita' di nitidezza).
 *
 * Regole:
 *  - **chiede solo cio' che serve**: i candidati gia' esclusi da D5 non si chiedono a nessuna porta;
 *  - **la nitidezza costa**, quindi le misure dei pixel (decodifiche) si chiedono solo per i [PIXEL_FINALISTS] candidati col punteggio piu'
 *    alto *prima* delle misure (D6: "solo per i <= 200 candidati finali"), entro [pixelBudget];
 *  - **una porta non puo' allargare l'insieme**: si tiene solo cio' che riguarda i candidati chiesti, e un vettore con numeri non finiti si
 *    scarta (nessun segnale e' meglio di un segnale rotto);
 *  - **le persone falliscono chiuse**: se la porta delle persone non risponde e ci sono scelte "Mostra meno" o "Mai", l'errore passa a chi
 *    chiama (il ricordo si rifara' al giro dopo), perche' senza le persone un "Mai" non si potrebbe applicare. Senza scelte sulle persone la
 *    mancanza di risposta vale "nessuna persona";
 *  - una cancellazione passa sempre.
 *
 * Niente orologio e niente log: il budget lo misura la porta dei pixel (monotono), qui si passa e basta.
 */
class PickSignalsLoader(
  private val vectors: MemoryVectors = NoMemoryVectors,
  private val lookAlikes: MemoryLookAlikes = NoMemoryLookAlikes,
  private val pixels: MemoryPixels = NoMemoryPixels,
  private val albums: MemoryAlbumSignals = NoMemoryAlbumSignals,
  private val people: MemoryPeople = NoMemoryPeople,
) {

  suspend fun load(candidates: List<CandidateCell>, spec: PickSpec, choices: MemoriesChoices, pixelBudget: Duration): PickSignals {
    val usable = candidates.distinctBy { it.id }
      .filter { MemoryExclusions.of(it, spec.dateBound, choices) == null }
    if (usable.isEmpty()) return PickSignals(choices = choices)
    val ids = usable.map { it.id }
    val asked = ids.toHashSet()

    val vectorMap = soft { vectors.vectors(ids) }.orEmpty()
      .filter { (id, v) -> id in asked && v.isNotEmpty() && v.all { it.isFinite() } }
    val lookAlike = soft { lookAlikes.among(ids) }
    val inAlbums = soft { albums.inManualAlbums(ids) }.orEmpty().filterTo(HashSet()) { it in asked }
    val peopleMap = loadPeople(ids, asked, choices)

    // Le misure dei pixel solo per i finalisti: il punteggio di prima delle misure ordina, le misure poi lo correggono.
    val before = PickSignals(vectorMap, lookAlike, emptyMap(), inAlbums, peopleMap, choices)
    val finalists = usable.sortedWith(
      compareByDescending<CandidateCell> { PhotoScore.of(it, before) }.thenBy { it.timeMs }.thenBy { it.id.value },
    ).take(PIXEL_FINALISTS).map { it.id }
    val finalistSet = finalists.toHashSet()
    val probes = soft { pixels.probe(finalists, pixelBudget) }.orEmpty().filterKeys { it in finalistSet }

    return PickSignals(vectorMap, lookAlike, probes, inAlbums, peopleMap, choices)
  }

  private suspend fun loadPeople(ids: List<MediaId>, asked: Set<MediaId>, choices: MemoriesChoices) = try {
    people.peopleIn(ids).filterKeys { it in asked }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    if (choices.people.isNotEmpty()) throw e
    emptyMap()
  }

  /** Una porta opzionale: un guasto vale "nessun segnale", una cancellazione passa. */
  private suspend inline fun <T> soft(block: () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }

  override fun toString(): String = "PickSignalsLoader"

  companion object {
    /** Per quanti candidati al massimo di un ricordo si misurano i pixel (D6). */
    const val PIXEL_FINALISTS: Int = 200
  }
}
