package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiUrgentKeys
import dev.pampa.mycloudgallery.core.ai.compute.UrgentWait
import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Il registro dei media che l'utente ha chiesto di analizzare adesso ("Analizza adesso" dal visore, Pacchetto X): solo chiavi di
 * contenuto, l'ora della richiesta e l'identita' locale del media (un identificativo opaco, per ritrovarlo nel catalogo anche oltre il
 * limite delle liste, XS-4), in un file di righe di `noBackupFilesDir` (come `AiStatusMemory` e i segni "non supportato").
 * Sopravvive alla morte del processo perche' il calcolo gira quando WorkManager lo avvia, anche in un processo nato per lui: la richiesta
 * dell'utente non deve perdersi perche' l'app si e' chiusa. Mai sul NAS: e' una precedenza **di questo dispositivo**, non un dato
 * dell'indice.
 *
 * **Una richiesta non e' per sempre** (XS-2, Pacchetto XF): ha un tetto ([MAX_KEYS]: oltre, le piu' vecchie escono per prime) e **scade**
 * dopo [ASK_TTL_MILLIS] (24 ore: un media che non si riesce a servire in un giorno vale come mai chiesto; finira' comunque nella coda
 * ordinaria). Escono subito anche i media finiti ([done]), cancellati o non supportati o non di questo dispositivo (li toglie il ciclo di
 * lavoro con [drop], mai la risoluzione delle miniature, XS-5). Una richiesta incontrata ma non servibile ([waiting]) resta, ma passa
 * **dietro** a quelle che non hanno ancora aspettato: la **priorita'** ([priority], quella che il selettore delle unita' vede) e' fatta di
 * al piu' [PRIORITY_KEYS] chiavi, le meno attese per prime, cosi' le richieste fanno a turno e non riempiono la coda del lavoro ordinario.
 *
 * L'ordine di [urgent] e' **l'ultima chiesta per prima** (XS-4). L'azzeramento lo svuota ([clear] da `AiGraph.stop`, e il file sta nella
 * cartella che l'eraser toglie).
 */
class AiUrgentFile(
  private val file: File,
  /** L'ora del telefono: serve solo a far scadere le richieste (un orologio sbagliato le fa durare di piu' o di meno, niente di piu'). */
  private val now: () -> Long = System::currentTimeMillis,
) : AiUrgentKeys {
  private class Entry(val key: ContentKey, var askedAt: Long, val media: MediaId? = null, var attempts: Int = 0, var wait: UrgentWait? = null)

  private val lock = Any()

  /** Le richieste, dalla piu' vecchia alla piu' recente (l'ordine di inserimento). */
  private val entries = LinkedHashMap<ContentKey, Entry>()

  private val flow = MutableStateFlow<Set<ContentKey>>(emptySet())
  private val priorityFlow = MutableStateFlow<Set<ContentKey>>(emptySet())
  private val waitsFlow = MutableStateFlow<Map<ContentKey, UrgentWait>>(emptyMap())

  /**
   * Le richieste di adesso (scadute escluse), come flusso: la pagina "Analisi" e il visore lo seguono mentre cambiano. E' un **insieme** (conta cio'
   * che c'e', e un flusso di insiemi non emette per un cambio di solo ordine): l'ordine delle richieste, l'ultima chiesta per prima, lo da' [urgent].
   */
  val state: StateFlow<Set<ContentKey>> = flow.asStateFlow()

  /** Le richieste che il selettore delle unita' mette davanti: al piu' [PRIORITY_KEYS], le meno attese per prime (XS-2). */
  val priority: StateFlow<Set<ContentKey>> = priorityFlow.asStateFlow()

  /** Perche' una richiesta non e' partita nell'ultima tappa che l'ha incontrata (solo in memoria): per il visore. */
  val waits: StateFlow<Map<ContentKey, UrgentWait>> = waitsFlow.asStateFlow()

  init {
    synchronized(lock) {
      loadLocked()
      expireLocked()
      publishLocked()
    }
  }

  /** "Analizza adesso": il media va in testa alle richieste (l'ultimo chiesto e' il primo in coda) e l'attesa riparte da zero. */
  fun add(key: ContentKey, media: MediaId? = null) {
    synchronized(lock) {
      entries.remove(key)
      entries[key] = Entry(key, now(), media)
      expireLocked()
      trimLocked()
      saveLocked()
    }
  }

  /** Le richieste di adesso, **l'ultima chiesta per prima** (XS-4); le scadute sono gia' uscite. */
  override fun urgent(): Set<ContentKey> = synchronized(lock) {
    if (expireLocked()) saveLocked()
    entries.keys.toList().asReversed().toCollection(LinkedHashSet())
  }

  override fun done(key: ContentKey) {
    synchronized(lock) {
      if (entries.remove(key) != null) saveLocked()
    }
  }

  override fun waiting(key: ContentKey, why: UrgentWait) {
    synchronized(lock) {
      val entry = entries[key] ?: return
      entry.attempts++
      entry.wait = why
      publishLocked()
    }
  }

  /** Togli quelle che sono gia' a posto (le riconosce il ciclo di lavoro con il criterio del calcolo, [dev.pampa.mycloudgallery.core.ai.compute.AiCompletion]). */
  fun remove(keys: Collection<ContentKey>) {
    if (keys.isEmpty()) return
    synchronized(lock) {
      var changed = false
      for (key in keys) if (entries.remove(key) != null) changed = true
      if (changed) saveLocked()
    }
  }

  /** L'azzeramento: dimentica tutto e toglie il file. */
  fun clear() {
    synchronized(lock) {
      entries.clear()
      publishLocked()
      StateFiles.delete(file)
    }
  }

  /** Fa scadere le richieste vecchie adesso (il ciclo di lavoro lo chiede a inizio tappa). Vero se ne e' uscita qualcuna. */
  fun expire(): Boolean = synchronized(lock) {
    val changed = expireLocked()
    if (changed) saveLocked()
    changed
  }

  /** Perche' [key] ha **aspettato** (incontrata ma non servibile) nell'ultima tappa di questo processo, se e' successo: il visore lo dice "in attesa". */
  fun waitOf(key: ContentKey): UrgentWait? = synchronized(lock) { entries[key]?.wait }

  /** Vero se qualche richiesta e' arrivata dopo [millis] (l'ora del telefono): un lavoro finito la raccoglie con un giro in piu'. */
  fun askedSince(millis: Long): Boolean = synchronized(lock) { entries.values.any { it.askedAt >= millis } }

  /** Il media che l'utente ha toccato per chiedere [key], se lo si sa (le richieste di prima non lo ricordano). */
  fun mediaOf(key: ContentKey): MediaId? = synchronized(lock) { entries[key]?.media }

  /** Quante volte e' stata incontrata e non servita (solo in memoria): per la rotazione e per le prove. */
  fun attemptsOf(key: ContentKey): Int = synchronized(lock) { entries[key]?.attempts ?: 0 }

  private fun expireLocked(): Boolean {
    val fresh = now()
    val gone = entries.values.filter { fresh - it.askedAt > ASK_TTL_MILLIS }
    gone.forEach { entries.remove(it.key) }
    return gone.isNotEmpty()
  }

  private fun trimLocked() {
    while (entries.size > MAX_KEYS) entries.remove(entries.keys.first())
  }

  private fun loadLocked() {
    try {
      if (!file.isFile) return
      val loadedAt = now()
      file.readLines().forEach { raw ->
        val parts = raw.trim().split(' ')
        // Formato v3: "v3 <chiave> <ora della richiesta> <media o ->"; v2 senza il media; le righe di prima (la sola chiave) valgono "chieste adesso".
        val hex: String
        val at: Long
        var media: MediaId? = null
        when {
          parts.size == 4 && parts[0] == V3 -> {
            hex = parts[1]
            at = parts[2].toLongOrNull() ?: loadedAt
            media = parts[3].takeUnless { it == NO_MEDIA }?.let { runCatching { MediaId(it) }.getOrNull() }
          }
          parts.size == 3 && parts[0] == V2 -> {
            hex = parts[1]
            at = parts[2].toLongOrNull() ?: loadedAt
          }
          else -> {
            hex = parts[0]
            at = loadedAt
          }
        }
        runCatching { ContentKey(hex) }.getOrNull()?.let { key ->
          entries.remove(key)
          entries[key] = Entry(key, at, media)
        }
      }
      trimLocked()
    } catch (e: IOException) {
      // Nel dubbio nessuna urgenza: la coda torna quella di sempre (l'errore e' nel log di `StateFiles`).
    }
  }

  private fun saveLocked() {
    publishLocked()
    try {
      StateFiles.writeText(file, entries.values.joinToString("") { "$V3 ${it.key.hex} ${it.askedAt} ${it.media?.value ?: NO_MEDIA}\n" })
    } catch (e: IOException) {
      // Resta in memoria per questo processo (l'errore e' nel log di `StateFiles`).
    }
  }

  private fun publishLocked() {
    flow.value = entries.keys.toList().asReversed().toCollection(LinkedHashSet())
    // La priorita': le meno attese per prime; a pari attesa, l'ultima chiesta per prima.
    val ranked = entries.values.withIndex()
      .sortedWith(compareBy<IndexedValue<Entry>> { it.value.attempts }.thenByDescending { it.index })
      .take(PRIORITY_KEYS).map { it.value.key }
    priorityFlow.value = ranked.toCollection(LinkedHashSet())
    waitsFlow.value = entries.values.mapNotNull { e -> e.wait?.let { e.key to it } }.toMap()
  }

  companion object {
    const val FILE = "ai-urgent"

    private const val V2 = "v2"
    private const val V3 = "v3"
    private const val NO_MEDIA = "-"

    /** Al piu' cosi' tante richieste ricordate: oltre, il tocco piu' vecchio vale come mai fatto. */
    const val MAX_KEYS: Int = 64

    /** Una richiesta vale 24 ore: oltre, scade (XS-2). Il media finira' comunque nella coda ordinaria. */
    const val ASK_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Quante richieste al massimo passano davanti nella finestra delle unita' (XS-2): le altre aspettano il loro turno. */
    const val PRIORITY_KEYS: Int = 16
  }
}
