package dev.pampa.mycloudgallery.feature.widget.frame

import android.content.res.Resources
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetEmpty
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTitle
import dev.pampa.mycloudgallery.feature.widget.R
import dev.pampa.mycloudgallery.feature.widget.host.WidgetPlaceNames
import java.time.format.TextStyle
import java.util.Locale

/**
 * Le parole sulla Home, dalle risorse italiane. Dal titolo **come dati** (`WidgetTitle`: anni fa, luoghi, etichetta del viaggio, mese; mai una persona, D4) al **testo**: le stesse
 * parole della notifica "Titolo e foto" (F9-D16): "Un anno fa · Oslo", "3 anni fa", l'etichetta generata di un viaggio ("Oslo, marzo 2025"), "Il meglio di settembre". Una
 * cosa che non si conosce vale "Un ricordo per te": mai un testo a caso, mai un nome.
 *
 * Ordine: l'**etichetta del viaggio** (con gli anni se ci sono), poi gli **anni** con il primo luogo che si conosce, poi il **mese**, poi il testo generico. L'etichetta e il
 * luogo si ripuliscono (niente caratteri di controllo o di direzione, spazi ripetuti) e si accorciano: un testo lungo non spinge fuori il resto del widget.
 */
class WidgetTexts(private val resources: Resources, private val places: WidgetPlaceNames) {

  /** Il testo di un messaggio senza foto: "Configura MyCloud Gallery" se l'app non e' configurata (D42), altrimenti "Apri MyCloud Gallery" (E-W1: una riga, non un errore). */
  fun message(reason: WidgetEmpty): String = resources.getString(
    if (reason == WidgetEmpty.NOT_CONFIGURED) R.string.widget_message_configure else R.string.widget_message_open,
  )

  /** La descrizione di una cella quando quella di `core-describe` non c'e' (un giorno che non e' una data vera): solo il tipo. */
  fun cellFallback(kind: MediaKind): String = resources.getString(if (kind == MediaKind.VIDEO) R.string.widget_cell_video else R.string.widget_cell_photo)

  fun compose(title: WidgetTitle): String {
    val years = title.yearsAgo
    val label = title.tripLabel?.let(::clean)
    if (label != null) {
      return when {
        years == null -> resources.getString(R.string.widget_title_trip_plain, label)
        years == 1 -> resources.getString(R.string.widget_title_trip_one, label)
        else -> resources.getString(R.string.widget_title_trip_many, years, label)
      }
    }
    if (years != null) {
      val place = title.placeIds.firstNotNullOfOrNull { places.name(it)?.let(::clean) }
      return when {
        place == null && years == 1 -> resources.getString(R.string.widget_title_years_one)
        place == null -> resources.getString(R.string.widget_title_years_many, years)
        years == 1 -> resources.getString(R.string.widget_title_years_place_one, place)
        else -> resources.getString(R.string.widget_title_years_place_many, years, place)
      }
    }
    title.month?.let { month ->
      // L'app ha i testi solo in italiano: anche il mese e' in italiano, qualunque sia la lingua del telefono.
      return resources.getString(R.string.widget_title_month, month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.ITALIAN))
    }
    return resources.getString(R.string.widget_title_generic)
  }

  internal companion object {
    /** Al piu' cosi' lungo (il titolo sta su una riga: il launcher lo accorcia comunque). */
    const val MAX_LENGTH: Int = 40

    /**
     * Il testo ripulito: senza caratteri di controllo, di formato o di direzione (un'etichetta ostile non deve poter girare o nascondere il resto), con gli spazi
     * ripetuti ridotti a uno, tagliato a [MAX_LENGTH]; null se non resta niente.
     */
    fun clean(raw: String): String? {
      val kept = StringBuilder(raw.length)
      var lastSpace = true
      for (ch in raw) {
        val type = Character.getType(ch)
        val drop = Character.isISOControl(ch) || type == Character.FORMAT.toInt() || type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
        if (drop) continue
        if (Character.isWhitespace(ch) || type == Character.SPACE_SEPARATOR.toInt()) {
          if (!lastSpace) kept.append(' ')
          lastSpace = true
        } else {
          kept.append(ch)
          lastSpace = false
        }
      }
      val text = kept.toString().trim().take(MAX_LENGTH).trimEnd()
      return text.ifEmpty { null }
    }
  }
}
