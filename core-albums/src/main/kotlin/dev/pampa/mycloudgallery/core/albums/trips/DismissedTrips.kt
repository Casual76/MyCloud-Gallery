package dev.pampa.mycloudgallery.core.albums.trips

import dev.pampa.mycloudgallery.core.albums.contract.TripId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// "NON MI INTERESSA" (docs/fase7-progetto.md D20, D4): una proposta scartata non torna su nessun dispositivo dell'account. La scelta e' una voce
// dei dati personali (`SETTING`, campo `trip_dismissed:<impronta>`), quindi viaggia con la stessa sincronizzazione dei preferiti: firmata
// dalla testa `personal`, nell'area privata, fusa per `(atNas, autore)`. Una voce per proposta: due dispositivi che scartano proposte diverse
// le tengono entrambe. **Nessuna coordinata e nessun luogo**: l'impronta e' un hash di tipo, giorni e id dei luoghi (`TripId`).

internal interface DismissedTrips {
  /** Le proposte scartate (da questo dispositivo o da un altro dello stesso account). */
  val dismissed: StateFlow<Set<TripId>>

  suspend fun dismiss(id: TripId)
}

/**
 * Le proposte scartate sui dati personali. Si legge [PersonalStore.effectiveEntries]: la scelta vale subito su questo dispositivo anche fuori
 * casa, senza l'ora del NAS (la modifica prende l'ora alla prima sincronizzazione e da allora viaggia). Una voce che non e' un'impronta valida,
 * che non e' un'impostazione o che non e' un flag acceso non scarta niente: nel dubbio una proposta resta.
 */
internal class PersonalDismissedTrips(private val store: PersonalStore, scope: CoroutineScope) : DismissedTrips {

  override val dismissed: StateFlow<Set<TripId>> = store.effectiveEntries
    .map { dismissedIn(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, dismissedIn(store.effectiveEntries.value))

  override suspend fun dismiss(id: TripId) {
    store.set(keyOf(id), PersonalValue.Flag(true))
  }

  private fun keyOf(id: TripId): PersonalKey = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.tripDismissed(id.value))

  private fun dismissedIn(entries: Map<PersonalKey, PersonalEntry>): Set<TripId> {
    val out = LinkedHashSet<TripId>()
    for ((key, entry) in entries) {
      if (key.kind != PersonalKind.SETTING || key.subject != PersonalFields.SETTINGS_SUBJECT) continue
      val raw = PersonalFields.tripDismissedId(key.field) ?: continue
      if ((entry.value as? PersonalValue.Flag)?.on != true) continue
      val id = runCatching { TripId(raw) }.getOrNull() ?: continue
      out += id
    }
    return out
  }
}
