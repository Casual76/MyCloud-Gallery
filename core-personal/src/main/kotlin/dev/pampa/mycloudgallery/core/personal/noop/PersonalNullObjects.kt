package dev.pampa.mycloudgallery.core.personal.noop

import dev.pampa.mycloudgallery.core.personal.contract.PersonalApplyResult
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStop
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSync
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncReport
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStatus
import dev.pampa.mycloudgallery.core.personal.contract.PersonalUnavailable
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object di core-personal (release-safe, fuori da `testing`): nessun dato personale sincronizzato. Falliscono chiusi: niente
// parte verso il NAS, le impostazioni restano ai valori di fabbrica e non si salvano.

/** Nessuna sincronizzazione: nessuna area privata. */
object NoPersonalSync : PersonalSync {
  override val status: StateFlow<PersonalSyncStatus> = MutableStateFlow(PersonalSyncStatus.Unavailable(PersonalUnavailable.NO_PRIVATE_AREA))

  override suspend fun sync(budget: Duration): PersonalSyncReport =
    PersonalSyncReport(published = 0, applied = 0, rejected = emptyMap(), stillUndated = 0, stoppedBy = PersonalStop.UNAVAILABLE)
}

/** Nessuno stato: non ricorda niente. */
object NoPersonalStore : PersonalStore {
  override val entries: StateFlow<Map<PersonalKey, PersonalEntry>> = MutableStateFlow(emptyMap())

  override suspend fun get(key: PersonalKey): PersonalEntry? = null

  override suspend fun set(key: PersonalKey, value: PersonalValue, fingerprint: PersonalFingerprint?) = Unit

  override suspend fun applyRemote(entries: List<PersonalEntry>): PersonalApplyResult = PersonalApplyResult(applied = 0, ignored = entries.size)

  override suspend fun undated(): List<PersonalKey> = emptyList()

  override suspend fun stamp(nasNowMs: Long) = Unit
}

/** I valori di fabbrica, che non si salvano. */
object NoPersonalSettings : PersonalSettings {
  override val showAlbumCovers: StateFlow<Boolean> = MutableStateFlow(false)

  override suspend fun setShowAlbumCovers(show: Boolean) = Unit

  override val privateAreaExclusions: StateFlow<Set<SharePath>> = MutableStateFlow(emptySet())

  override suspend fun setPrivateAreaExclusions(folders: Set<SharePath>) = Unit
}
