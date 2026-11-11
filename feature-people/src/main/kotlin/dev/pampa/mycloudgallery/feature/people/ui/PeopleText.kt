package dev.pampa.mycloudgallery.feature.people.ui

import android.content.res.Resources
import dev.pampa.mycloudgallery.core.faces.contract.PersonCard
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.people.R

/**
 * I testi che piu' schermate dicono allo stesso modo: il conto delle foto e la frase che TalkBack legge per una persona
 * (docs/fase8-progetto.md §2.6 "Accessibilita'": "Marco, 120 foto" o "Persona senza nome, 12 foto"; mai solo l'immagine).
 */
internal object PeopleText {

  /** "120 foto", "1 foto", "0 foto" (con il separatore delle migliaia). */
  fun photos(resources: Resources, count: Int): String = resources.getQuantityString(R.plurals.persone_photos, count, GalleryNumbers.format(count))

  /** La frase che TalkBack legge per una persona: il nome (o "Persona senza nome") e il conto. */
  fun spoken(resources: Resources, card: PersonCard): String =
    (card.name ?: resources.getString(R.string.persone_unnamed_spoken)) + ", " + photos(resources, card.photos)

  /** Il titolo di una riga: il nome, o "Persona senza nome". */
  fun title(resources: Resources, card: PersonCard): String = card.name ?: resources.getString(R.string.persone_unnamed_spoken)

  /** Il nome per una frase ("Anna", o "una persona senza nome"). */
  fun nameInSentence(resources: Resources, card: PersonCard): String = card.name ?: resources.getString(R.string.persone_unnamed_in_sentence)
}
