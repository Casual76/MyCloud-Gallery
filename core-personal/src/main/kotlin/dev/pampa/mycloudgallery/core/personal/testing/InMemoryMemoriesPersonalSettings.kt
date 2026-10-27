package dev.pampa.mycloudgallery.core.personal.testing

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesChoices
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import dev.pampa.mycloudgallery.core.personal.contract.MemoryDays
import dev.pampa.mycloudgallery.core.personal.contract.MemoryLessLevel
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Il fake delle scelte sui ricordi (Fase 9, P0, §5.2): per core-memories (P6, P8), le schermate (P9) e le notifiche (P10), senza ledger ne'
// NAS. Ha la semantica di `SyncedMemoriesSettings` nelle cose che contano (vale subito anche senza l'ora, "da datare" finche' non si
// sincronizza, l'ultima scrittura vince, la lettura e' quella di `MemoriesRecords`) e passa la stessa suite (`MemoriesPersonalSettingsContract`).

/**
 * Il registro dell'account condiviso da piu' dispositivi finti: per ogni chiave l'ultima voce arrivata (l'ordine d'arrivo fa da ora del NAS,
 * come nel registro dei volti). Le voci si possono anche scrivere a mano "da un altro dispositivo" ([inject]): una forma che questa versione
 * non capisce, una lapide.
 */
class MemoriesRegister {
  private val values = LinkedHashMap<PersonalKey, PersonalValue>()
  private var clock = 1L
  private val stamps = HashMap<PersonalKey, Long>()

  @Synchronized
  fun publish(key: PersonalKey, value: PersonalValue) {
    values[key] = value
    stamps[key] = clock++
  }

  @Synchronized
  fun inject(key: PersonalKey, value: PersonalValue) = publish(key, value)

  /** Lo stato come voci (l'autore e' quello del registro: la lettura non lo guarda). */
  @Synchronized
  fun entries(author: String): Map<PersonalKey, PersonalEntry> =
    values.mapValues { (key, value) -> PersonalEntry(key, stamps.getValue(key), author, value) }

  /** Le voci dei ricordi che non sono lapidi (per le prove "nessun nome, nessun titolo"). */
  @Synchronized
  fun live(): Map<PersonalKey, PersonalValue> = values.filterValues { it != PersonalValue.Removed }

  /** Quante scritture ha visto il registro. */
  @Synchronized
  fun writes(): Long = clock - 1
}

/**
 * Le scelte di UN dispositivo finto sopra un [MemoriesRegister]. Online ([online], di serie) ogni scrittura va subito nel registro; senza
 * l'ora del NAS (`online = false`) vale qui subito ma resta "da datare" fino a [sync], che la porta nel registro e rilegge le scelte degli
 * altri. [pull] rilegge senza scrivere.
 */
class InMemoryMemoriesPersonalSettings(
  val register: MemoriesRegister = MemoriesRegister(),
) : MemoriesPersonalSettings {

  @Volatile var online: Boolean = true

  private val author = TrustIds.random()
  private val lock = Any()
  private var seenRegister: Map<PersonalKey, PersonalEntry> = register.entries(author)
  private val pending = LinkedHashMap<PersonalKey, PersonalValue>()

  private val state = MutableStateFlow(readNow())
  override val choices: StateFlow<MemoriesChoices> = state

  /** Le scelte fatte qui e non ancora portate nel registro. */
  val pendingCount: Int get() = synchronized(lock) { pending.size }

  override fun current(): MemoriesChoices = synchronized(lock) { readNow() }

  override suspend fun markSeen(memoryId: String, day: Int) {
    val key = MemoriesRecords.seenKey(memoryId)
    val value = MemoriesRecords.seenValue(day)
    synchronized(lock) {
      val now = readNow().seen[memoryId]
      if (now != null && now >= day) return
      write(key, value)
    }
  }

  override suspend fun setDismissed(memoryId: String, dismissed: Boolean) = synchronized(lock) {
    write(MemoriesRecords.dismissedKey(memoryId), if (dismissed) PersonalValue.Flag(true) else PersonalValue.Removed)
  }

  override suspend fun setLess(person: PersonId, level: MemoryLessLevel?) = synchronized(lock) {
    write(MemoriesRecords.lessKey(person), MemoriesRecords.lessValue(level))
  }

  override suspend fun setNeverDays(days: MemoryDays, never: Boolean) = synchronized(lock) {
    write(MemoriesRecords.neverKey(days), if (never) PersonalValue.Flag(true) else PersonalValue.Removed)
  }

  override suspend fun setPeopleMemories(on: Boolean) = synchronized(lock) {
    write(MemoriesRecords.peopleKey, PersonalValue.Flag(on))
  }

  override suspend fun dropAllPeopleChoices(): Int = synchronized(lock) {
    val keys = effective().filter { (key, entry) ->
      PersonalFields.memoriesLessPersonOf(key.field) != null && entry.value != PersonalValue.Removed
    }.keys
    for (key in keys) write(key, PersonalValue.Removed)
    keys.size
  }

  /** Rilegge il registro (il giro dei dati personali). Le scelte "da datare" restano sopra. */
  fun pull() = synchronized(lock) {
    seenRegister = register.entries(author)
    state.value = readNow()
  }

  /** La prima sincronizzazione con l'ora del NAS: le scelte da datare entrano nel registro, poi si rilegge. */
  fun sync() = synchronized(lock) {
    for ((key, value) in pending) register.publish(key, value)
    pending.clear()
    seenRegister = register.entries(author)
    state.value = readNow()
  }

  private fun write(key: PersonalKey, value: PersonalValue) {
    if (online) {
      pending.remove(key)
      register.publish(key, value)
      seenRegister = register.entries(author)
    } else {
      pending[key] = value
    }
    state.value = readNow()
  }

  private fun effective(): Map<PersonalKey, PersonalEntry> {
    if (pending.isEmpty()) return seenRegister
    val out = HashMap(seenRegister)
    for ((key, value) in pending) out[key] = PersonalEntry(key, Long.MAX_VALUE, author, value)
    return out
  }

  private fun readNow(): MemoriesChoices = MemoriesRecords.read(effective())
}
