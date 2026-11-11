package dev.pampa.mycloudgallery.core.designsystem.chrome

import androidx.compose.runtime.Immutable

// COME STANNO LE VOCI NELLA BARRA DEI SUGGERIMENTI (CU-2 / CS-3 del giro 1 della fase H). Tre voci di uguale peso con i puntini in coda diventavano
// uguali mentre si scrive («comple… | Castigli… | comple…»): i completamenti cominciano tutti con cio' che l'utente ha gia' scritto, e la coda tagliata
// era proprio la parte che li distingueva. Qui, in ordine:
//  1. il testo piu' grande a cui le voci entrano **intere** (dalla misura della chrome fino a un minimo leggibile, come la barra della tastiera);
//  2. al minimo, le voci corte restano intere e quelle lunghe si dividono lo spazio che resta;
//  3. una voce lunga si accorcia **dove non si perde cio' che la distingue**: se comincia come un'altra voce, la parte in comune (cio' che l'utente ha
//     gia' scritto) diventa i puntini all'inizio («…della nonna a Firenze»); altrimenti i puntini vanno in coda;
//  4. **mai due voci uguali a vista**: chi collide prova i puntini in mezzo; se e' ancora uguale a una voce prima di lei, esce (resta la piu' adatta).
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

  /** Quante lettere in comune all'inizio fanno di due voci "la stessa cosa scritta fin qui". */
  private const val MinSharedPrefix = 3

  /**
   * Come stanno [labels] in [available] pixel (lo spazio per i soli testi). [steps] misure del testo, dalla piu' grande alla minima; [measure] dice quanto e'
   * largo un testo a una misura.
   */
  fun layout(labels: List<String>, available: Float, steps: Int, measure: (text: String, step: Int) -> Float): SuggestionFitResult {
    // Due voci identiche non sono due suggerimenti: resta la prima.
    val distinct = labels.withIndex().distinctBy { it.value }.filter { it.value.isNotBlank() }
    return layoutDistinct(distinct, available, steps.coerceAtLeast(1), measure)
  }

  private fun layoutDistinct(
    labels: List<IndexedValue<String>>,
    available: Float,
    steps: Int,
    measure: (String, Int) -> Float,
  ): SuggestionFitResult {
    if (labels.isEmpty()) return SuggestionFitResult(0, emptyList())
    val count = labels.size
    // 1. Il testo piu' grande a cui entrano tutte intere.
    for (step in 0 until steps) {
      val widths = labels.map { measure(it.value, step) }
      val total = widths.sum()
      if (total <= available) {
        val equal = available / count
        // Come la barra della tastiera: parti uguali se ci stanno; se no, ognuna la sua misura e il resto diviso in parti uguali.
        val weights = if (widths.all { it <= equal }) List(count) { 1f } else widths.map { it + (available - total) / count }
        return SuggestionFitResult(step, labels.mapIndexed { i, label -> ShownSuggestion(label.index, label.value, weights[i]) })
      }
    }
    // 2. Al minimo: le corte intere, alle lunghe il resto.
    val step = steps - 1
    val widths = labels.map { measure(it.value, step) }
    val slots = fill(widths, available)
    val all = labels.map { it.value }
    val texts = labels.mapIndexed { i, label ->
      if (widths[i] <= slots[i]) label.value else shorten(label.value, all - label.value, slots[i]) { measure(it, step) }
    }.toMutableList()
    // 4. Mai due voci uguali a vista: una voce accorciata che potrebbe essere anche un'altra prova i puntini in mezzo.
    fun ambiguous(i: Int): Boolean = labels.indices.any { j -> j != i && matches(texts[i], labels[j].value) }
    for (i in texts.indices) {
      if (widths[i] > slots[i] && ambiguous(i)) texts[i] = middle(labels[i].value, slots[i]) { measure(it, step) }
    }
    // Se ancora due voci si confondono, resta la prima (la piu' adatta) e l'altra esce: le altre si ridividono lo spazio.
    val clash = texts.indices.firstOrNull { j ->
      (0 until j).any { i -> matches(texts[i], labels[j].value) || matches(texts[j], labels[i].value) }
    }
    if (clash != null) return layoutDistinct(labels.filterIndexed { i, _ -> i != clash }, available, steps, measure)
    return SuggestionFitResult(step, labels.mapIndexed { i, label -> ShownSuggestion(label.index, texts[i], slots[i]) })
  }

  /**
   * Vero se [display] (una voce come si vede, magari con i puntini) potrebbe essere [label]: i pezzi visibili ci sono, nell'ordine, dove i puntini nascondono
   * almeno una lettera. Due voci di cui una "potrebbe essere" l'altra non si distinguono a vista.
   */
  internal fun matches(display: String, label: String): Boolean {
    if (!display.contains(Ellipsis)) return display == label
    val parts = display.split(Ellipsis)
    val head = parts.first()
    if (!label.startsWith(head)) return false
    var position = head.length
    for (k in 1 until parts.size) {
      val part = parts[k]
      // Dove ci sono i puntini manca almeno una lettera.
      val from = position + 1
      if (k == parts.size - 1) {
        return if (part.isEmpty()) label.length >= from else label.length - part.length >= from && label.endsWith(part)
      }
      val found = label.indexOf(part, from)
      if (found < 0) return false
      position = found + part.length
    }
    return true
  }

  /**
   * Lo spazio di ciascuna: chi ci sta nella sua parte uguale prende solo cio' che gli serve, gli altri si dividono in parti uguali cio' che resta (le voci
   * corte non si accorciano per colpa di una lunga).
   */
  internal fun fill(widths: List<Float>, available: Float): List<Float> {
    val slots = MutableList(widths.size) { 0f }
    var remaining = available
    var left = widths.indices.sortedBy { widths[it] }
    while (left.isNotEmpty()) {
      val share = remaining / left.size
      val small = left.filter { widths[it] <= share }
      if (small.isEmpty()) {
        left.forEach { slots[it] = share }
        break
      }
      small.forEach {
        slots[it] = widths[it]
        remaining -= widths[it]
      }
      left = left - small.toSet()
    }
    return slots
  }

  /**
   * [label] accorciata per stare in [slot]. Se comincia come una delle [others] (cio' che l'utente ha gia' scritto), la parte in comune fino alla parola
   * che le distingue diventa i puntini all'inizio; quello che resta, se ancora non ci sta, ha i puntini in coda. Altrimenti i puntini vanno in coda.
   */
  internal fun shorten(label: String, others: List<String>, slot: Float, width: (String) -> Float): String {
    val shared = others.maxOfOrNull { commonPrefix(label, it) } ?: 0
    if (shared >= MinSharedPrefix) {
      // Si taglia all'inizio della parola in cui le due voci cominciano a essere diverse.
      val space = label.lastIndexOf(' ', (shared - 1).coerceAtLeast(0))
      val cut = if (space >= 0) space + 1 else 0
      if (cut > 0) {
        val rest = label.substring(cut).trimStart()
        val withLead = Ellipsis + rest
        return if (width(withLead) <= slot) withLead else Ellipsis + head(rest, slot - width(Ellipsis), width)
      }
      // La differenza e' nella prima parola: i puntini in coda se lasciano vedere almeno una lettera diversa, se no all'inizio.
      val end = head(label, slot, width)
      if (end.length - Ellipsis.length > shared) return end
      return tail(label, slot, width)
    }
    return head(label, slot, width)
  }

  /** I puntini in mezzo: l'inizio e la fine della voce (per due voci che restavano uguali). */
  internal fun middle(label: String, slot: Float, width: (String) -> Float): String {
    if (width(label) <= slot) return label
    var best = Ellipsis
    for (keep in 1 until label.length) {
      val front = (keep + 1) / 2
      val back = keep - front
      val candidate = label.take(front).trimEnd() + Ellipsis + label.takeLast(back).trimStart()
      if (width(candidate) > slot) break
      best = candidate
    }
    return best
  }

  /** L'inizio di [label] che sta in [slot] con i puntini in coda (l'intera voce se ci sta). */
  private fun head(label: String, slot: Float, width: (String) -> Float): String {
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

  /** La fine di [label] che sta in [slot] con i puntini all'inizio. */
  private fun tail(label: String, slot: Float, width: (String) -> Float): String {
    if (width(label) <= slot) return label
    var low = 0
    var high = label.length
    while (low < high) {
      val mid = (low + high + 1) / 2
      if (width(Ellipsis + safeTakeLast(label, mid).trimStart()) <= slot) low = mid else high = mid - 1
    }
    return Ellipsis + safeTakeLast(label, low).trimStart()
  }

  /** Le prime [count] unita' senza spezzare una coppia di surrogati (una emoji resta intera o fuori). */
  private fun safeTake(text: String, count: Int): String {
    val end = count.coerceIn(0, text.length)
    return if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1])) text.take(end - 1) else text.take(end)
  }

  private fun safeTakeLast(text: String, count: Int): String {
    val start = text.length - count.coerceIn(0, text.length)
    return if (start in 1 until text.length && Character.isLowSurrogate(text[start])) text.substring(start + 1) else text.substring(start)
  }

  /** Quante lettere hanno in comune all'inizio (senza badare alle maiuscole). */
  internal fun commonPrefix(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var i = 0
    while (i < limit && a[i].lowercaseChar() == b[i].lowercaseChar()) i++
    return i
  }
}
