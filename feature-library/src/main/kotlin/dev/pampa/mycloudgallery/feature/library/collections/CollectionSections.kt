package dev.pampa.mycloudgallery.feature.library.collections

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Le sezioni delle Raccolte come dati (Fase 7, P11, D33, §2.7). La scheda non conosce le sezioni una per una: ogni sezione e' un
// `CollectionEntryGroup` (alias `CollectionsGroup`) che un fornitore registrato consegna, con un codice stabile. Qui stanno i codici,
// l'ordine predefinito e il contratto del fornitore.

/**
 * I codici stabili delle sezioni (D33, §2.7). Finiscono nelle voci `collections_order` e `collections_hidden:<codice>` che si
 * sincronizzano fra i dispositivi: **non cambiano mai**, e non sono mai un dato dell'utente (niente titoli di album, niente cartelle).
 * Una sezione nuova di una fase futura prende un codice nuovo e nessun altro cambia.
 */
object CollectionSections {
  /** Album, i miei e i condivisi insieme (P3, P10): non si nasconde mai. Dalla G2 sta nella scheda Album, non nelle Raccolte ([inAlbumsTab]). */
  const val ALBUMS: String = "albums"

  /** Album smart (P6): separati dagli altri perche' si comportano diversamente ("Suggerite"). Dalla G2 nella scheda Album ([inAlbumsTab]). */
  const val SMART: String = "smart"

  /** Viaggi ed eventi proposti (P7). */
  const val TRIPS: String = "trips"

  /** Persone (Fase 8): il posto nell'ordine e' gia' riservato; con il riconoscimento dei volti spento ha la riga "Attiva". */
  const val PEOPLE: String = "people"

  /** Ricordi (Fase 9): il posto nell'ordine e' gia' riservato. */
  const val MEMORIES: String = "memories"

  /**
   * Luoghi (Fase 9, `docs/fase9-progetto.md` D30): la riga "Mappa" e i luoghi piu' fotografati, subito dopo i Ricordi nell'ordine predefinito.
   * Compare solo quando c'e' il suo fornitore (P4, legato da :app in P13); con un ordine gia' salvato va in coda (E-L14).
   */
  const val PLACES: String = "places"

  /** Cartelle NAS (oggi "Sul NAS", da `:app`). */
  const val NAS: String = "nas"

  /** Telefono (oggi "Dal telefono"): Preferiti, Video, Screenshot, WhatsApp. Dalla G2 nella scheda Album ([inAlbumsTab]). */
  const val PHONE: String = "phone"

  /** Da tenere al sicuro: gli Eliminati di recente di Android, dove il cestino di sistema esiste. */
  const val SAFE: String = "safe"

  /** Utility (P8; oggi "Libera spazio" da `:app`). */
  const val UTILITY: String = "utility"

  /** Nascosti (P9): nascosta dalle Raccolte resta raggiungibile da Impostazioni → Privacy → Nascosti. */
  const val HIDDEN: String = "hidden"

  /**
   * L'ordine predefinito del §2.7: vale finche' l'utente non ne salva uno. Comprende ancora Album, Album smart e Telefono, che dalla G2 non
   * stanno piu' nelle Raccolte ([inAlbumsTab]): qui non si mostrano, ma un ordine salvato da questa versione li tiene al loro posto, e una
   * versione vecchia che lo legge (la sincronizzazione fra i dispositivi) li ritrova dove li aveva.
   */
  val defaultOrder: List<String> = listOf(ALBUMS, SMART, TRIPS, PEOPLE, MEMORIES, PLACES, NAS, PHONE, SAFE, UTILITY, HIDDEN)

  /**
   * G2 (lista dell'utente, terzo punto): le sezioni che **escono dalle Raccolte** perche' vanno nella scheda Album della pillola. I codici
   * non cambiano e non spariscono dalle voci sincronizzate: un ordine salvato che li contiene (qui o su un dispositivo con la versione
   * vecchia) resta valido, le Raccolte li saltano come una sezione che oggi non c'e', e salvando un ordine nuovo restano al loro posto
   * ([CollectionsLayout.toSave]). Un fornitore con uno di questi codici non si ascolta nemmeno: niente lavoro per una sezione che non si vede.
   */
  val inAlbumsTab: Set<String> = setOf(ALBUMS, SMART, PHONE)

  /**
   * Le sezioni che oggi non esistono e che una fase futura aggiunge (Persone, Ricordi, Luoghi): salvando un ordine non le si tiene da parte,
   * cosi' quando arrivano **vanno in coda** senza rompere l'ordine salvato (E-L14). Le altre, se mancano un momento (il NAS non ancora
   * configurato, nessun cestino di sistema), tengono il loro posto.
   */
  val reservedForLater: Set<String> = setOf(PEOPLE, MEMORIES, PLACES)

  /**
   * Le sezioni che non si nascondono mai (D33): lo stesso elenco di `SyncedCollectionsSettings.DEFAULT_NEVER_HIDDEN` di core-personal.
   *
   * G2: Album non e' piu' una sezione delle Raccolte (si arriva agli album dalla loro scheda), quindi qui il vincolo non ha piu' niente da
   * proteggere; **resta** perche' e' scritto anche nelle voci sincronizzate: una versione vecchia mostra ancora Album nelle Raccolte, e li'
   * e' l'unico modo di arrivare agli album. Con il codice in questo elenco nessuna versione scrive `collections_hidden:albums`, e una voce
   * scritta da altri si ignora ovunque.
   */
  val neverHidden: Set<String> = setOf(ALBUMS)

  /** Il massimo di sezioni che si ricordano nell'ordine salvato, e la lunghezza di un codice: gli stessi di `CollectionsSettingsCodec`. */
  const val MAX_SECTIONS: Int = 40
  const val MAX_CODE_LENGTH: Int = 24

  private val CODE = Regex("^[a-z][a-z0-9_]{0,${MAX_CODE_LENGTH - 1}}$")

  /** Un codice che si puo' scrivere nelle impostazioni: minuscolo, comincia con una lettera, lettere, cifre e `_`. */
  fun isValidCode(code: String): Boolean = CODE.matches(code)

  /**
   * Il codice di un gruppo: quello dichiarato, o -- per i gruppi che `:app` passa ancora senza codice (Cartelle NAS e Utility, prima di
   * P13) -- ricavato dalla prima parte del codice delle loro voci (`nas/folders` -> [NAS], `backup/free-space` -> [UTILITY]); un gruppo
   * che non si riconosce prende `x_<prima parte>` (stabile, mai un dato dell'utente). Null se il gruppo e' vuoto e non ha un codice.
   */
  fun codeOf(group: CollectionEntryGroup): String? {
    group.code?.let { return it }
    val first = group.entries.firstOrNull()?.code ?: return null
    val head = first.substringBefore('/').lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
    return when (head) {
      "nas" -> NAS
      "backup" -> UTILITY
      "" -> null
      else -> "x_$head".take(MAX_CODE_LENGTH).takeIf(::isValidCode)
    }
  }
}

/**
 * Chi possiede una sezione la registra con un fornitore: l'ultimo anello fra i pacchetti che costruiscono i dati (album, smart, viaggi,
 * Utility, Nascosti) e la scheda. I fornitori si legano in `:app` con un `@Provides @IntoSet` (Hilt), senza che questo modulo li
 * conosca; finche' un fornitore non c'e' la sezione non compare (null-object: [NoCollectionsSection]) e le Raccolte funzionano come
 * prima. Un fornitore con il codice di una sezione che la scheda fa gia' da sola ([CollectionSections.PHONE], [CollectionSections.SAFE],
 * [CollectionSections.HIDDEN]) o di un gruppo che `:app` passa ancora come `extraGroups` **la sostituisce**.
 */
interface CollectionsSectionProvider {
  /** Il codice stabile della sezione ([CollectionSections.isValidCode]); un fornitore con un codice non scrivibile e' ignorato. */
  val code: String

  /**
   * La sezione di adesso. Si aggiorna quando cambiano i dati (gli album, un conteggio, il NAS che si collega). **Senza voci** = la sezione
   * e' di questo fornitore e oggi e' vuota: non c'e' (nessuna intestazione, e nemmeno nella schermata "Modifica Raccolte") e toglie anche
   * quella che la scheda fa da sola con lo stesso codice. **Null** = il fornitore non ha niente da dire (non e' pronto, la funzione non c'e'):
   * resta quella della scheda, se c'e'.
   */
  val group: StateFlow<CollectionEntryGroup?>
}

/** Il fornitore che non fornisce niente: la sezione non c'e'. E' quello che c'e' finche' il pacchetto che la possiede non la registra. */
class NoCollectionsSection(override val code: String) : CollectionsSectionProvider {
  override val group: StateFlow<CollectionEntryGroup?> = MutableStateFlow<CollectionEntryGroup?>(null).asStateFlow()
}

/** Un fornitore con una sezione data (e che si puo' cambiare): quello che usano i test, gli screenshot e chi ha una sezione fissa. */
class FixedCollectionsSection(override val code: String, initial: CollectionEntryGroup?) : CollectionsSectionProvider {
  private val flow = MutableStateFlow(initial)
  override val group: StateFlow<CollectionEntryGroup?> = flow.asStateFlow()

  fun set(group: CollectionEntryGroup?) {
    flow.value = group
  }
}
