package dev.pampa.mycloudgallery.core.metadata.format

import java.util.Locale

/**
 * Le parti di un nome di file che servono a raggruppare raffiche e coppie RAW+JPEG: il nome base, l'estensione, il
 * numero di scatto in una raffica e se il nome dice "copertina".
 *
 * Suffissi di raffica riconosciuti (docs/fase3-progetto.md §2.2):
 * - `_BURSTnnn` (Android/Google: `IMG_20240312_101530_BURST003`, anche `..._BURST003_COVER`);
 * - `00001IMG_00001_BURST20240312101530123[_COVER]` (Google Camera: il numero e' in testa, la raffica e' l'identificativo);
 * - `_nnn` a tre cifre (Samsung: `20240312_101530_003`);
 * - `(n)` (`20240312_101530(3)`).
 *
 * `_nnn` e `(n)` valgono **solo** se il nome base contiene una data e un'ora (`20240312_101530`): senza, `IMG_123` e
 * `foto (1)` sono nomi qualunque e non raffiche (nel dubbio non si raggruppa).
 */
internal class NameParts(
  /** Il nome senza suffisso di raffica ne' estensione. */
  val base: String,
  /** L'estensione in minuscolo, senza punto (vuota se manca). */
  val extension: String,
  val burstIndex: Int?,
  val isCover: Boolean,
  val style: Style?,
  /** Il nome intero senza estensione, in minuscolo: due file sono una coppia RAW+JPEG se hanno lo stesso [stem]. */
  val stem: String,
) {

  enum class Style { BURST_TAG, UNDERSCORE_NUMBER, PARENTHESES }

  /** La chiave per confrontare nomi base: minuscolo, senza locale. */
  val key: String get() = base.lowercase(Locale.ROOT)

  companion object {

    private val GOOGLE_BURST = Regex("""^(\d{1,6})IMG_\d{1,6}_BURST(\d{8,20})$""", RegexOption.IGNORE_CASE)
    private val TAGGED_BURST = Regex("""^(.+)_BURST(\d{1,4})$""", RegexOption.IGNORE_CASE)
    private val UNDERSCORE = Regex("""^(.+)_(\d{3})$""")
    private val PARENS = Regex("""^(.+?)\s?\((\d{1,3})\)$""")
    private val TIMESTAMP = Regex("""\d{8}[_-]?\d{6}""")

    fun of(displayName: String): NameParts {
      val dot = displayName.lastIndexOf('.')
      val stem = if (dot > 0) displayName.substring(0, dot) else displayName
      val fullStem = stem.lowercase(Locale.ROOT)
      val ext = if (dot > 0) displayName.substring(dot + 1).lowercase(Locale.ROOT) else ""
      var name = stem
      var cover = false
      if (name.endsWith("_COVER", ignoreCase = true)) {
        cover = true
        name = name.dropLast("_COVER".length)
      }
      GOOGLE_BURST.matchEntire(name)?.let { m ->
        return NameParts("BURST" + m.groupValues[2], ext, m.groupValues[1].toIntOrNull(), cover, Style.BURST_TAG, fullStem)
      }
      TAGGED_BURST.matchEntire(name)?.let { m ->
        return NameParts(m.groupValues[1], ext, m.groupValues[2].toInt(), cover, Style.BURST_TAG, fullStem)
      }
      UNDERSCORE.matchEntire(name)?.let { m ->
        if (TIMESTAMP.containsMatchIn(m.groupValues[1])) return NameParts(m.groupValues[1], ext, m.groupValues[2].toInt(), cover, Style.UNDERSCORE_NUMBER, fullStem)
      }
      PARENS.matchEntire(name)?.let { m ->
        if (TIMESTAMP.containsMatchIn(m.groupValues[1])) return NameParts(m.groupValues[1], ext, m.groupValues[2].toInt(), cover, Style.PARENTHESES, fullStem)
      }
      return NameParts(name, ext, null, cover, null, fullStem)
    }
  }
}
