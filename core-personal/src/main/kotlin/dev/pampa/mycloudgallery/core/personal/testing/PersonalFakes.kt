package dev.pampa.mycloudgallery.core.personal.testing

import dev.pampa.mycloudgallery.core.personal.contract.PersonalApplyResult
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalMerge
import dev.pampa.mycloudgallery.core.personal.contract.PersonalRules
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStop
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSync
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncReport
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStatus
import dev.pampa.mycloudgallery.core.personal.contract.PersonalUnavailable
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValidity
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalVerdict
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I fake di core-personal (P0-B): in src/main per i test di P13, P14, P16 e :app. Mai nella release (tools/check.ps1).

/**
 * Lo store locale in memoria di UN dispositivo ([author] = il suo id). Una modifica prende l'ora del NAS di [nasNow] se c'e', altrimenti
 * resta "da datare" (§2.9) e si data con [stamp].
 */
class InMemoryPersonalStore(
  private val author: String,
  private val nasNow: () -> Long? = { null },
) : PersonalStore {

  private val state = MutableStateFlow<Map<PersonalKey, PersonalEntry>>(emptyMap())
  override val entries: StateFlow<Map<PersonalKey, PersonalEntry>> = state

  /** Le modifiche fatte senza ora del NAS: il valore c'e', l'ora no. */
  private val pending = LinkedHashMap<PersonalKey, Pair<PersonalValue, PersonalFingerprint?>>()

  override suspend fun get(key: PersonalKey): PersonalEntry? = state.value[key]

  override suspend fun set(key: PersonalKey, value: PersonalValue, fingerprint: PersonalFingerprint?) {
    val at = nasNow()
    if (at == null) {
      pending[key] = value to fingerprint
    } else {
      pending.remove(key)
      state.value = PersonalMerge.merge(state.value, listOf(PersonalEntry(key, at, author, value, fingerprint)))
    }
  }

  override suspend fun applyRemote(entries: List<PersonalEntry>): PersonalApplyResult {
    val before = state.value
    val after = PersonalMerge.merge(before, entries)
    state.value = after
    val applied = entries.count { after[it.key] == it && before[it.key] != it }
    return PersonalApplyResult(applied, entries.size - applied)
  }

  override suspend fun undated(): List<PersonalKey> = pending.keys.toList()

  override suspend fun effective(): Map<PersonalKey, PersonalEntry> {
    val out = HashMap(state.value)
    for ((key, v) in pending) out[key] = PersonalEntry(key, Long.MAX_VALUE, author, v.first, v.second)
    return out
  }

  override suspend fun stamp(nasNowMs: Long) {
    val dated = pending.map { (key, v) -> PersonalEntry(key, nasNowMs, author, v.first, v.second) }
    pending.clear()
    state.value = PersonalMerge.merge(state.value, dated)
  }
}

/** Le impostazioni personali in memoria. */
class InMemoryPersonalSettings(
  showAlbumCovers: Boolean = false,
  exclusions: Set<SharePath> = emptySet(),
) : PersonalSettings {
  private val covers = MutableStateFlow(showAlbumCovers)
  private val excluded = MutableStateFlow(exclusions)

  override val showAlbumCovers: StateFlow<Boolean> = covers

  override suspend fun setShowAlbumCovers(show: Boolean) {
    covers.value = show
  }

  override val privateAreaExclusions: StateFlow<Set<SharePath>> = excluded

  override suspend fun setPrivateAreaExclusions(folders: Set<SharePath>) {
    excluded.value = folders
  }
}

/**
 * Il NAS dei dati personali: per ogni account, l'area privata (un elenco di voci per dispositivo) e, per le prove ostili, l'area
 * **comune**, dove le voci non devono valere mai (C19). [nasNow] e' l'ora del NAS.
 */
class FakePersonalCloud(val nasNow: () -> Long) {
  /** account -> dispositivo -> voci pubblicate nella sua area privata. */
  val privateAreas: MutableMap<String, MutableMap<String, List<PersonalEntry>>> = HashMap()

  /** Voci messe nell'area comune (da un dispositivo compromesso o da un errore): ignorate. */
  val commonArea: MutableList<PersonalEntry> = ArrayList()

  /** account -> dispositivi approvati. */
  val approved: MutableMap<String, MutableSet<String>> = HashMap()

  val revoked: MutableSet<String> = HashSet()

  fun approve(account: String, device: String) {
    approved.getOrPut(account) { HashSet() } += device
  }

  fun rules(account: String): PersonalRules =
    PersonalRules(nasNow(), approvedAuthors = approved[account].orEmpty(), revokedAuthors = revoked)
}

/**
 * La sincronizzazione di riferimento di UN dispositivo ([deviceId], account [account]) su un [FakePersonalCloud]: pubblica lo stato
 * unito nella propria area privata, legge quelle degli altri dispositivi dello **stesso account**, scarta cio' che non vale e fonde il
 * resto. Le voci nell'area comune non entrano mai.
 */
class FakePersonalSync(
  private val account: String,
  private val deviceId: String,
  private val store: PersonalStore,
  private val cloud: FakePersonalCloud,
) : PersonalSync {

  private val state = MutableStateFlow<PersonalSyncStatus>(PersonalSyncStatus.Idle)
  override val status: StateFlow<PersonalSyncStatus> = state

  /** Falso = il NAS non risponde. */
  var reachable: Boolean = true

  /** Il motivo per cui non si pubblica, se c'e' (area in sola lettura, congelamento...): si legge soltanto. */
  var publishBlocked: PersonalUnavailable? = null

  override suspend fun sync(budget: Duration): PersonalSyncReport {
    if (!reachable) {
      state.value = PersonalSyncStatus.Unavailable(PersonalUnavailable.NAS_UNREACHABLE)
      return PersonalSyncReport(0, 0, emptyMap(), store.undated().size, PersonalStop.UNAVAILABLE)
    }
    state.value = PersonalSyncStatus.Syncing
    val now = cloud.nasNow()
    store.stamp(now)
    val rules = cloud.rules(account)
    val rejected = HashMap<PersonalVerdict, Int>()

    // Legge gli altri dispositivi dello stesso account (area privata dichiarata).
    val valid = ArrayList<PersonalEntry>()
    for ((device, entries) in cloud.privateAreas[account].orEmpty()) {
      if (device == deviceId) continue
      for (entry in entries) {
        val verdict = PersonalValidity.check(entry, rules, inDeclaredPrivateArea = true)
        if (verdict == PersonalVerdict.VALID) valid += entry else rejected.merge(verdict, 1, Int::plus)
      }
    }
    // Le voci nell'area comune non valgono, da chiunque vengano.
    for (entry in cloud.commonArea) {
      rejected.merge(PersonalValidity.check(entry, rules, inDeclaredPrivateArea = false), 1, Int::plus)
    }
    val applied = store.applyRemote(valid).applied

    // Pubblica lo stato unito.
    var published = 0
    if (publishBlocked == null) {
      val mine = store.entries.value.values.toList()
      cloud.privateAreas.getOrPut(account) { HashMap() }[deviceId] = mine
      published = mine.size
    }
    state.value = publishBlocked?.let { PersonalSyncStatus.Unavailable(it) } ?: PersonalSyncStatus.UpToDate(now)
    return PersonalSyncReport(published, applied, rejected, store.undated().size, PersonalStop.COMPLETE)
  }
}

/** Una voce di preferito, scritta come la UI la scrive: `MEDIA_FLAG`, campo `favorite`. */
fun favorite(mediaId: String): PersonalKey = PersonalKey(PersonalKind.MEDIA_FLAG, mediaId, PersonalFields.FAVORITE)

/** Una voce di nascosto. */
fun hidden(mediaId: String): PersonalKey = PersonalKey(PersonalKind.MEDIA_FLAG, mediaId, PersonalFields.HIDDEN)
