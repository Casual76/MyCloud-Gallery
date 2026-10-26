package dev.pampa.mycloudgallery.core.personal.settings

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

/**
 * L'ordine e la visibilita' delle sezioni delle Raccolte (Fase 7, P11, D33, E-L14), come impostazioni personali **sincronizzate** fra i
 * dispositivi dello stesso account (l'ultima modifica vince con l'ora del NAS), come [SyncedSearchSettings]:
 *
 * - **`collections_order`**: [PersonalValue.Text], i codici delle sezioni separati da virgola, nell'ordine scelto. Voce assente, lapide o
 *   un valore che non e' un testo = **nessun ordine salvato** (vale quello predefinito). I codici non validi e i doppioni si scartano
 *   (il primo vale); al piu' [MAX_SECTIONS] codici di al piu' [MAX_CODE_LENGTH] caratteri, cosi' il testo sta sempre nel tetto di una
 *   voce. Una sezione che non e' nell'elenco (nuova: Persone con la Fase 8) **non rompe l'ordine salvato**: chi legge la mette in coda
 *   (E-L14, lo fa `CollectionsLayout` di feature-library).
 * - **`collections_hidden:<codice>`**: [PersonalValue.Flag], una voce per sezione. Vera = nascosta; assente, falsa, lapide o un valore
 *   che non si capisce = **mostrata** (nel dubbio una sezione non sparisce). Nascosta su un dispositivo = nascosta su tutti quelli
 *   dell'account (E-L14). Le sezioni di [neverHidden] (Album: e' il modo di arrivare agli album) non si nascondono mai: non si scrive la
 *   voce e una scritta da un'altra versione o da un altro dispositivo si ignora.
 *
 * **Valgono subito, anche senza l'ora del NAS.** Si legge [PersonalStore.effectiveEntries]: lo stato unito con sopra le modifiche locali
 * ancora "da datare" (fuori casa, senza VPN e senza un accesso SMB nel processo la modifica non ha l'ora e non e' nello stato che si
 * pubblica), come la correzione R6 della revisione B della Fase 6 per `text_reading` e `search_order`. La sincronizzazione con gli altri
 * dispositivi viene dopo: la modifica prende l'ora del NAS alla prima sincronizzazione ([PersonalStore.stamp]) e conserva l'ordine.
 *
 * Questo modulo non conosce le sezioni una per una: i codici sono quelli che `feature-library` (`CollectionSections`) e chi registra una
 * sezione decidono, qui si controlla solo che siano scrivibili. Per Impostazioni → Privacy → Nascosti: [hidden] dice se la sezione
 * `hidden` e' nascosta dalle Raccolte e [setHidden] la rimostra; la pagina dei Nascosti si apre comunque dalla sua rotta.
 *
 * File a parte da [SyncedSearchSettings], [SyncedMotionSettings] e [SyncedPersonalSettings]: le implementazioni non si toccano.
 */
class SyncedCollectionsSettings(
  private val store: PersonalStore,
  scope: CoroutineScope,
  private val neverHidden: Set<String> = DEFAULT_NEVER_HIDDEN,
) {

  /** L'ordine salvato (vuoto = mai scelto). Sono i codici delle sezioni, anche di quelle che oggi non ci sono: li conserva. */
  val order: StateFlow<List<String>> = store.effectiveEntries
    .map { orderOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, orderOf(store.effectiveEntries.value))

  /** I codici delle sezioni nascoste dall'utente (mai quelle di `neverHidden`). */
  val hidden: StateFlow<Set<String>> = store.effectiveEntries
    .map { hiddenOf(it) }
    .distinctUntilChanged()
    .stateIn(scope, SharingStarted.Eagerly, hiddenOf(store.effectiveEntries.value))

  /**
   * Salva [codes] come ordine delle sezioni. Un elenco senza codici validi toglie l'ordine salvato (si torna al predefinito).
   * I codici non validi e i doppioni si scartano come in lettura: cio' che si scrive e' cio' che si rilegge.
   */
  suspend fun setOrder(codes: List<String>) {
    store.set(ORDER, CollectionsSettingsCodec.encodeOrder(codes))
  }

  /** Torna all'ordine predefinito (la lapide della voce). */
  suspend fun resetOrder() {
    store.set(ORDER, PersonalValue.Removed)
  }

  /**
   * Nasconde o mostra la sezione [code]. Un codice non scrivibile e le sezioni di `neverHidden` (Album) non scrivono niente: la
   * schermata non offre l'interruttore, e se arrivasse qui comunque la voce non nascerebbe.
   */
  suspend fun setHidden(code: String, hidden: Boolean) {
    if (!CollectionsSettingsCodec.isValidCode(code)) return
    if (code in neverHidden) return
    store.set(hiddenKey(code), PersonalValue.Flag(hidden))
  }

  private fun orderOf(entries: Map<PersonalKey, PersonalEntry>): List<String> =
    CollectionsSettingsCodec.decodeOrder(entries[ORDER]?.value)

  private fun hiddenOf(entries: Map<PersonalKey, PersonalEntry>): Set<String> {
    val prefix = "${PersonalFields.SETTING_COLLECTIONS_HIDDEN}:"
    val out = LinkedHashSet<String>()
    for ((key, entry) in entries) {
      if (key.kind != PersonalKind.SETTING || key.subject != PersonalFields.SETTINGS_SUBJECT) continue
      if (!key.field.startsWith(prefix)) continue
      val code = key.field.substring(prefix.length)
      if (!CollectionsSettingsCodec.isValidCode(code) || code in neverHidden) continue
      if ((entry.value as? PersonalValue.Flag)?.on == true) out += code
    }
    return out
  }

  companion object {
    /** Le sezioni che non si nascondono mai: Album (D33, §2.7). Lo stesso codice di `CollectionSections.ALBUMS` di feature-library. */
    val DEFAULT_NEVER_HIDDEN: Set<String> = setOf("albums")

    /** Al piu' questi codici nell'ordine salvato: 40 codici da 24 caratteri e le virgole stanno sotto il tetto di una voce `Text`. */
    const val MAX_SECTIONS: Int = CollectionsSettingsCodec.MAX_SECTIONS

    /** La lunghezza massima di un codice di sezione. */
    const val MAX_CODE_LENGTH: Int = CollectionsSettingsCodec.MAX_CODE_LENGTH

    private val ORDER = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_COLLECTIONS_ORDER)

    private fun hiddenKey(code: String) = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.collectionsHidden(code))
  }
}

/**
 * Come un ordine diventa una voce e come si rilegge, senza il resto: pura, provata sulla JVM con valori ostili. Un codice di sezione
 * e' minuscolo, comincia con una lettera e ha lettere, cifre e `_` (mai la virgola che separa, mai `:` che separa il campo, mai `\u0000`).
 */
object CollectionsSettingsCodec {
  const val MAX_SECTIONS: Int = 40
  const val MAX_CODE_LENGTH: Int = 24

  private val CODE = Regex("^[a-z][a-z0-9_]{0,${MAX_CODE_LENGTH - 1}}$")

  fun isValidCode(code: String): Boolean = CODE.matches(code)

  /** I codici validi di [codes], senza doppioni (il primo vale), al piu' [MAX_SECTIONS]. */
  fun clean(codes: Iterable<String>): List<String> = codes.filter(::isValidCode).distinct().take(MAX_SECTIONS)

  /** La voce di un ordine; senza codici validi la lapide (nessun ordine salvato). */
  fun encodeOrder(codes: List<String>): PersonalValue {
    val clean = clean(codes)
    return if (clean.isEmpty()) PersonalValue.Removed else PersonalValue.Text(clean.joinToString(","))
  }

  /** L'ordine di una voce: un testo di codici separati da virgola; ogni altro valore (o nessuno) = nessun ordine salvato. */
  fun decodeOrder(value: PersonalValue?): List<String> {
    val text = (value as? PersonalValue.Text)?.text ?: return emptyList()
    return clean(text.split(','))
  }
}
