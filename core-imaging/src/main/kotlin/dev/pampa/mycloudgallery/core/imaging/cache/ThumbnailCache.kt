package dev.pampa.mycloudgallery.core.imaging.cache

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * I livelli di `onTrimMemory` (`ComponentCallbacks2.TRIM_MEMORY_*`) come numeri: i tre "in esecuzione" sono
 * deprecati dall'API 35 (il sistema non li manda piu') ma i dispositivi piu' vecchi si, e il valore non cambia.
 */
internal object TrimLevels {
  const val RUNNING_MODERATE = 5
  const val RUNNING_LOW = 10
  const val RUNNING_CRITICAL = 15
  const val UI_HIDDEN = 20
  const val BACKGROUND = 40
}

/** I contatori della cache, per le statistiche del caricatore. */
internal data class CacheCounters(
  val hits: Long,
  val misses: Long,
  val usedBytes: Long,
  val entries: Int,
)

/**
 * La cache in memoria delle miniature (D5): **a budget in byte**, LRU, con la finestra protetta.
 *
 * - **Budget.** La somma di `allocationByteCount` delle bitmap non supera [budgetBytes]; quando lo supera si
 *   scartano le meno usate di recente.
 * - **La finestra non si scarta per fare posto.** Le voci *visibili* non si scartano mai per caricarne altre, e
 *   quelle *in arrivo* solo per caricarne una visibile: altrimenti, con una finestra piu' grande del budget, ogni
 *   miniatura caricata ne caccerebbe un'altra della finestra, il pianificatore la richiederebbe di nuovo e il
 *   caricatore girerebbe all'infinito. [hasRoom] dice al pianificatore quando non c'e' piu' posto per un tipo di
 *   richiesta; in quel caso non parte altro lavoro finche' la finestra non cambia. Le voci protette si scartano solo
 *   su richiesta esplicita ([trim] critico, [clear]).
 * - **Stato osservabile.** [stateFor] da' alla cella un `State` che vale null finche' la bitmap non c'e' e poi la
 *   bitmap. Lo stato e' unico per chiave: la cella che lo tiene continua a ricevere gli aggiornamenti anche dopo
 *   uno scarto e un nuovo caricamento (lo stato torna null allo scarto e si riempie al ritorno). La cache tiene gli
 *   stati solo con riferimenti deboli: non trattiene le celle uscite dalla composizione.
 * - **Niente `recycle()`.** Una bitmap scartata puo' essere ancora disegnata da una cella: la libera il GC.
 *
 * - **Gli stati li crea solo chi li legge.** Uno stato di Compose nasce solo in [stateFor], cioe' sul filo di chi compone;
 *   [put] e gli scarti (che girano sui fili del caricatore) aggiornano gli stati che esistono gia' e non ne creano. Uno
 *   stato creato da un altro filo **dopo** che una composizione ha preso la sua istantanea, e letto in quella
 *   composizione, fa cadere l'app («Reading a state that was created after the snapshot was taken»): era il crash della
 *   scheda Album del 11/11/2026 (copertine chieste prima che le celle fossero composte).
 *
 * Tutto sotto un lock corto; le scritture sugli stati di Compose avvengono dopo averlo rilasciato.
 */
internal class ThumbnailCache(val budgetBytes: Long) {

  init {
    require(budgetBytes > 0) { "Il budget della cache e' positivo." }
  }

  private class Entry(val image: ImageBitmap, val bytes: Long)

  private val lock = Any()
  private val entries = LinkedHashMap<ThumbKey, Entry>(128, 0.75f, true)
  private val states = HashMap<ThumbKey, WeakReference<MutableState<ImageBitmap?>>>()
  private var visible: Set<ThumbKey> = emptySet()
  private var ahead: Set<ThumbKey> = emptySet()
  private var usedBytes = 0L
  private var statesSweepAt = SWEEP_START
  private val hits = AtomicLong()
  private val misses = AtomicLong()

  /**
   * Lo stato osservabile di [key]: null finche' la miniatura non e' in cache. Non avvia niente. E' l'unico punto che crea
   * uno stato (vedi la descrizione della classe): se la miniatura c'e' gia', lo stato nasce con lei dentro.
   */
  fun stateFor(key: ThumbKey): State<ImageBitmap?> {
    synchronized(lock) {
      val entry = entries[key]
      if (entry != null) hits.incrementAndGet() else misses.incrementAndGet()
      val existing = states[key]?.get()
      if (existing != null) return existing
      val created = mutableStateOf(entry?.image)
      states[key] = WeakReference(created)
      if (states.size >= statesSweepAt) {
        states.values.removeAll { it.get() == null }
        statesSweepAt = maxOf(SWEEP_START, states.size * 2)
      }
      return created
    }
  }

  /** La bitmap se e' in cache (e la segna come usata di recente). */
  fun peek(key: ThumbKey): ImageBitmap? = synchronized(lock) {
    val entry = entries[key]
    if (entry != null) hits.incrementAndGet() else misses.incrementAndGet()
    entry?.image
  }

  /** Vero se [key] e' in cache; la segna come usata di recente (la finestra protegge cio' che serve). */
  fun touch(key: ThumbKey): Boolean = synchronized(lock) { entries[key] != null }

  /**
   * Vero se si puo' ancora caricare una richiesta [forVisible] (o in arrivo): c'e' spazio nel budget, oppure c'e'
   * almeno una voce che quella richiesta puo' scartare (fuori dalla finestra; per una visibile, anche in arrivo).
   */
  fun hasRoom(forVisible: Boolean): Boolean = synchronized(lock) {
    usedBytes < budgetBytes || entries.keys.any { it !in visible && (forVisible || it !in ahead) }
  }

  /**
   * Mette [bitmap] in cache sotto [key], scarta le voci che puo' scartare (vedi la descrizione della classe) fino al
   * budget e aggiorna lo stato osservato dalle celle. Lo sforamento dovuto ai lavori gia' in volo (al massimo il
   * tetto di concorrenza) si accetta.
   */
  fun put(key: ThumbKey, bitmap: Bitmap): ImageBitmap {
    val image = bitmap.asImageBitmap()
    val bytes = bitmap.allocationByteCount.toLong()
    val updates = ArrayList<Pair<MutableState<ImageBitmap?>, ImageBitmap?>>()
    synchronized(lock) {
      entries.remove(key)?.let { usedBytes -= it.bytes }
      entries[key] = Entry(image, bytes)
      usedBytes += bytes
      // Solo lo stato che una cella ha gia' chiesto: qui (filo del caricatore) non se ne creano.
      states[key]?.get()?.let { updates += it to image }
      // Una richiesta visibile puo' far uscire quelle in arrivo; una in arrivo (o fuori finestra) no.
      evictLocked(budgetBytes, updates, protectVisible = true, protectAhead = key !in visible, keep = key)
    }
    applyUpdates(updates)
    return image
  }

  /** La finestra corrente: le chiavi visibili (protette del tutto) e quelle in arrivo (protette dalle richieste in arrivo). */
  fun pin(visible: Set<ThumbKey>, ahead: Set<ThumbKey> = emptySet()) {
    synchronized(lock) {
      this.visible = visible
      this.ahead = ahead
    }
  }

  /**
   * `onTrimMemory`: riduce la cache secondo il livello. Con i livelli lievi si salvano le voci visibili; da
   * `RUNNING_CRITICAL` in su si riduce tutto. Ritorna vero se ha scartato anche voci visibili (il caricatore le
   * richiede di nuovo se l'app e' ancora in primo piano).
   */
  fun trim(level: Int): Boolean {
    val fraction = keepFraction(level)
    if (fraction >= 1.0) return false
    return shrinkTo((budgetBytes * fraction).toLong(), protectVisible = level < TrimLevels.RUNNING_CRITICAL)
  }

  /** Dopo un `OutOfMemoryError`: tiene al massimo la meta' del budget, risparmiando le voci visibili. */
  fun shrinkForMemoryPressure(): Boolean = shrinkTo(budgetBytes / 2, protectVisible = true)

  fun clear() {
    shrinkTo(0, protectVisible = false)
  }

  fun counters(): CacheCounters = synchronized(lock) {
    CacheCounters(hits.get(), misses.get(), usedBytes, entries.size)
  }

  // --- Interni -------------------------------------------------------------------------------------------

  /** Scarta fino a [targetBytes]. Ritorna vero se ha toccato voci visibili. */
  private fun shrinkTo(targetBytes: Long, protectVisible: Boolean): Boolean {
    val updates = ArrayList<Pair<MutableState<ImageBitmap?>, ImageBitmap?>>()
    val touchedVisible = synchronized(lock) {
      evictLocked(targetBytes, updates, protectVisible = protectVisible, protectAhead = false, keep = null)
    }
    applyUpdates(updates)
    return touchedVisible
  }

  /**
   * Scarta dalle meno usate, in tre passate: le voci fuori dalla finestra; poi, se [protectAhead] e' falso, quelle in
   * arrivo; poi, se [protectVisible] e' falso, le visibili. Non scarta mai [keep] (la voce appena inserita).
   * Ritorna vero se ne ha scartata una visibile.
   */
  private fun evictLocked(
    targetBytes: Long,
    updates: MutableList<Pair<MutableState<ImageBitmap?>, ImageBitmap?>>,
    protectVisible: Boolean,
    protectAhead: Boolean,
    keep: ThumbKey?,
  ): Boolean {
    if (usedBytes <= targetBytes) return false
    fun pass(allowed: (ThumbKey) -> Boolean): Boolean {
      var evicted = false
      val iterator = entries.entries.iterator()
      while (usedBytes > targetBytes && iterator.hasNext()) {
        val (key, entry) = iterator.next()
        if (key == keep || !allowed(key)) continue
        iterator.remove()
        usedBytes -= entry.bytes
        states[key]?.get()?.let { updates += it to null }
        evicted = true
      }
      return evicted
    }
    pass { it !in visible && it !in ahead }
    if (usedBytes > targetBytes && !protectAhead) pass { it !in visible }
    if (usedBytes > targetBytes && !protectVisible) return pass { true }
    return false
  }

  private fun applyUpdates(updates: List<Pair<MutableState<ImageBitmap?>, ImageBitmap?>>) {
    for ((state, value) in updates) state.value = value
  }

  companion object {
    private const val SWEEP_START = 2048

    /**
     * Che frazione del budget tenere a un livello di `onTrimMemory` (`ComponentCallbacks2.TRIM_MEMORY_*`). I
     * livelli "in esecuzione" (5, 10, 15) valgono mentre l'app e' visibile: si riduce senza svuotare. Da `UI_HIDDEN` in
     * su l'interfaccia non si vede piu': prima un quarto, poi niente.
     */
    fun keepFraction(level: Int): Double = when {
      level >= TrimLevels.BACKGROUND -> 0.0
      level >= TrimLevels.UI_HIDDEN -> 0.25
      level >= TrimLevels.RUNNING_CRITICAL -> 0.25
      level >= TrimLevels.RUNNING_LOW -> 0.5
      level >= TrimLevels.RUNNING_MODERATE -> 0.75
      else -> 1.0
    }
  }
}
