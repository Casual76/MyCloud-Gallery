package dev.pampa.mycloudgallery.feature.library.collections

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource

// L'ordine e la visibilita' delle sezioni delle Raccolte (Fase 7, P11, D33, E-L14), come dati e come funzioni pure provate su JVM.
// `CollectionsLayoutState` e' lo stato del §1.5 ("sezioni con ordine e visibilita', modalita' Modifica"): il filone grafico lo disegna
// senza toccare la logica.

/** Un testo che si scrive nella composizione: una risorsa (le sezioni che la scheda fa da sola) o una frase gia' scritta (un fornitore). */
@Immutable
sealed interface SectionText {
  data class Res(@StringRes val id: Int) : SectionText

  data class Raw(val text: String) : SectionText
}

@Composable
internal fun SectionText.resolve(): String = when (this) {
  is SectionText.Res -> stringResource(id)
  is SectionText.Raw -> text
}

/** Una riga di una sezione: una raccolta che la scheda sa aprire da sola, o una voce che un fornitore o `:app` consegna come dato. */
@Immutable
sealed interface SectionRow {
  /** Il nome stabile della riga dentro la scheda (non e' mai un dato dell'utente). */
  val key: String

  /** Preferiti, Video, Screenshot, WhatsApp, Nascosti, Eliminati di recente: si aprono con `onOpenCollection(kind)`. */
  data class Kind(val row: CollectionRow) : SectionRow {
    override val key: String get() = "kind:${row.kind.code}"
  }

  /** Una voce in piu': si apre con `onOpenEntry(entry)` e, su due pannelli, con `entryDetail(entry)`. */
  data class Entry(val entry: CollectionEntry) : SectionRow {
    override val key: String get() = "entry:${entry.code}"
  }
}

/**
 * Una sezione com'e' adesso: il suo codice, le righe e se si vede.
 *
 * @property hideable se l'utente la puo' nascondere (falso per Album: mai).
 * @property shown falso se l'utente l'ha nascosta: sta nella schermata "Modifica Raccolte" con l'interruttore spento, non nell'elenco.
 */
@Immutable
data class CollectionsSection(
  val code: String,
  val title: SectionText,
  val rows: List<SectionRow>,
  val hideable: Boolean = true,
  val shown: Boolean = true,
  val hint: SectionText? = null,
)

/**
 * Lo stato della scheda Raccolte (§1.5, `CollectionsLayoutState`): le sezioni che ci sono adesso nell'ordine scelto dall'utente (anche
 * quelle nascoste, con [CollectionsSection.shown] falso) e se si sta modificando l'elenco. Solo dati.
 *
 * @property saveFailed l'ultima scelta (ordine o visibilita') non e' stata salvata: la schermata "Modifica Raccolte" lo dice.
 */
@Immutable
data class CollectionsLayoutState(
  val sections: List<CollectionsSection>,
  val editing: Boolean = false,
  val saveFailed: Boolean = false,
) {
  /** Le sezioni che si vedono nell'elenco, nell'ordine. */
  val visible: List<CollectionsSection> get() = sections.filter { it.shown }

  /** Tutte le righe che si vedono nell'elenco, nell'ordine in cui compaiono. */
  val visibleRows: List<SectionRow> get() = visible.flatMap { it.rows }

  /** Se [kind] e' una riga che si vede (una sezione nascosta, o un cestino che non c'e', no). */
  operator fun contains(kind: CollectionKind): Boolean = visibleRows.any { it is SectionRow.Kind && it.row.kind == kind }

  /**
   * La prima raccolta-filtro che si vede: su due pannelli e' quella aperta quando non se ne e' scelta una. Mai i Nascosti o gli Eliminati di
   * recente (chiederebbero la credenziale senza che nessuno l'abbia toccato); null se non se ne vede nessuna.
   */
  val firstFilter: CollectionKind?
    get() = visibleRows.firstNotNullOfOrNull { (it as? SectionRow.Kind)?.row?.kind?.takeIf(CollectionKind::isFilter) }

  /** La voce di un fornitore con [code], se si vede. */
  fun findEntry(code: String?): CollectionEntry? =
    if (code == null) null else visibleRows.firstNotNullOfOrNull { (it as? SectionRow.Entry)?.entry?.takeIf { e -> e.code == code } }
}

/** Le funzioni pure dell'ordine e della visibilita' delle sezioni: provate su JVM (`CollectionsLayoutTest`). */
object CollectionsLayout {

  /**
   * L'ordine in cui mostrare [codes] (le sezioni che ci sono adesso) dato l'ordine salvato [saved].
   *
   * - **Mai salvato** (vuoto): l'ordine predefinito del §2.7; le sezioni che non ne fanno parte vanno in fondo, come arrivano.
   * - **Salvato**: le sezioni dell'ordine salvato, nel suo ordine; **una sezione nuova non compare in quello salvato e va in coda**
   *   (E-L14: Persone con la Fase 8), senza rompere l'ordine che l'utente ha scelto; piu' sezioni nuove si mettono in coda nel loro
   *   ordine predefinito. Un codice salvato che oggi non c'e' (il NAS non configurato) si salta e riprende il suo posto quando torna.
   */
  fun orderOf(codes: List<String>, saved: List<String>): List<String> {
    val present = codes.distinct()
    val base = if (saved.isEmpty()) CollectionSections.defaultOrder else saved
    val index = HashMap<String, Int>()
    base.forEachIndexed { i, code -> index.putIfAbsent(code, i) }
    val known = present.filter { it in index }.sortedBy { index.getValue(it) }
    val fresh = present.filter { it !in index }
    val tail = if (saved.isEmpty()) {
      fresh
    } else {
      fresh.sortedBy { code -> CollectionSections.defaultOrder.indexOf(code).let { if (it < 0) Int.MAX_VALUE else it } }
    }
    return known + tail
  }

  /**
   * Le sezioni [present] (quelle che hanno almeno una riga) nell'ordine di [orderOf], con la visibilita' scelta: una sezione e' nascosta
   * se e' in [hidden] **e** si puo' nascondere (Album no, qualunque cosa dica la voce sincronizzata).
   */
  fun arrange(present: List<CollectionsSection>, saved: List<String>, hidden: Set<String>): List<CollectionsSection> {
    val unique = present.filter { it.rows.isNotEmpty() }.distinctBy { it.code }
    val byCode = unique.associateBy { it.code }
    return orderOf(unique.map { it.code }, saved).map { code ->
      val section = byCode.getValue(code)
      val hideable = section.hideable && code !in CollectionSections.neverHidden
      section.copy(hideable = hideable, shown = !(hideable && code in hidden))
    }
  }

  /**
   * L'ordine da salvare quando l'utente ha scelto [newOrder] per le sezioni che ci sono adesso. Le sezioni che oggi non ci sono ma stanno
   * nell'ordine salvato [previousSaved] **non si perdono**: restano subito dopo la sezione che le precedeva (il NAS non ancora collegato
   * tiene il suo posto). Con un ordine mai salvato valgono i posti predefiniti, tranne quelli delle sezioni future (Persone, Ricordi),
   * che quando arrivano vanno in coda (E-L14). Al piu' [CollectionSections.MAX_SECTIONS] codici.
   */
  fun toSave(newOrder: List<String>, previousSaved: List<String>): List<String> {
    val chosen = newOrder.distinct()
    val chosenSet = chosen.toSet()
    val base = if (previousSaved.isNotEmpty()) {
      previousSaved.distinct()
    } else {
      CollectionSections.defaultOrder.filter { it in chosenSet || it !in CollectionSections.reservedForLater }
    }
    val result = chosen.toMutableList()
    for ((i, code) in base.withIndex()) {
      if (code in result) continue
      var position = 0
      for (j in i - 1 downTo 0) {
        val at = result.indexOf(base[j])
        if (at >= 0) {
          position = at + 1
          break
        }
      }
      result.add(position, code)
    }
    return result.take(CollectionSections.MAX_SECTIONS)
  }

  /** [order] con [code] spostato di [delta] posti (-1 su, +1 giu'); null se non c'e' il posto o il codice non c'e' ("Sposta su" in cima). */
  fun moved(order: List<String>, code: String, delta: Int): List<String>? {
    val from = order.indexOf(code)
    val to = from + delta
    if (from < 0 || delta == 0 || to !in order.indices) return null
    return order.toMutableList().also {
      it[from] = it[to]
      it[to] = code
    }
  }

  /** Le sezioni dei gruppi che `:app` passa ancora senza fornitore (Cartelle NAS, Utility prima di P13), come sezioni con le sole voci. */
  fun legacySections(groups: List<CollectionEntryGroup>, taken: Set<String>): List<CollectionsSection> {
    val seen = HashSet(taken)
    return groups.mapNotNull { group ->
      val code = CollectionSections.codeOf(group) ?: return@mapNotNull null
      if (group.isEmpty || !seen.add(code)) return@mapNotNull null
      sectionOf(code, group)
    }
  }

  /** La sezione di un [group] consegnato da un fornitore o da `:app`. Senza voci non c'e' sezione. */
  fun sectionOf(code: String, group: CollectionEntryGroup?): CollectionsSection? {
    if (group == null || group.isEmpty) return null
    return CollectionsSection(
      code = code,
      title = SectionText.Raw(group.title),
      rows = group.entries.map { SectionRow.Entry(it) },
      hideable = group.hideable,
      hint = group.hint?.let { SectionText.Raw(it) },
    )
  }

  /**
   * Lo stato della scheda: le sezioni [present] (della scheda e dei fornitori) piu' i gruppi di `:app` [legacy] di cui nessuno ha gia' il
   * codice, nell'ordine salvato e con la visibilita' scelta.
   */
  fun state(
    present: List<CollectionsSection>,
    legacy: List<CollectionEntryGroup>,
    saved: List<String>,
    hidden: Set<String>,
    editing: Boolean,
    saveFailed: Boolean,
  ): CollectionsLayoutState {
    val all = present + legacySections(legacy, present.mapTo(HashSet()) { it.code })
    return CollectionsLayoutState(arrange(all, saved, hidden), editing, saveFailed)
  }
}
