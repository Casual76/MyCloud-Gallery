package dev.pampa.mycloudgallery.core.personal.settings

import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalLimits
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.TrustViolation
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Le impostazioni personali che viaggiano fra i miei dispositivi (Q9 (a)): "Mostra le copertine degli album" e le cartelle
 * dell'area privata escluse dalla scansione. **Non** i preset, la qualita' delle anteprime, i ruoli: dipendono dal dispositivo
 * (batteria, spazio) e restano nelle impostazioni locali.
 *
 * Sono voci dello stesso stato unito dei preferiti: l'ultima modifica vince (ora del NAS) e vale su tutti i dispositivi dello
 * stesso account. Le cartelle escluse sono **una voce per cartella** ([PersonalFields.privateExclusion]): due dispositivi che
 * aggiungono cartelle diverse le tengono entrambe, e la cartella tolta su uno si toglie ovunque.
 *
 * Un percorso piu' lungo di [PersonalLimits.MAX_TEXT] caratteri non entra in una voce: non si sincronizza e non e' una esclusione
 * (una cartella a oltre 1.000 caratteri di percorso non esce da una scelta dell'utente). I percorsi letti dallo stato si
 * rivalidano come `SharePath` e devono corrispondere al campo che li porta: una voce ostile non diventa un'esclusione.
 */
class SyncedPersonalSettings(
  private val store: PersonalStore,
  scope: CoroutineScope,
) : PersonalSettings {

  override val showAlbumCovers: StateFlow<Boolean> = store.entries
    .map { coversOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, coversOf(store.entries.value))

  override suspend fun setShowAlbumCovers(show: Boolean) {
    store.set(COVERS, PersonalValue.Flag(show))
  }

  override val privateAreaExclusions: StateFlow<Set<SharePath>> = store.entries
    .map { exclusionsOf(it).values.toSet() }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, exclusionsOf(store.entries.value).values.toSet())

  override suspend fun setPrivateAreaExclusions(folders: Set<SharePath>) {
    val wanted = folders.filter { it.value.length <= PersonalLimits.MAX_TEXT }.associateBy { PersonalFields.privateExclusion(it) }
    val current = exclusionsOf(store.entries.value)
    for ((field, folder) in wanted) {
      if (field !in current) store.set(exclusionKey(field), PersonalValue.Text(folder.value))
    }
    for (field in current.keys) {
      if (field !in wanted) store.set(exclusionKey(field), PersonalValue.Removed)
    }
  }

  private fun coversOf(entries: Map<PersonalKey, PersonalEntry>): Boolean =
    (entries[COVERS]?.value as? PersonalValue.Flag)?.on ?: false

  /** Campo -> cartella, per le voci con un testo che e' un percorso valido e che sta sotto il suo campo. */
  private fun exclusionsOf(entries: Map<PersonalKey, PersonalEntry>): Map<String, SharePath> {
    val out = LinkedHashMap<String, SharePath>()
    for ((key, entry) in entries) {
      if (key.kind != PersonalKind.SETTING || key.subject != PersonalFields.SETTINGS_SUBJECT) continue
      if (!PersonalFields.isPrivateExclusion(key.field)) continue
      val text = (entry.value as? PersonalValue.Text)?.text ?: continue
      val path = try {
        SharePath.parse(text)
      } catch (e: TrustViolation) {
        continue
      }
      if (PersonalFields.privateExclusion(path) == key.field) out[key.field] = path
    }
    return out
  }

  private fun exclusionKey(field: String): PersonalKey = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, field)

  private companion object {
    val COVERS = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_SHOW_ALBUM_COVERS)
  }
}
