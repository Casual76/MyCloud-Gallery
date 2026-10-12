package dev.pampa.mycloudgallery.core.rules.traffic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dev.pampa.mycloudgallery.core.rules.SystemClockFollowingZone
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * I contatori di traffico per attivita' e per rete (piano 1.6): alimentano i tetti mensili, la
 * Diagnostica ("consumi del mese") e le stime.
 *
 * Chi trasferisce dati registra QUANTO e SU QUALE rete li ha trasferiti, in tempo reale (a ogni blocco o
 * a ogni file), non alla fine: se il processo muore, il traffico gia' passato deve contare.
 */
interface TrafficLedger {

  /** Aggiunge [bytes] al ciclo in corso. Valori zero o negativi non fanno nulla. */
  suspend fun record(source: TrafficSource, network: TrafficNetwork, bytes: Long)

  /** Il traffico del ciclo in corso (vuoto se e' appena cominciato un nuovo mese). */
  suspend fun currentUsage(): CycleUsage

  /** Come [currentUsage], ma aggiornato a ogni registrazione. */
  fun observeCurrentUsage(): Flow<CycleUsage>

  /** Lo storico: il ciclo in corso e quelli passati, dal piu' recente. */
  suspend fun history(): List<CycleUsage>
}

/**
 * Implementazione su DataStore Preferences: una chiave `traffic.<ciclo>.<fonte>.<rete>` per ogni
 * combinazione, con un `edit` atomico a ogni registrazione.
 *
 * L'azzeramento mensile non cancella niente: il ciclo corrente si ricava dalla data (`clock`), quindi
 * a mezzanotte del primo del mese i contatori "ripartono da zero" perche' il ciclo nuovo non ha ancora
 * chiavi. I cicli vecchi restano nello storico per [keepCycles] mesi, poi vengono potati.
 *
 * @param cycleStartDay giorno di inizio del ciclo (1 = mese di calendario), letto a ogni uso.
 */
class DataStoreTrafficLedger(
  private val dataStore: DataStore<Preferences>,
  private val clock: Clock = SystemClockFollowingZone,
  private val cycleStartDay: () -> Int = { 1 },
  private val keepCycles: Int = DEFAULT_KEEP_CYCLES,
) : TrafficLedger {

  init {
    require(keepCycles >= 1) { "Va tenuto almeno il ciclo in corso." }
  }

  override suspend fun record(source: TrafficSource, network: TrafficNetwork, bytes: Long) {
    if (bytes <= 0L) return
    val cycle = currentCycle()
    val key = longPreferencesKey(keyName(cycle.id, source, network))
    dataStore.edit { prefs ->
      prefs[key] = saturatingAdd(prefs[key] ?: 0L, bytes)
      prune(prefs, cycle)
    }
  }

  override suspend fun currentUsage(): CycleUsage = usageOf(dataStore.data.first(), currentCycle())

  override fun observeCurrentUsage(): Flow<CycleUsage> =
    dataStore.data.map { prefs -> usageOf(prefs, currentCycle()) }

  override suspend fun history(): List<CycleUsage> {
    val prefs = dataStore.data.first()
    val startDay = cycleStartDay()
    val current = currentCycle()
    val byCycle = parse(prefs)
    val ids = (byCycle.keys + current.id).sortedDescending()
    return ids.mapNotNull { id ->
      val cycle = BillingCycle.parseId(id, startDay) ?: return@mapNotNull null
      CycleUsage(cycle, byCycle[id].orEmpty())
    }
  }

  private fun currentCycle(): BillingCycle =
    BillingCycle.containing(LocalDate.now(clock), cycleStartDay().coerceIn(1, BillingCycle.MAX_START_DAY))

  private fun usageOf(prefs: Preferences, cycle: BillingCycle): CycleUsage =
    CycleUsage(cycle, parse(prefs)[cycle.id].orEmpty())

  /** Tutte le chiavi dei contatori, raggruppate per id di ciclo. Le chiavi illeggibili si ignorano. */
  private fun parse(prefs: Preferences): Map<String, Map<TrafficKey, Long>> {
    val result = HashMap<String, MutableMap<TrafficKey, Long>>()
    for ((key, value) in prefs.asMap()) {
      if (value !is Long) continue
      val parsed = parseKeyName(key.name) ?: continue
      result.getOrPut(parsed.first) { HashMap() }[parsed.second] = value
    }
    return result
  }

  private fun prune(prefs: MutablePreferences, current: BillingCycle) {
    val oldestKept = current.month.minusMonths((keepCycles - 1).toLong())
    val oldestId = BillingCycle(oldestKept, current.startDay).id
    val stale = prefs.asMap().keys.filter { key ->
      val id = parseKeyName(key.name)?.first
      id != null && id < oldestId // "yyyy-MM" si ordina come le date
    }
    stale.forEach { prefs.remove(it) }
  }

  private fun keyName(cycleId: String, source: TrafficSource, network: TrafficNetwork) =
    "$KEY_PREFIX$cycleId.${source.name}.${network.name}"

  private fun parseKeyName(name: String): Pair<String, TrafficKey>? {
    if (!name.startsWith(KEY_PREFIX)) return null
    val parts = name.removePrefix(KEY_PREFIX).split('.')
    if (parts.size != 3) return null
    val source = TrafficSource.entries.firstOrNull { it.name == parts[1] } ?: return null
    val network = TrafficNetwork.entries.firstOrNull { it.name == parts[2] } ?: return null
    return parts[0] to TrafficKey(source, network)
  }

  companion object {
    /** Un anno di storico. */
    const val DEFAULT_KEEP_CYCLES = 12
    private const val KEY_PREFIX = "traffic."
  }
}
