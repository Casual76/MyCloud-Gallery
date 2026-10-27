package dev.pampa.mycloudgallery.core.personal.testing

import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Il fake della voce `faces` (Fase 8, P7, §5.2): per l'interruttore di core-faces e per le schermate, senza ledger ne' NAS. Ha la stessa
// semantica di `SyncedFacesSettings` nelle cose che contano (vale subito anche senza l'ora, "da datare" finche' non si sincronizza, l'ultima
// scrittura vince, la voce sconosciuta non si riscrive) e passa la stessa suite di contratto (`FacesPersonalSettingsContract`).

/**
 * Il registro condiviso da piu' dispositivi finti: una sola voce `faces`, l'ultima scrittura vince. L'ordine e' quello di arrivo al registro
 * (un contatore): una scrittura "da datare" prende il suo posto quando il dispositivo si sincronizza, come l'ora del NAS alla prima
 * sincronizzazione.
 */
class FacesRegister {
  private var value: PersonalValue? = null
  private var author: String = ""
  private var version: Long = 0L

  /** Scrive [raw] (null = lapide) come ultima voce, di [by]. */
  @Synchronized
  fun publish(raw: PersonalValue?, by: String) {
    value = raw
    author = by
    version++
  }

  @Synchronized
  fun read(): PersonalValue? = value

  /** Chi ha scritto l'ultima voce ("attivato da ..."). */
  @Synchronized
  fun lastAuthor(): String = author

  /** Quante scritture ha visto il registro. */
  @Synchronized
  fun writes(): Long = version
}

/**
 * La voce `faces` di UN dispositivo finto sopra un [FacesRegister]. Online ([online], di serie) [set] scrive subito nel registro; senza l'ora del
 * NAS (`online = false`) la scelta vale qui subito ma resta "da datare" ([syncPending]) fino a [sync], che la porta nel registro e rilegge quello
 * che c'e' (anche le scelte degli altri). [pull] rilegge senza scrivere. Il registro si legge con le regole di [FacesSettingCodec].
 */
class InMemoryFacesPersonalSettings(
  val register: FacesRegister = FacesRegister(),
  val deviceId: String = "dispositivo",
) : FacesPersonalSettings {

  @Volatile var online: Boolean = true

  private val lock = Any()
  private var seen: PersonalValue? = register.read()
  private var pending: PersonalValue? = null
  private var hasPending: Boolean = false

  private val settingState = MutableStateFlow(valueNow())
  override val setting: StateFlow<FacesSetting?> = settingState

  private val pendingState = MutableStateFlow(false)
  override val syncPending: StateFlow<Boolean> = pendingState

  override fun current(): FacesSetting? = synchronized(lock) { valueNow() }

  override suspend fun set(setting: FacesSetting): Boolean = synchronized(lock) {
    if (valueNow() == FacesSetting.Unknown) {
      require(setting != FacesSetting.Unknown) { "Una voce sconosciuta non si scrive." }
      return false
    }
    val raw = FacesSettingCodec.valueOf(setting)
    if (online) {
      register.publish(raw, deviceId)
      seen = raw
      hasPending = false
      pending = null
    } else {
      pending = raw
      hasPending = true
    }
    publishState()
    true
  }

  /** Rilegge il registro (il giro dei dati personali che porta le scelte degli altri). Una scelta "da datare" resta sopra. */
  fun pull() = synchronized(lock) {
    seen = register.read()
    publishState()
  }

  /** La prima sincronizzazione con l'ora del NAS: la scelta da datare entra nel registro, poi si rilegge. */
  fun sync() = synchronized(lock) {
    if (hasPending) {
      register.publish(pending, deviceId)
      hasPending = false
      pending = null
    }
    seen = register.read()
    publishState()
  }

  /** Una voce scritta a mano da "un altro dispositivo" (per una forma che questa versione non capisce, E-F12). */
  fun injectRemote(raw: PersonalValue?, from: String = "altro") {
    register.publish(raw, from)
    pull()
  }

  private fun valueNow(): FacesSetting? = FacesSettingCodec.parse(if (hasPending) pending else seen)

  private fun publishState() {
    settingState.value = valueNow()
    pendingState.value = hasPending
  }
}
