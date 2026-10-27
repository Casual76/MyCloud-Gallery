package dev.pampa.mycloudgallery.core.search.history

import dev.pampa.mycloudgallery.core.search.contract.SearchText

/**
 * Il riconoscimento dei nomi delle persone nella cronologia delle ricerche (Fase 8, D26, D29 passo 4, invariante 1 del §2.8): "Disattiva e
 * cancella" toglie le voci che contengono un nome noto in questa generazione. Una voce **contiene** un nome se le parole del nome
 * (normalizzate come i luoghi: minuscolo, senza accenti, apostrofi uniformi) compaiono di seguito, a parole intere, nel testo o in una
 * delle etichette dei chip. Parole intere: "Giovanna" non contiene "Anna". Un nome di una lettera o di una parola comune ("A", "Rosa") toglie
 * anche le voci che lo usano come parola qualunque: si preferisce una voce tolta in piu' a un nome rimasto.
 *
 * Puro e JVM: niente log, niente nomi in nessun messaggio (D37).
 */
object HistoryNames {

  /** I nomi come sequenze di parole normalizzate; i vuoti si scartano, i doppioni no (non servono). */
  fun wanted(names: Collection<String>): List<List<String>> =
    names.map { SearchText.words(it) }.filter { it.isNotEmpty() }.distinct()

  /** Vero se [text] o una delle [chipLabels] contiene uno dei [wanted] (da [wanted]). */
  fun contains(text: String, chipLabels: List<String>, wanted: List<List<String>>): Boolean {
    if (wanted.isEmpty()) return false
    if (matches(SearchText.words(text), wanted)) return true
    return chipLabels.any { matches(SearchText.words(it), wanted) }
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
