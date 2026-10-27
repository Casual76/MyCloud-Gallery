package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId

// LE VOCI `people` DAL TESTO ALLA MODIFICA (docs/fase8-progetto.md §2.4 "Voci people", D23, D24). Il database le tiene nella forma del formato
// (`subject`, `field`, `value`, tutti testo: la chiave di una voce e' la coppia soggetto-campo) e l'archivio le restituisce come
// [PeopleEdit]. Questa e' la decodifica, **stretta** come quella del formato: una forma che non si capisce (un campo sconosciuto, un id non
// canonico, un valore d'altra forma, una persona unita a se stessa) e' `null`, e chi legge salta la riga: non entra mai niente di
// mezzo-valido. La scrittura e' gia' nei contratti (`PeopleEdit.subject/field/value`): qui la lettura.

internal object PeopleEditCodec {

  fun parse(subject: String, field: String, value: String?): PeopleEdit? = try {
    when {
      field == "name" -> person(subject)?.let { PeopleEdit.Name(it, value) }
      field == "hidden" -> person(subject)?.let { p -> flag(value)?.let { PeopleEdit.Hidden(p, it) } }
      field == "forget" -> if (value == "true") person(subject)?.let { PeopleEdit.Forget(it) } else null
      field == "alias" -> person(subject)?.let { p -> value?.let(::person)?.let { PeopleEdit.Alias(p, it) } }
      field.startsWith("apart:") -> person(subject)?.let { p ->
        person(field.removePrefix("apart:"))?.let { other -> presence(value)?.let { PeopleEdit.Apart(p, other, it) } }
      }
      field == "born" -> person(subject)?.let { p ->
        value?.takeIf { it.startsWith("split:") }?.removePrefix("split:")?.let(::person)?.let { PeopleEdit.Born(p, it) }
      }
      field == "cover" -> person(subject)?.let { p ->
        if (value == null) PeopleEdit.Cover(p, null) else FaceLocator.parse(value)?.let { PeopleEdit.Cover(p, it) }
      }
      field == "is" -> FaceLocator.parse(subject)?.let { face ->
        if (value == null) PeopleEdit.Is(face, null) else person(value)?.let { PeopleEdit.Is(face, it) }
      }
      field.startsWith("not:") -> FaceLocator.parse(subject)?.let { face ->
        person(field.removePrefix("not:"))?.let { p -> presence(value)?.let { PeopleEdit.Not(face, p, it) } }
      }
      field == "ignore" -> FaceLocator.parse(subject)?.let { face -> presence(value)?.let { PeopleEdit.Ignore(face, it) } }
      else -> null
    }
  } catch (_: IllegalArgumentException) {
    // I costruttori dei contratti rifiutano il non valido (un nome non ammesso, due persone uguali): e' "non si capisce".
    null
  }

  private fun person(text: String): PersonId? = PersonId.fromBase32(text)

  /** `true`/`false`, o null se e' un'altra forma. */
  private fun flag(value: String?): Boolean? = when (value) {
    "true" -> true
    "false" -> false
    else -> null
  }

  /** I campi "vero oppure lapide": `true` = c'e', assente = lapide (falso); qualunque altro valore non si capisce. */
  private fun presence(value: String?): Boolean? = when (value) {
    "true" -> true
    null -> false
    else -> null
  }
}
