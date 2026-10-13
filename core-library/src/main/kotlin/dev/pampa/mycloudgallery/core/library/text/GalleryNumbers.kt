package dev.pampa.mycloudgallery.core.library.text

/**
 * **Il solo punto** in cui la galleria scrive un conteggio per le persone: "92.019 foto, 7.981 video", i contatori della
 * selezione, delle Raccolte, la posizione nel viewer ("1.204 di 92.019"). Come [GalleryDates] per le date, il formato e'
 * **sempre italiano**, qualunque lingua abbia il telefono: separatore delle migliaia il punto, da 1.000 in su.
 *
 * Perche' non `%d` nelle risorse: `String.format("%d")` non mette nessun separatore ("92019 foto"), e `%,d` userebbe la
 * lingua del telefono ("92,019" con il telefono in inglese). Le stringhe che mostrano un conteggio prendono quindi un
 * `%1$s` e ricevono il testo di [format]; i plurali (`<plurals>`) continuano a scegliere la forma dal numero vero.
 *
 * Nessun `Locale`, nessun `NumberFormat`: il risultato non cambia fra JVM, Robolectric e Android (ICU), e non dipende da
 * `Locale.getDefault()`. Solo interi: la galleria non mostra decimali.
 */
object GalleryNumbers {

  /** Il separatore italiano delle migliaia. */
  const val GROUP_SEPARATOR: Char = '.'

  /** "92.019"; "-1.234"; "999" (sotto 1.000 non c'e' separatore). */
  fun format(value: Long): String {
    val text = value.toString()
    val negative = text.startsWith('-')
    val digits = if (negative) text.substring(1) else text
    if (digits.length <= GROUP_SIZE) return text
    val out = StringBuilder(text.length + digits.length / GROUP_SIZE + 1)
    if (negative) out.append('-')
    val head = digits.length % GROUP_SIZE
    if (head > 0) out.append(digits, 0, head)
    var i = head
    while (i < digits.length) {
      if (out.isNotEmpty() && out.last() != '-') out.append(GROUP_SEPARATOR)
      out.append(digits, i, i + GROUP_SIZE)
      i += GROUP_SIZE
    }
    return out.toString()
  }

  fun format(value: Int): String = format(value.toLong())

  private const val GROUP_SIZE = 3
}
