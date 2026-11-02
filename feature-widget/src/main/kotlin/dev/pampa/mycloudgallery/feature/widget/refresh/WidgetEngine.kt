package dev.pampa.mycloudgallery.feature.widget.refresh

import android.content.res.Resources
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetRules
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetPreparer
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetSizing
import dev.pampa.mycloudgallery.feature.widget.frame.WidgetTexts
import dev.pampa.mycloudgallery.feature.widget.glance.WidgetComposer
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import dev.pampa.mycloudgallery.feature.widget.host.WidgetServices
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Come e' andato un ridisegno di un widget. */
internal enum class RefreshResult {
  /** Disegnato, con le sue foto o con il suo messaggio. */
  DRAWN,

  /** Non ridisegnato: la Libreria non e' ancora pronta o le porte non ci sono (E-W4). Resta il contenuto di prima, che era verificato. */
  KEPT,

  /** Disegnato **senza immagini**: il registro non ha scritto (D9: senza registro niente foto) o l'errore e' stato riconosciuto. */
  BLANKED,
}

/**
 * Il motore del ridisegno (Fase 10, P3, docs/fase10-progetto.md §2.1, D5, D6, D9, D42): dal piano al `RemoteViews` consegnato ad `AppWidgetManager`, per ogni causa. Tutta la
 * logica e' qui, sopra due porte ([WidgetTargets], [WidgetComposer]) e sopra le porte di `:app` (`services`): si prova sulla JVM con tempo virtuale; `GlanceWidgetRefresher`
 * e' la sua faccia pubblica (`WidgetRefresher`).
 *
 * **Mai un nascosto, nemmeno per un istante** (D5). Tre garanzie:
 * 1. il pianificatore fa il filtro finale come ultimo passo (`visibleOrThrow`, `hiddenAmongOrThrow`);
 * 2. **fra il piano e la consegna** passano le decodifiche (anche centinaia di millisecondi): un "Nascondi" che arriva li' sposta [generation], e il giro in corso **non
 *    consegna** (ricomincia da capo, al piu' [MAX_ATTEMPTS] volte); registrare e consegnare stanno dentro [gate], lo stesso cancello dello svuotamento, quindi nessun
 *    ridisegno vecchio sovrascrive un widget gia' svuotato;
 * 3. lo **svuotamento** ([hiddenChanged]) non aspetta i giri: prende solo il cancello (breve), compone un disegno senza immagini e lo consegna (< 100 ms), poi fa il giro.
 *
 * I giri interi sono **in fila** ([refreshLock]): il piano di ogni giro ha un'epoca piu' alta del precedente e il registro e il disegno restano d'accordo (un tocco apre
 * sempre il media che si vede).
 */
internal class WidgetEngine(
  private val services: suspend (budgetMs: Long) -> WidgetServices?,
  private val targets: WidgetTargets,
  private val composer: WidgetComposer,
  private val resources: () -> Resources,
  private val describer: () -> MediaDescriber = { MediaDescribers.shared() },
  /**
   * Dopo ogni disegno con le foto si **compone in anticipo** il disegno senza immagini di quel widget (e si tiene in memoria): lo svuotamento di D5 non compone, **consegna**
   * (comporre con Glance costa decine o centinaia di millisecondi, consegnare qualche millisecondo). Falso nei test che contano i disegni.
   */
  private val prewarmBlanks: Boolean = false,
) {

  /** Si sposta a ogni causa che rende vecchio un giro in corso (nascosto, ricordo scartato, blocco, aspetto, azzeramento). */
  private val generation = AtomicLong()
  private val gate = Mutex()
  private val refreshLock = Mutex()

  /** I disegni senza immagini gia' composti, per misura, testo e impostazioni d'aspetto (pochi: un widget per misura, e il testo e' quasi sempre lo stesso). */
  private val blankCache = object : LinkedHashMap<BlankKey, RemoteViews>(BLANK_CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BlankKey, RemoteViews>): Boolean = size > BLANK_CACHE_SIZE
  }

  private data class BlankKey(val width: Int, val height: Int, val message: String, val settings: EngineSettings)

  /** Svuota la memoria dei disegni senza immagini (i test, e un cambio d'aspetto la rende inutile da solo: la chiave porta le impostazioni). */
  fun clearBlankCache() {
    synchronized(blankCache) { blankCache.clear() }
  }

  /** Ridisegna [slot]. Un giro che trova una causa nuova a meta' ricomincia (D5); non piu' di [MAX_ATTEMPTS] volte. */
  suspend fun refresh(slot: WidgetSlot, budgetMs: Long = WidgetRules.BUDGET_MS): RefreshResult = refreshLock.withLock refreshing@{
    var attempts = 0
    while (attempts++ < MAX_ATTEMPTS) {
      val ports = services(WidgetRules.COLD_START_BUDGET_MS) ?: return@refreshing RefreshResult.KEPT
      val started = generation.get()
      val (width, height) = dimsOf(slot)
      val size = WidgetSizing.classify(width, height)
      val preparer = WidgetPreparer(ports)
      val prepared = preparer.prepare(slot, size, budgetMs) ?: return@refreshing RefreshResult.KEPT
      val texts = WidgetTexts(resources(), ports.places)
      var result: RefreshResult? = null
      gate.withLock committing@{
        // D5: una causa e' arrivata mentre decodificavo: questo piano puo' contenere un nascosto. Non si consegna.
        if (generation.get() != started) return@committing
        // §2.1 passo 7: il registro **prima** del disegno. Se non scrive, niente foto (E-W7: un tocco non troverebbe il media giusto).
        val recorded = try {
          ports.ledger.record(prepared.plan)
          true
        } catch (e: IOException) {
          GalleryLog.w(TAG) { "Registro del widget non scritto (${e.javaClass.simpleName}): nessuna foto." }
          false
        }
        val frame = if (recorded) {
          WidgetPreparer.frame(prepared, describer(), texts)
        } else {
          WidgetPreparer.blankFrame(slot, size, WidgetEmpty.ERROR, texts)
        }
        targets.push(slot, composer.compose(frame, DpSize(width.dp, height.dp), ports.appearance.settings()))
        result = if (recorded) RefreshResult.DRAWN else RefreshResult.BLANKED
      }
      if (result == RefreshResult.DRAWN && prewarmBlanks) warmBlank(slot, size, width, height, texts, ports.appearance.settings())
      result?.let { return@refreshing it }
    }
    RefreshResult.KEPT
  }

  /** Ridisegna ogni widget, uno dopo l'altro (il cursore delle foto e' condiviso: due widget, foto diverse). */
  suspend fun refreshAll(budgetMs: Long = WidgetRules.BUDGET_MS) {
    for (slot in targets.slots()) {
      try {
        refresh(slot, budgetMs)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "Ridisegno del widget non riuscito (${e.javaClass.simpleName})." }
      }
    }
  }

  /**
   * "Nascondi" su questo dispositivo o arrivato da un altro (D5): **prima** si svuotano i widget che mostrano [ids] (nessuna decodifica, nessun piano: un disegno senza
   * immagini), **poi** il giro intero. Se le porte non ci sono non si sa cosa mostra ogni widget: **si svuotano tutti** (fallire chiusi).
   */
  suspend fun hiddenChanged(ids: Set<MediaId>) {
    generation.incrementAndGet()
    val ports = services(0)
    val affected = affectedBy(ports) { it.ledger.showing(ids) }
    blank(affected, ports, WidgetEmpty.NO_MEDIA)
    for (slot in affected) refresh(slot)
  }

  /** Un ricordo scartato, escluso con "Mai" o potato (E-W19): come [hiddenChanged], per i widget che mostrano quel ricordo. */
  suspend fun memoryDropped(id: MemoryId) {
    generation.incrementAndGet()
    val ports = services(0)
    val affected = affectedBy(ports) { it.ledger.showingMemory(id) }
    blank(affected, ports, WidgetEmpty.NO_MEDIA)
    for (slot in affected) refresh(slot)
  }

  /**
   * I widget che una causa tocca, dal registro. **Fallire chiusi** (D5, punto 5): senza le porte o se il registro non si legge non si sa cosa mostra ogni widget, quindi si
   * considerano toccati **tutti** (un widget svuotato in piu' si ridisegna subito dopo; un nascosto rimasto sulla Home no).
   */
  private suspend fun affectedBy(ports: WidgetServices?, lookup: suspend (WidgetServices) -> Set<WidgetSlot>): List<WidgetSlot> {
    if (ports == null) return targets.slots()
    return try {
      lookup(ports).toList()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "Registro del widget non letto (${e.javaClass.simpleName}): si svuotano tutti." }
      targets.slots()
    }
  }

  /** Azzeramento, perso, uscita (D42): tutti i widget a [reason] **senza immagini**, e il registro dimentica ogni cella. Ritorna a disegno consegnato. */
  suspend fun blankAll(reason: WidgetEmpty) {
    generation.incrementAndGet()
    val ports = services(0)
    blank(targets.slots(), ports, reason)
    ports?.ledger?.clear()
  }

  /** Il blocco dell'app, il tema o il ricordo del giorno sono cambiati: ogni giro in corso e' vecchio. Poi il giro intero. */
  suspend fun settingsChanged() {
    generation.incrementAndGet()
    refreshAll()
  }

  /** Il widget [slot] e' stato tolto dalla Home: il registro lo dimentica. */
  suspend fun forget(slots: List<WidgetSlot>) {
    val ports = services(0) ?: return
    for (slot in slots) ports.ledger.forget(slot)
  }

  /**
   * I disegni senza immagini di [slots]: dentro il cancello, uno per uno. Nessuna decodifica e nessun piano: solo il testo "Apri MyCloud Gallery". Un widget che non si
   * riesce a svuotare (tolto dalla Home mentre si lavora) **non ferma gli altri**: si continua, e nel log resta solo il tipo.
   */
  private suspend fun blank(slots: List<WidgetSlot>, ports: WidgetServices?, reason: WidgetEmpty) {
    for (slot in slots) {
      try {
        val (width, height) = dimsOf(slot)
        val size = WidgetSizing.classify(width, height)
        gate.withLock {
          val texts = WidgetTexts(resources(), ports?.places ?: WidgetPlaceNames { null })
          val settings = ports?.appearance?.settings() ?: EngineSettings()
          // Prima si consegna il disegno senza immagini (D5), poi il registro lo ricorda: se il processo muore in mezzo, il widget e' gia' vuoto e al piu' il registro conosce
          // celle che non si vedono piu' (uno svuotamento in piu' alla prossima causa, mai una foto che resta).
          targets.push(slot, blankViews(slot, size, width, height, reason, texts, settings))
          if (ports != null) recordBlank(ports, slot, size, reason)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.w(TAG) { "Svuotamento del widget non riuscito (${e.javaClass.simpleName})." }
      }
    }
  }

  /** Il disegno senza immagini di (misura, testo, aspetto): quello in memoria se c'e', altrimenti si compone adesso e si tiene. */
  private suspend fun blankViews(slot: WidgetSlot, size: WidgetSize, width: Int, height: Int, reason: WidgetEmpty, texts: WidgetTexts, settings: EngineSettings): RemoteViews {
    val key = BlankKey(width, height, texts.message(reason), settings)
    synchronized(blankCache) { blankCache[key] }?.let { return it }
    val views = composer.compose(WidgetPreparer.blankFrame(slot, size, reason, texts), DpSize(width.dp, height.dp), settings)
    synchronized(blankCache) { blankCache[key] = views }
    return views
  }

  /** Compone in anticipo lo svuotamento di [slot] (mai un errore verso chi ha appena disegnato). */
  private suspend fun warmBlank(slot: WidgetSlot, size: WidgetSize, width: Int, height: Int, texts: WidgetTexts, settings: EngineSettings) {
    try {
      blankViews(slot, size, width, height, WidgetEmpty.NO_MEDIA, texts, settings)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "Svuotamento in anticipo non composto (${e.javaClass.simpleName})." }
    }
  }

  /** Il registro ricorda che il widget non mostra piu' niente: un piano senza celle con un'epoca piu' alta (se il registro non scrive, lo svuotamento c'e' lo stesso). */
  private suspend fun recordBlank(ports: WidgetServices, slot: WidgetSlot, size: WidgetSize, reason: WidgetEmpty) {
    try {
      val epoch = ports.ledger.epochOf(slot) + 1
      ports.ledger.record(WidgetPlan(slot, size, WidgetMode.Message(reason), emptyList(), epoch))
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "Registro del widget non scritto allo svuotamento (${e.javaClass.simpleName})." }
    }
  }

  private fun dimsOf(slot: WidgetSlot): Pair<Int, Int> {
    val (width, height) = targets.sizeOf(slot)
    return if (width > 0 && height > 0) width to height else DEFAULT_DP to DEFAULT_DP
  }

  companion object {
    private const val TAG = "WidgetEngine"

    /** Quante volte un giro ricomincia perche' una causa e' arrivata mentre decodificava (D5). Oltre: si lascia (lo svuotamento c'e' gia') e il giro dopo riprova. */
    const val MAX_ATTEMPTS: Int = 3

    /** La misura di un widget di cui il launcher non ha detto le dimensioni: la piccola (2x2). */
    private const val DEFAULT_DP: Int = 110

    /** Quanti disegni senza immagini si tengono. */
    private const val BLANK_CACHE_SIZE: Int = 12
  }
}
