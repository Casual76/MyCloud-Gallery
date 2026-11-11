package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.runtime.Immutable

// COME STANNO LE VOCI NELLA BARRA DEI SUGGERIMENTI (CU-2 / CS-3, giri 1 e 2 della fase H). Il modello dell'utente e' la barra delle parole della tastiera,
// che mostra parole **intere**: tre voci accorciate («…della nonn… | Castiglione… | …di Anna al…») sembrano un errore. Qui, in ordine:
//  1. si mostrano le voci che entrano **intere**, nell'ordine dato (la piu' adatta prima), fino a tre: una voce che non entra lascia il posto alle altre;
//  2. il testo e' il piu' grande a cui entrano piu' voci (dalla misura della chrome fino all'etichetta piccola del tema): due voci intere sono meglio di
//     tre mozzate, e tre intere un po' piu' piccole sono meglio di due grandi;
//  3. **mai puntini in testa e in coda insieme**: se nemmeno una voce entra intera, se ne mostra una sola, la prima, con i puntini in coda.
// Pura, senza Compose: la misura del testo arriva da fuori, quindi si prova su JVM.

/** Una voce come si vede: [index] e' la sua posizione fra quelle date (il tocco e TalkBack la usano), [text] cio' che si scrive, [weight] il suo spazio. */
@Immutable
internal data class ShownSuggestion(val index: Int, val text: String, val weight: Float)

/** Le voci da mostrare, tutte con il testo alla misura [step] (0 = la piu' grande). */
@Immutable
internal data class SuggestionFitResult(val step: Int, val shown: List<ShownSuggestion>)

internal object SuggestionFit {

  /** I puntini (un carattere solo, come quelli della tipografia). */
  const val Ellipsis: String = "…"

  /**
   * Come stanno [labels] in una riga larga [width] pixel, dove ogni voce ha anche [itemPadding] di margine e fra due voci c'e' un separatore largo
   * [separator]. [steps] misure del testo, dalla piu' grande alla minima; [measure] dice quanto e' largo un testo a una misura.
   */
  fun layout(
    labels: List<String>,
    width: Float,
    itemPadding: Float,
    separator: Float,
    steps: Int,
    measure: (text: String, step: Int) -> Float,
  ): SuggestionFitResult {
    // Due voci identiche non sono due suggerimenti: resta la prima.
    val candidates = labels.withIndex().distinctBy { it.value }.filter { it.value.isNotBlank() }
    if (candidates.isEmpty()) return SuggestionFitResult(0, emptyList())
    val last = steps.coerceAtLeast(1) - 1
    fun textSpace(count: Int): Float = width - itemPadding * count - separator * (count - 1).coerceAtLeast(0)

    var bestStep = 0
    var best: List<IndexedValue<String>> = emptyList()
    for (step in 0..last) {
      // Ogni voce, nell'ordine, entra se ci sta intera insieme a quelle gia' prese.
      val chosen = ArrayList<IndexedValue<String>>(candidates.size)
      var used = 0f
      for (label in candidates) {
        val w = measure(label.value, step)
        if (used + w <= textSpace(chosen.size + 1)) {
          chosen += label
          used += w
        }
      }
      if (chosen.size > best.size) {
        best = chosen
        bestStep = step
      }
      // Tutte intere: il testo piu' grande a cui succede.
      if (chosen.size == candidates.size) break
    }
    if (best.isNotEmpty()) return whole(best, bestStep, textSpace(best.size), measure)

    // Nemmeno una entra intera: una sola, la prima, con i puntini in coda.
    val first = candidates.first()
    val space = textSpace(1)
    return SuggestionFitResult(last, listOf(ShownSuggestion(first.index, head(first.value, space) { measure(it, last) }, space)))
  }

  /** Le voci intere: parti uguali se ci stanno, come la barra della tastiera; se no ognuna la sua misura e il resto diviso in parti uguali. */
  private fun whole(chosen: List<IndexedValue<String>>, step: Int, space: Float, measure: (String, Int) -> Float): SuggestionFitResult {
    val widths = chosen.map { measure(it.value, step) }
    val equal = space / chosen.size
    val slack = (space - widths.sum()) / chosen.size
    val weights = if (widths.all { it <= equal }) List(chosen.size) { 1f } else widths.map { it + slack }
    return SuggestionFitResult(step, chosen.mapIndexed { i, label -> ShownSuggestion(label.index, label.value, weights[i]) })
  }

  /** L'inizio di [label] che sta in [slot] con i puntini in coda (l'intera voce se ci sta). */
  internal fun head(label: String, slot: Float, width: (String) -> Float): String {
    if (width(label) <= slot) return label
    var low = 0
    var high = label.length
    // Il prefisso piu' lungo che, con i puntini, sta nel posto.
    while (low < high) {
      val mid = (low + high + 1) / 2
      if (width(safeTake(label, mid).trimEnd() + Ellipsis) <= slot) low = mid else high = mid - 1
    }
    return safeTake(label, low).trimEnd() + Ellipsis
  }

  /** Le prime [count] unita' senza spezzare una coppia di surrogati (una emoji resta intera o fuori). */
  private fun safeTake(text: String, count: Int): String {
    val end = count.coerceIn(0, text.length)
    return if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1])) text.take(end - 1) else text.take(end)
  }
}
