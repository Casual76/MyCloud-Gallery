package dev.pampa.mycloudgallery.core.personal.settings

import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SearchOrderSetting
import dev.pampa.mycloudgallery.core.personal.contract.SearchPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.TextReadingChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Le impostazioni personali della ricerca (Fase 6, P8, D41, D48; risposte Q4, Q10, Q13), sincronizzate fra i dispositivi dello stesso
 * account come le altre (l'ultima modifica vince con l'ora del NAS):
 *
 * - **`text_reading`**: [PersonalValue.Number], 0 = spenta, n > 0 = attiva con la versione n del testo della pagina di attivazione che
 *   l'utente ha letto. Voce assente, valore che non e' un numero positivo (anche negativo o enorme) = **spenta**: nel dubbio ML Kit non
 *   parte. L'esclusione di un solo dispositivo non e' una voce: e' locale.
 * - **`search_order`**: [PersonalValue.Text], `recent` o `relevant`; assente o sconosciuto (una versione piu' nuova) = "Recenti".
 *
 * **Valgono subito, anche senza l'ora del NAS.** Si legge [PersonalStore.effectiveEntries]: lo stato unito con sopra le modifiche locali
 * ancora "da datare" (fuori casa, senza VPN e senza un accesso SMB nel processo la modifica non ha l'ora e non e' nello stato che si
 * pubblica). Con `entries` "Spegni" della lettura del testo non spegneva finche' il telefono non tornava a casa (R6 della revisione di
 * sicurezza della Fase 6): la promessa "da quel momento ML Kit non parte piu'" deve valere qui, adesso. La sincronizzazione con gli altri
 * dispositivi viene dopo: la modifica prende l'ora del NAS alla prima sincronizzazione ([PersonalStore.stamp]) e conserva l'ordine.
 *
 * File a parte da [SyncedPersonalSettings] e da [SyncedMotionSettings]: le tre implementazioni non si toccano.
 */
class SyncedSearchSettings(
  private val store: PersonalStore,
  scope: CoroutineScope,
) : SearchPersonalSettings {

  override val textReading: StateFlow<TextReadingChoice> = store.effectiveEntries
    .map { readingOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, readingOf(store.effectiveEntries.value))

  override suspend fun setTextReading(choice: TextReadingChoice) {
    store.set(READING, TextReadingChoice.toValue(choice))
  }

  override val searchOrder: StateFlow<SearchOrderSetting> = store.effectiveEntries
    .map { orderOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, orderOf(store.effectiveEntries.value))

  override suspend fun setSearchOrder(order: SearchOrderSetting) {
    store.set(ORDER, PersonalValue.Text(order.code))
  }

  private fun readingOf(entries: Map<PersonalKey, PersonalEntry>): TextReadingChoice =
    TextReadingChoice.fromValue(entries[READING]?.value)

  private fun orderOf(entries: Map<PersonalKey, PersonalEntry>): SearchOrderSetting =
    SearchOrderSetting.fromCode((entries[ORDER]?.value as? PersonalValue.Text)?.text)

  private companion object {
    val READING = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_TEXT_READING)
    val ORDER = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_SEARCH_ORDER)
  }
}
