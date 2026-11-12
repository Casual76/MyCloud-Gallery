package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiCompletion
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import kotlinx.coroutines.CancellationException

/**
 * La pulizia delle richieste "Analizza adesso" **da parte del ciclo di lavoro** (XS-2, XS-5, Pacchetto XF): non la fa la risoluzione delle
 * miniature della pagina (che prima toglieva una richiesta appena esisteva un vettore, anche con il testo ancora da leggere), la fa il
 * calcolo, a inizio e a fine tappa. Toglie dal registro ([AiUrgentFile]):
 * - le richieste **scadute** (piu' di [AiUrgentFile.ASK_TTL_MILLIS]);
 * - quelle **finite** secondo **lo stesso criterio del calcolo** ([dev.pampa.mycloudgallery.core.ai.compute.AiCompletion], ricette e lettura
 *   del testo correnti), anche se a finirle e' stato un altro dispositivo (arrivate con la replica);
 * - quelle che qui **non si possono piu' servire** ([servable] falso): media cancellato, cambiato di versione, formato non supportato, non di
 *   questo dispositivo. Dove non si sa ([servable] null: niente NAS o catalogo non pronto) non si toglie niente: scadra'.
 *
 * Un guasto di una porta vale come "non so": la richiesta resta (scadra' da sola) e mai un errore esce da qui.
 */
internal class AiUrgentSweep(
  private val urgent: AiUrgentFile,
  private val isDone: suspend (ContentKey, MediaId?) -> Boolean,
  private val servable: suspend (ContentKey, MediaId?) -> Boolean?,
) {
  /** Una passata. [checkServable] chiede anche se si puo' ancora servire (a inizio tappa; a fine tappa basta il criterio di "fatto"). */
  suspend fun sweep(checkServable: Boolean) {
    urgent.expire()
    val gone = ArrayList<ContentKey>()
    for (key in urgent.urgent()) {
      val media = urgent.mediaOf(key)
      if (safe { isDone(key, media) } == true) {
        gone += key
        continue
      }
      if (checkServable && safe { servable(key, media) } == false) gone += key
    }
    urgent.remove(gone)
  }

  private suspend fun <T> safe(block: suspend () -> T): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }
}

/**
 * XS-5: **l'unico criterio di "fatto"** per un media, quello del calcolo ([AiCompletion]: vettore e, con la lettura del testo attiva, anche il
 * testo, con le ricette **correnti**), per il visore e per il registro delle richieste. Le classi vengono da cio' che e' salvato qui per ogni
 * ambito del media (le sue share e, se non ne ha nessuna, il telefono, come `ItemWorker.plan`) e da cio' che altri hanno gia' pubblicato nella
 * share ([doneByOthers], dalla replica: il completamento remoto). Fatto in **un** ambito basta.
 */
internal class AiDoneCheck(
  /** Le share di questo dispositivo che contengono la chiave (regola M3); vuoto = un media del solo telefono. */
  private val shares: suspend (ContentKey) -> List<String>,
  /** Cio' che e' salvato qui per (chiave, ricetta, ambito): `null` = i propri byte (telefono). */
  private val savedFor: suspend (ContentKey, Int, String?) -> Set<AiClass>,
  /** Cio' che altri hanno pubblicato nella share per (share, chiave, ricetta). */
  private val doneByOthers: suspend (String, ContentKey, Int) -> Set<AiClass>,
  private val recipe: AiRecipe,
  private val reading: () -> Boolean,
) {
  suspend fun isDone(key: ContentKey): Boolean {
    val scopes: List<String?> = shares(key).takeIf { it.isNotEmpty() } ?: listOf(null)
    val readingText = reading()
    for (share in scopes) {
      val have = LinkedHashSet<AiClass>()
      if (AiClass.VEC in savedFor(key, recipe.version, share)) have += AiClass.VEC
      if (AiClass.OCR in savedFor(key, recipe.ocrVersion, share)) have += AiClass.OCR
      if (share != null) {
        if (AiClass.VEC in doneByOthers(share, key, recipe.version)) have += AiClass.VEC
        if (AiClass.OCR in doneByOthers(share, key, recipe.ocrVersion)) have += AiClass.OCR
      }
      if (AiCompletion.isDone(have, readingText)) return true
    }
    return false
  }
}

/**
 * Il lavoro che resta, **senza ricontare il database a ogni media** (XS-7): a fine tappa lo ricontano [AiGraph.backlog] (numero vero, [set]);
 * nel frattempo ogni media che il calcolo finisce ([onFinished], dal flusso [dev.pampa.mycloudgallery.core.ai.contract.AiLive]) lo abbassa di
 * uno, cosi' "restano N" non resta fermo durante una tappa lunga. Mai sotto zero; null (non ancora letto) resta null.
 */
internal class RemainingTracker(initial: Int? = null) {
  private val flow = kotlinx.coroutines.flow.MutableStateFlow(initial)
  private var lastHead: ContentKey? = null

  val state: kotlinx.coroutines.flow.StateFlow<Int?> get() = flow

  /** Il numero vero, appena riletto. */
  fun set(value: Int?) {
    flow.value = value
  }

  /** Il calcolo ha un media finito in testa a "ultime fatte": un media in meno da fare. Lo stesso media due volte non conta due volte. */
  fun onFinished(recent: List<ContentKey>) {
    val head = recent.firstOrNull() ?: return
    if (head == lastHead) return
    lastHead = head
    flow.value = flow.value?.let { (it - 1).coerceAtLeast(0) }
  }

  /** La prima osservazione di un flusso gia' avviato non conta come un media finito adesso. */
  fun observed(recent: List<ContentKey>) {
    lastHead = recent.firstOrNull()
  }
}
