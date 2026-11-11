package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId

// Le voci in piu' dell'elenco delle Raccolte che arrivano da `:app` (Fase 4, docs/fase4-progetto.md §1.3, §1.5, riga P14 del §4.4):
// oggi le quattro pagine del NAS della 4B (Cartelle NAS, Spazio NAS, Eliminati di recente del NAS, Operazioni in corso). Sono **solo
// dati**: `feature-library` non sa che cosa c'e' dietro una voce (i `feature-*` non si conoscono), disegna una riga e dice a `:app`
// quale e' stata toccata; `:app` apre la pagina giusta (`NasScreenContent` di feature-nas). Cosi' il codice delle Raccolte non cambia
// quando una fase aggiunge un'altra pagina.
//
// Come si collega (le tre righe che restano a chi integra, P16): `CollectionsTab` riceve i gruppi (`extraGroups`) e li passa a
// `CollectionEntryRows` dopo i gruppi di sempre; su due pannelli la voce scelta e' il `code` salvato accanto a quello delle raccolte
// e il dettaglio e' `entryDetail(entry)`; sul telefono `onOpenEntry(entry)` porta alla rotta della voce.

/**
 * Una riga in piu' delle Raccolte.
 *
 * @property code il nome stabile della destinazione (la rotta di `NasScreen`, per esempio `nas/folders`): mai un percorso del NAS,
 *   mai un nome dell'utente. Non cambia: finisce nello stato salvato del pannello scelto su un tablet.
 * @property title il nome della riga (gia' scritto, nella lingua dell'app).
 * @property subtitle la frase sotto il nome.
 * @property badge quante cose chiedono un'occhiata adesso (le operazioni in corso); 0 = niente da segnalare, nessun numero sulla riga.
 * @property trailing una parola breve a destra ("Condiviso" su un album di famiglia), solo se non c'e' un [badge]; null = niente.
 * @property cover H5: la foto che rappresenta la voce (l'ultimo ricordo, un viaggio): la piastrella la mostra al posto dell'icona quando la miniatura
 *   c'e' ([CollectionRowArt]). Null = l'icona. Mai per i Nascosti, gli Eliminati di recente o un comando; e se la foto e' stata nascosta la Libreria
 *   visibile non la trova e resta l'icona.
 * @property faces CS-1: i volti della voce (una persona, o fino a tre per "Tutte le persone"), ritagliati da feature-people solo da media mostrabili;
 *   vuoto = niente volti (Persone spenta, o una voce che non e' una persona).
 * @property coverPlace CS-1: l'id GeoNames di un luogo: la piastrella mostra la foto piu' recente scattata li' (cercata nella Libreria visibile);
 *   0 = nessuno.
 * @property coverPlaces CU-15 (giro 2): i luoghi (id GeoNames) di un mosaico: la piastrella della "Mappa" mostra fino a quattro foto, una per luogo,
 *   come i quadrati di una carta, con il segno della mappa nell'angolo; vuoto = nessun mosaico.
 */
@Immutable
data class CollectionEntry(
  val code: String,
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val tone: FluidTone,
  val badge: Int = 0,
  val trailing: String? = null,
  val cover: MediaId? = null,
  val faces: List<FaceLocator> = emptyList(),
  val coverPlace: Int = 0,
  val coverPlaces: List<Int> = emptyList(),
) {
  init {
    require(code.isNotBlank()) { "Una voce ha un codice." }
    require(badge >= 0) { "Il numero sulla riga non e' negativo." }
  }

  override fun toString(): String = "CollectionEntry($code, badge=$badge)"
}

/**
 * Una **sezione** delle Raccolte come dato (D33, §2.7): un'intestazione e le sue righe, costruita da chi la possiede (Album: P3 e P10,
 * Album smart: P6, Viaggi: P7, Utility: P8, Nascosti: P9, Persone e Ricordi: Fasi 8 e 9, Cartelle NAS e Utility ancora da `:app`) e
 * consegnata a `CollectionsTab`, che non sa che cosa ci sia dietro: disegna le righe, dice a `:app` quale e' stata toccata e le mette
 * nell'ordine che l'utente ha scelto. Si registra con un [CollectionsSectionProvider]; vedi anche [CollectionsGroup].
 *
 * @property code il codice stabile della sezione ([CollectionSections]): finisce nelle voci `collections_order` e
 *   `collections_hidden:<codice>` sincronizzate fra i dispositivi, quindi non cambia mai e non e' mai un dato dell'utente. Null solo per i
 *   gruppi di prima di P11 (`:app` li passa ancora come `extraGroups`): il codice si ricava dalle loro voci ([CollectionSections.codeOf]).
 * @property title il nome della sezione, scritto, nella lingua dell'app.
 * @property hideable se l'utente la puo' nascondere; falso per Album (e' il modo di arrivare agli album): la schermata non offre
 *   l'interruttore e una scelta arrivata da fuori si ignora.
 * @property hint una frase che la schermata "Modifica Raccolte" scrive sotto il nome quando la sezione e' nascosta ("Si apre da
 *   Impostazioni → Privacy"): dice dove si ritrova.
 */
@Immutable
data class CollectionEntryGroup(
  val title: String,
  val entries: List<CollectionEntry>,
  val code: String? = null,
  val hideable: Boolean = true,
  val hint: String? = null,
) {
  init {
    require(entries.map { it.code }.toSet().size == entries.size) { "I codici delle voci di un gruppo sono unici." }
    require(code == null || CollectionSections.isValidCode(code)) { "Il codice di una sezione e' scrivibile nelle impostazioni." }
  }

  val isEmpty: Boolean get() = entries.isEmpty()
}

/** Il nome della sezione del §2.7 e del §1.5: lo stesso tipo di [CollectionEntryGroup], con il suo codice e i suoi diritti. */
typealias CollectionsGroup = CollectionEntryGroup

/** Le funzioni pure sui gruppi: provate su JVM (`CollectionEntriesTest`). */
object CollectionEntries {

  /** La voce con [code] fra tutti i [groups], o null (una versione piu' nuova dell'app ne ha altre, o il NAS non e' piu' collegato). */
  fun find(groups: List<CollectionEntryGroup>, code: String?): CollectionEntry? =
    if (code == null) null else groups.asSequence().flatMap { it.entries }.firstOrNull { it.code == code }

  /** I gruppi con almeno una voce, nell'ordine dato: un gruppo vuoto non ha neanche l'intestazione. */
  fun visible(groups: List<CollectionEntryGroup>): List<CollectionEntryGroup> = groups.filterNot { it.isEmpty }

  /**
   * La voce scelta su due pannelli: [saved] se c'e' ancora, altrimenti null (la scelta torna a una raccolta). Un codice salvato di una
   * voce sparita non lascia un pannello vuoto.
   */
  fun selected(groups: List<CollectionEntryGroup>, saved: String?): CollectionEntry? = find(groups, saved)

  /** Tutti i codici, per verificare che due gruppi non ne condividano uno (le rotte sono uniche). */
  fun codes(groups: List<CollectionEntryGroup>): List<String> = groups.flatMap { g -> g.entries.map { it.code } }
}
