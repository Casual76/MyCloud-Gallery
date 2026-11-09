package dev.pampa.mycloudgallery.core.personal.store

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalApplyResult
import dev.pampa.mycloudgallery.core.personal.contract.PersonalChange
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalMerge
import dev.pampa.mycloudgallery.core.personal.contract.PersonalPublication
import dev.pampa.mycloudgallery.core.personal.contract.PersonalRules
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValidity
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalVerdict
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lo store dei dati personali di UN dispositivo ([author] = il suo id): lo stato unito, le modifiche "da datare", le teste gia'
 * accettate e l'ultima pubblicazione, salvati in un [PersonalLedger]; i preferiti e i nascosti seguiti nel catalogo da una
 * [PersonalProjection].
 *
 * **Ora.** Una modifica prende l'ora del NAS da [nasNow] se c'e' (D32: mai l'orologio del telefono; `:app` passa `null` anche quando
 * l'ora del NAS e' sospetta dopo un salto), altrimenti resta "da datare" e la prende alla prima sincronizzazione ([stamp]). L'ora
 * data a una modifica non e' mai prima di cio' che il dispositivo gia' sa di quella chiave: una modifica fatta DOPO aver visto una
 * voce non puo' perdere contro quella voce per un'ora del NAS sfasata di qualche secondo (ordine causale, dentro la tolleranza di
 * 2 minuti che i lettori accettano).
 *
 * **Catalogo.** L'effetto locale di una modifica (il preferito acceso, il media nascosto) e' immediato anche se resta da datare.
 * Una voce arrivata da un altro dispositivo si porta nel catalogo dopo essere stata salvata, e **prima** di salvarla la sua chiave
 * entra in `unprojected`: se il processo muore a meta', al giro dopo si rifa'. Mai la voce di un altro sopra una modifica locale
 * ancora da datare (l'utente ha appena nascosto una foto: non deve riapparire per una voce piu' vecchia).
 *
 * **A lotti** (review generale, OA-1). Il registro e' un'istantanea sola, riscritta per intero a ogni salvataggio: chi cambia molte voci
 * insieme (le foto aggiunte a un album, una copia di un album condiviso chiuso) usa [setAll], che applica tutto in memoria e salva
 * **una volta**. Una [set] per voce riscriveva il registro per ogni voce: con 20.000 voci, 2.000 foto erano 7,5 GiB e 44 secondi.
 *
 * Tutte le operazioni sono serializzate. Il primo uso legge il registro; [open] lo fa prima, per chi legge [entries] subito.
 */
class LedgerPersonalStore(
  private val author: String,
  private val nasNow: () -> Long?,
  private val ledger: PersonalLedger,
  private val projection: PersonalProjection = NoProjection,
) : PersonalSyncStore {

  private val lock = Mutex()
  private var loaded = false

  /** Mai modificata dopo che e' stata pubblicata in [state]: ogni cambiamento ne costruisce una nuova. */
  private var current: Map<PersonalKey, PersonalEntry> = emptyMap()
  private val pending = LinkedHashMap<PersonalKey, PendingChange>()
  private val unprojected = LinkedHashSet<PersonalKey>()
  private val marks = HashMap<String, HeadMark>()
  private var published: PersonalPublication? = null

  /** L'ultimo salvataggio non e' riuscito: in memoria c'e' qualcosa che il registro non ha (lo porta il salvataggio dopo). Sotto [lock]. */
  private var unsaved = false

  private val state = MutableStateFlow<Map<PersonalKey, PersonalEntry>>(emptyMap())
  override val entries: StateFlow<Map<PersonalKey, PersonalEntry>> = state

  /** Lo stato unito con sopra le modifiche ancora da datare (vedi [effectiveEntries]): si riemette a ogni cambio dell'uno o delle altre. */
  private val effectiveState = MutableStateFlow<Map<PersonalKey, PersonalEntry>>(emptyMap())
  override val effectiveEntries: StateFlow<Map<PersonalKey, PersonalEntry>> = effectiveState

  /** Legge il registro locale (una volta). */
  suspend fun open() {
    lock.withLock { ensureLoaded() }
  }

  override suspend fun get(key: PersonalKey): PersonalEntry? = lock.withLock {
    ensureLoaded()
    current[key]
  }

  override suspend fun set(key: PersonalKey, value: PersonalValue, fingerprint: PersonalFingerprint?) {
    // Una modifica sola salva sempre, anche se il valore non cambia: chi riprova dopo un guasto del disco porta sul registro cio' che mancava.
    write(listOf(PersonalChange(key, value, fingerprint)), always = true)
  }

  /**
   * Il lotto si applica in memoria nell'ordine dato e si salva **una volta sola**: sul registro c'e' tutto o non c'e' niente (mai un lotto
   * a meta' dopo la morte del processo). Lo stato che ne esce e' quello delle stesse modifiche fatte una alla volta con [set] nello stesso
   * istante del NAS (una chiave ripetuta: vale l'ultima, con l'ora dopo la precedente). Un lotto che non cambia niente non riscrive il
   * registro, a meno che l'ultimo salvataggio fosse fallito. I preferiti e i nascosti del lotto si portano nel catalogo dopo il salvataggio,
   * come per [set] (un secondo salvataggio toglie dalle chiavi "da portare" quelle arrivate: due in tutto, mai uno per voce).
   */
  override suspend fun setAll(changes: List<PersonalChange>) {
    if (changes.isEmpty()) return
    write(changes, always = false)
  }

  /** L'effetto di un flag sul catalogo: l'ultimo valore che il lotto da' alla chiave. */
  private class FlagEffect(val value: PersonalValue, val fingerprint: PersonalFingerprint?)

  private suspend fun write(changes: List<PersonalChange>, always: Boolean) {
    for (change in changes) require(!change.key.kind.reserved) { "Tipo di voce riservato." }
    lock.withLock {
      ensureLoaded()
      // 1. Le impronte che ne' il lotto ne' lo stato hanno si chiedono al catalogo PRIMA di toccare qualcosa: se il catalogo cade, niente e' cambiato.
      val fromCatalog = HashMap<PersonalKey, PersonalFingerprint?>()
      for (change in changes) {
        val key = change.key
        if (change.fingerprint != null || key in fromCatalog || current[key]?.fingerprint != null) continue
        fromCatalog[key] = projection.fingerprintOf(key)
      }

      // 2. In memoria, tutto insieme: da qui al salvataggio niente sospende e niente puo' fallire. Una sola ora del NAS per il lotto.
      val at = nasNow()
      var next: HashMap<PersonalKey, PersonalEntry>? = null
      var pendingChanged = false
      val flags = LinkedHashMap<PersonalKey, FlagEffect>()
      val others = ArrayList<PersonalEntry>()
      for (change in changes) {
        val key = change.key
        val value = change.value
        val known = (next ?: current)[key]
        val effective = pending[key]?.value ?: known?.value
        val fp = change.fingerprint ?: known?.fingerprint ?: fromCatalog[key]
        if (value != effective) {
          if (at == null) {
            // Senza ora del NAS: se si torna al valore che lo stato gia' ha, non c'e' piu' niente da datare.
            if (known?.value == value) pending.remove(key) else pending[key] = PendingChange(value, fp)
            pendingChanged = true
          } else {
            pending.remove(key)
            val map = next ?: HashMap(current).also { next = it }
            map[key] = PersonalEntry(key, causalTime(at, known), author, value, fp)
          }
        }
        if (key.kind == PersonalKind.MEDIA_FLAG) flags[key] = FlagEffect(value, fp) else others += PersonalEntry(key, known?.atNas ?: 0L, author, value, fp)
      }
      val changed = next != null || pendingChanged
      val dated = next
      if (dated != null) publishState(dated) else if (pendingChanged) emitEffective()

      // 3. Un salvataggio. L'effetto locale e' subito, anche se la modifica resta da datare: prima si salva (con le chiavi "da portare nel
      // catalogo"), poi si applica: se il processo muore o il catalogo fallisce in mezzo, al giro dopo `reproject` rifa' il passo.
      unprojected += flags.keys
      if (always || changed || flags.isNotEmpty() || unsaved) persist()

      // 4. Il catalogo.
      if (flags.isNotEmpty()) {
        val now = effectiveNow()
        var resolved = false
        for ((key, effect) in flags) {
          val entry = PersonalEntry(key, current[key]?.atNas ?: 0L, author, effect.value, effect.fingerprint)
          if (projection.apply(entry, now) != ProjectionResult.UNRESOLVED) {
            unprojected -= key
            resolved = true
          }
        }
        if (resolved) persist()
      }
      for (entry in others) projection.apply(entry)
    }
  }

  override suspend fun applyRemote(entries: List<PersonalEntry>): PersonalApplyResult = applyFrom(deviceId = "", entries = entries, mark = null)

  override suspend fun applyFrom(deviceId: String, entries: List<PersonalEntry>, mark: HeadMark?): PersonalApplyResult = lock.withLock {
    ensureLoaded()
    val next = HashMap(current)
    val changed = LinkedHashMap<PersonalKey, PersonalEntry>()
    for (entry in entries) {
      if (entry.key.kind.reserved) continue
      val existing = next[entry.key]
      if (existing == null || PersonalMerge.compare(entry, existing) > 0) {
        next[entry.key] = entry
        changed[entry.key] = entry
      }
    }
    // Quello che va nel catalogo: i flag dei media, mai sopra una modifica locale ancora da datare.
    val toProject = changed.values.filter { it.key.kind == PersonalKind.MEDIA_FLAG && it.key !in pending }
    if (changed.isNotEmpty()) publishState(next)
    if (mark != null && deviceId.isNotEmpty()) marks[deviceId] = mark
    toProject.forEach { unprojected += it.key }
    if (changed.isNotEmpty() || mark != null) persist()
    for (entry in toProject) {
      if (projection.apply(entry, effectiveNow()) != ProjectionResult.UNRESOLVED) unprojected -= entry.key
    }
    if (toProject.isNotEmpty()) persist()
    PersonalApplyResult(applied = changed.size, ignored = entries.size - changed.size)
  }

  override suspend fun effective(): Map<PersonalKey, PersonalEntry> = lock.withLock {
    ensureLoaded()
    effectiveNow()
  }

  /** Dal catalogo, attraverso la proiezione: niente stato dello store, quindi fuori dal lucchetto. */
  override suspend fun fingerprintOfMedia(mediaId: String): PersonalFingerprint? = projection.fingerprintOfMedia(mediaId)

  override suspend fun mediaWithFingerprint(fingerprint: PersonalFingerprint): MediaId? = projection.mediaWithFingerprint(fingerprint)

  override suspend fun undated(): List<PersonalKey> = lock.withLock {
    ensureLoaded()
    pending.keys.toList()
  }

  override suspend fun stamp(nasNowMs: Long) {
    lock.withLock {
      ensureLoaded()
      if (pending.isEmpty()) return
      val next = HashMap(current)
      for ((key, change) in pending) {
        next[key] = PersonalEntry(key, causalTime(nasNowMs, next[key]), author, change.value, change.fingerprint)
      }
      pending.clear()
      publishState(next)
      persist()
    }
  }

  override suspend fun marks(): Map<String, HeadMark> = lock.withLock {
    ensureLoaded()
    HashMap(marks)
  }

  override suspend fun purgeInvalid(rules: PersonalRules): Int = lock.withLock {
    ensureLoaded()
    // Le voci proprie non si toccano: se questo dispositivo e' revocato, il registro lo dice a tutti e lo sa gia' da se'.
    val dropped = current.values.filter { entry ->
      entry.author != author && PersonalValidity.check(entry, rules, inDeclaredPrivateArea = true).let {
        it == PersonalVerdict.AUTHOR_REVOKED || it == PersonalVerdict.AUTHOR_NOT_ALLOWED
      }
    }
    if (dropped.isEmpty()) return 0
    val next = HashMap(current)
    dropped.forEach {
      next.remove(it.key)
      unprojected -= it.key
    }
    publishState(next)
    persist()
    dropped.size
  }

  override suspend fun state(): Map<PersonalKey, PersonalEntry> = lock.withLock {
    ensureLoaded()
    current
  }

  override suspend fun published(): PersonalPublication? = lock.withLock {
    ensureLoaded()
    published
  }

  override suspend fun setPublished(publication: PersonalPublication) {
    lock.withLock {
      ensureLoaded()
      published = publication
      persist()
    }
  }

  override suspend fun reproject(): Int = lock.withLock {
    ensureLoaded()
    var changed = false
    for (key in unprojected.toList()) {
      // Cio' che il catalogo deve avere: la scelta dell'utente ancora da datare, altrimenti la voce dello stato unito.
      val change = pending[key]
      val entry = if (change != null) PersonalEntry(key, current[key]?.atNas ?: 0L, author, change.value, change.fingerprint) else current[key]
      if (entry == null) {
        unprojected -= key
        changed = true
      } else if (projection.apply(entry, effectiveNow()) != ProjectionResult.UNRESOLVED) {
        unprojected -= key
        changed = true
      }
    }
    if (changed) persist()
    unprojected.size
  }

  override suspend fun adoptLocal(): Int = lock.withLock {
    ensureLoaded()
    val at = nasNow()
    var next: HashMap<PersonalKey, PersonalEntry>? = null
    var adopted = 0
    for (flag in projection.localFlags()) {
      val key = PersonalKey(PersonalKind.MEDIA_FLAG, flag.mediaId, flag.field)
      if (key in pending) continue
      val known = current[key]
      val knownOn = (known?.value as? PersonalValue.Flag)?.on ?: false
      // Il catalogo e' d'accordo con lo stato unito, oppure la proiezione e' ancora in attesa (lo stato unito vince): niente.
      if (flag.on == knownOn || key in unprojected) continue
      val fingerprint = known?.fingerprint ?: projection.fingerprintOf(key)
      if (at == null) {
        pending[key] = PendingChange(PersonalValue.Flag(flag.on), fingerprint)
      } else {
        val map = next ?: HashMap(current).also { next = it }
        map[key] = PersonalEntry(key, causalTime(at, known), author, PersonalValue.Flag(flag.on), fingerprint)
      }
      adopted++
    }
    next?.let(::publishState)
    if (adopted > 0) {
      emitEffective()
      persist()
    }
    adopted
  }

  // --- Interni (sempre sotto [lock]) ---------------------------------------------------------------------------------------------

  private suspend fun ensureLoaded() {
    if (loaded) return
    val snapshot = ledger.load()
    current = HashMap(snapshot.entries)
    pending.putAll(snapshot.pending)
    unprojected.addAll(snapshot.unprojected)
    marks.putAll(snapshot.marks)
    published = snapshot.published
    state.value = current
    emitEffective()
    loaded = true
  }

  /** Lo stato unito con sopra le modifiche ancora da datare, che valgono subito e hanno l'ora piu' alta (vedi [effective]). */
  private fun effectiveNow(): Map<PersonalKey, PersonalEntry> {
    if (pending.isEmpty()) return current
    val out = HashMap(current)
    for ((key, change) in pending) out[key] = PersonalEntry(key, Long.MAX_VALUE, author, change.value, change.fingerprint)
    return out
  }

  private fun publishState(next: Map<PersonalKey, PersonalEntry>) {
    current = next
    state.value = next
    emitEffective()
  }

  /** Riemette [effectiveEntries]: lo stato unito, o (con modifiche da datare) lo stato unito con sopra le modifiche. Sempre sotto [lock]. */
  private fun emitEffective() {
    effectiveState.value = effectiveNow()
  }

  private suspend fun persist() {
    unsaved = true
    ledger.save(LedgerSnapshot(current, LinkedHashMap(pending), LinkedHashSet(unprojected), HashMap(marks), published))
    unsaved = false
  }

  /** L'ora di una modifica: quella del NAS, ma mai prima di cio' che si sapeva di quella chiave. */
  private fun causalTime(nasAt: Long, known: PersonalEntry?): Long = if (known == null) nasAt else maxOf(nasAt, known.atNas + 1)
}
