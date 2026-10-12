package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity

/**
 * Una voce del catalogo: tutto cio' che si sa di un [code] a prescindere da chi lo produce.
 *
 * @property severity la gravita' **tipica**; chi contribuisce puo' modularla (un'uscita per poca memoria e' un
 *   avviso, la stessa uscita dieci volte di fila un errore).
 * @property titleRes la frase breve, usata dove manca quella del contributore (gli eventi del registro).
 * @property guideRes il «cosa fare»; 0 solo per le voci che non sono ne' un avviso ne' un errore.
 * @property action cosa si puo' fare dalla voce, se il contributore non ne propone una.
 */
class DiagEntry(
  val code: DiagCode,
  val section: DiagSection,
  val severity: DiagSeverity,
  @param:StringRes val titleRes: Int,
  @param:StringRes val guideRes: Int,
  val action: DiagAction?,
) {
  override fun toString(): String = "DiagEntry($code)"
}

/**
 * Il catalogo dei [DiagCode] (P8, docs/fase2-progetto.md §2.6): l'elenco di tutto cio' che la Diagnostica sa
 * dire, con gli edge case di Fase 2 che ogni codice copre.
 *
 * Serve a tre cose:
 *  1. **completezza**: `DiagCatalogTest` verifica che ogni edge case di Fase 2 che produce un avviso abbia il suo
 *     codice («fatto quando: ogni avviso ha una voce in Diagnostica», piano 2.7);
 *  2. **«cosa fare»**: la guida e l'azione suggerita stanno qui, non in ogni modulo che contribuisce;
 *  3. **eventi**: una riga del registro e' solo un codice e dei numeri, e il suo titolo viene da qui.
 */
object DiagCatalog {

  val entries: List<DiagEntry> = DiagCatalogEntries.all

  private val byId: Map<String, DiagEntry> = entries.associateBy { it.code.id }

  /** Gli edge case (`E-S16`, `C3`, `T-19`...) che almeno un codice dichiara di coprire. */
  val coveredEdgeCases: Set<String> = entries.flatMapTo(LinkedHashSet()) { it.code.edgeCases }

  fun entry(code: DiagCode): DiagEntry? = byId[code.id]

  operator fun contains(code: DiagCode): Boolean = code.id in byId

  /** Il titolo breve di [code], o 0 se il codice non e' nel catalogo. */
  @StringRes
  fun titleOf(code: DiagCode): Int = byId[code.id]?.titleRes ?: 0

  /** Il «cosa fare» di [code], o 0 se non c'e'. */
  @StringRes
  fun guideOf(code: DiagCode): Int = byId[code.id]?.guideRes ?: 0

  /** L'azione della voce: quella che ha proposto il contributore, altrimenti quella del catalogo. */
  fun actionFor(item: DiagItem): DiagAction? = item.action ?: byId[item.code.id]?.action

  /** I codici che coprono [edgeCase] (di solito uno, a volte piu' d'uno: un edge case ha piu' facce). */
  fun codesCovering(edgeCase: String): List<DiagCode> = entries.filter { edgeCase in it.code.edgeCases }.map { it.code }

  /**
   * I codici di [items] che il catalogo non conosce. Non e' un errore a runtime (la voce si mostra lo stesso, col
   * titolo del contributore): e' la domanda a cui risponde il test di collegamento di :app (P9).
   */
  fun unknownCodes(items: Collection<DiagItem>): Set<DiagCode> = items.map { it.code }.filterNot { it in this }.toSet()

  /** Le voci di una sezione, nell'ordine del catalogo. */
  fun entriesOf(section: DiagSection): List<DiagEntry> = entries.filter { it.section == section }
}
