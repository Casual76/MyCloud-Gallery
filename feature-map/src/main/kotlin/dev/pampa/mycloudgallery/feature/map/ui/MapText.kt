package dev.pampa.mycloudgallery.feature.map.ui

import android.content.res.Resources
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.map.R

/**
 * Le frasi della mappa che si compongono con i numeri e i nomi, da funzioni pure (si provano senza comporre). Mai un nome o una coordinata fuori da dove li
 * mostra la schermata: nessuna di queste frasi va nei log (D33).
 */
object MapText {

  /** "12 foto" o "1 foto". */
  fun photos(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.mappa_photos, count.coerceAtLeast(1), GalleryNumbers.format(count))

  /** La frase di TalkBack di una bolla: "12 foto, Firenze", o "12 foto" senza nome, o "1 foto". */
  fun bubble(resources: Resources, count: Int, label: String?): String {
    val photos = photos(resources, count)
    return if (label.isNullOrBlank()) photos else resources.getString(R.string.mappa_bubble_place, photos, label)
  }

  /** "1.240 foto senza posizione". */
  fun withoutPosition(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.mappa_without_position, count.coerceAtLeast(1), GalleryNumbers.format(count))

  /** "9.000 foto con la posizione". */
  fun withPosition(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.mappa_with_position, count.coerceAtLeast(1), GalleryNumbers.format(count))
}
