package dev.pampa.mycloudgallery.core.personal.testing

import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.personal.contract.PrePublishHook

// I fake della Fase 8 (P0, D30) in core-personal: per i test dell'interruttore (P7), della cancellazione (P8) e del banco (P12). In
// src/main/testing per i moduli a valle; mai nella release (tools/check.ps1).

/**
 * Le ultime teste personali dei dispositivi dell'account, scritte a mano: [put] dice che la testa di [device] (letta all'ora del NAS
 * [atNas]) ha [entry] per la sua chiave (o non la ha, con [forget]).
 */
class FakePersonalHeadsView : PersonalHeadsView {
  private val heads = HashMap<String, MutableMap<PersonalKey, PersonalEntry>>()
  private val times = HashMap<String, Long>()

  fun put(device: String, atNas: Long, entry: PersonalEntry): FakePersonalHeadsView = synchronized(this) {
    heads.getOrPut(device) { HashMap() }[entry.key] = entry
    times[device] = atNas
    this
  }

  /** La testa di [device] non contiene piu' [key] (ma il dispositivo c'e'). */
  fun forget(device: String, atNas: Long, key: PersonalKey): FakePersonalHeadsView = synchronized(this) {
    heads.getOrPut(device) { HashMap() }.remove(key)
    times[device] = atNas
    this
  }

  override suspend fun latestValueOf(key: PersonalKey): Map<String, HeadValue> = synchronized(this) {
    times.keys.associateWith { device -> HeadValue(heads[device]?.get(key), times.getValue(device)) }
  }
}

/**
 * Un gancio che registra gli stati che ha visto e risponde con [answer] (di serie "si'"). Per provare che il motore lo chiama **prima** di
 * ogni pubblicazione e che con `Hold` quel giro non pubblica (P8).
 */
class RecordingPrePublishHook(@Volatile var answer: PrePublish = PrePublish.Proceed) : PrePublishHook {
  val seen: MutableList<Map<PersonalKey, PersonalEntry>> = java.util.Collections.synchronizedList(mutableListOf())

  override suspend fun beforePublish(state: Map<PersonalKey, PersonalEntry>): PrePublish {
    seen += state
    return answer
  }
}
