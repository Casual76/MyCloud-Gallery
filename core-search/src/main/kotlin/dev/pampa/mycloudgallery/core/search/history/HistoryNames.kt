package dev.pampa.mycloudgallery.core.search.history

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.VisibleText
import dev.pampa.mycloudgallery.core.search.contract.NameText
import java.util.Locale

/**
 * Il riconoscimento dei nomi delle persone nella cronologia delle ricerche (Fase 8, D26, D29 passo 4, invariante 1 del §2.8; correzioni D, R2):
 * "Disattiva e cancella", "Rinomina", "Dimentica" e "Nascondi persona" tolgono le voci che nominano una persona.
 *
 * Una voce nomina una persona in due modi:
 *  - **per id**: e' nata da un chip "Persona" e la cronologia ne ricorda l'id (mai il nome fuori dal testo gia' salvato): vale anche se il nome
 *    nel frattempo e' cambiato, ed e' l'unica strada dove i nomi non si leggono piu' (R2);
 *  - **per nome**: le parole del nome (la normalizzazione Unicode di [NameText]: minuscolo, senza accenti, lettere e cifre di ogni alfabeto) compaiono di
 *    seguito, a parole intere, nel testo o in una delle etichette dei chip. Parole intere: "Giovanna" non contiene "Anna". Un nome di una lettera o di
 *    una parola comune ("A", "Rosa") toglie anche le voci che lo usano come parola qualunque: si preferisce una voce tolta in piu' a un nome rimasto.
 *    Un nome **senza lettere ne' cifre** ("❤", "?") non ha parole: si cerca com'e' scritto (maiuscole a parte) dentro il testo e le etichette.
 *
 * Puro e JVM: niente log, niente nomi in nessun messaggio (D37).
 */
object HistoryNames {

  /** Cio' che si cerca: nomi come sequenze di parole, nomi senza parole come testo, persone per id. Mai nomi o id in `toString()`. */
  class Wanted internal constructor(internal val words: List<List<String>>, internal val raw: List<String>, internal val persons: Set<PersonId>) {
    val isEmpty: Boolean get() = words.isEmpty() && raw.isEmpty() && persons.isEmpty()

    override fun toString(): String = "HistoryNames.Wanted(${words.size + raw.size} nomi, ${persons.size} persone)"
  }

  /** Quello che si cerca per i [names] e le [persons]; i nomi vuoti si scartano, i doppioni no (non servono). */
  fun wanted(names: Collection<String>, persons: Collection<PersonId> = emptyList()): Wanted {
    val words = ArrayList<List<String>>()
    val raw = ArrayList<String>()
    for (name in names) {
      val w = NameText.words(name)
      if (w.isNotEmpty()) {
        if (w !in words) words += w
      } else {
        val shown = visible(name)
        if (shown != null && shown !in raw) raw += shown
      }
    }
    return Wanted(words, raw, persons.toSet())
  }

  /** Vero se la voce ([text], [chipLabels], [personIds]) nomina qualcuno di [wanted]. */
  fun contains(text: String, chipLabels: List<String>, wanted: Wanted, personIds: Collection<PersonId> = emptyList()): Boolean {
    if (wanted.isEmpty) return false
    if (wanted.persons.isNotEmpty() && personIds.any { it in wanted.persons }) return true
    if (wanted.words.isNotEmpty()) {
      if (matches(NameText.words(text), wanted.words)) return true
      if (chipLabels.any { matches(NameText.words(it), wanted.words) }) return true
    }
    if (wanted.raw.isNotEmpty()) {
      val lowered = text.lowercase(Locale.ROOT)
      if (wanted.raw.any { lowered.contains(it) }) return true
      if (chipLabels.any { label -> val l = label.lowercase(Locale.ROOT); wanted.raw.any { l.contains(it) } }) return true
    }
    return false
  }

  /**
   * Il nome com'e' scritto, in minuscolo e senza spazi ai bordi, se ha almeno un carattere che si vede (un emoji, un simbolo, un segno); null se e' fatto
   * solo di spazi, caratteri di controllo, giunti ZWJ e selettori di variante (nessun testo da cercare).
   */
  private fun visible(name: String): String? {
    val trimmed = name.trim()
    return if (VisibleText.hasVisible(trimmed)) trimmed.lowercase(Locale.ROOT) else null
  }

  private fun matches(words: List<String>, wanted: List<List<String>>): Boolean {
    if (words.isEmpty()) return false
    for (name in wanted) {
      if (name.size > words.size) continue
      for (start in 0..words.size - name.size) {
        var same = true
        for (k in name.indices) {
          if (words[start + k] != name[k]) {
            same = false
            break
          }
        }
        if (same) return true
      }
    }
    return false
  }
}
