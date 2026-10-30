package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.VisibleText
import dev.pampa.mycloudgallery.core.trust.codec.TextRules

// LE VOCI `people` COME LE PENSA CHI LE FA (docs/fase8-progetto.md §2.4 "Voci people", §2.6, D13, D23, D24). Contratti di P0: le azioni di
// `People` (P9) producono [PeopleEdit]; il codec firmato (`FacesDocs`, classe 20, JSON canonico, l'ultima scrittura vince come
// `PersonalMerge`) e' di P3 in core-trust, che aggiunge l'ora del NAS (`at`) e l'autore. Soggetto e campo hanno qui la loro forma del
// formato (§2.4): la chiave della voce e' SHA-256("mcg-people\0" ‖ soggetto ‖ "\0" ‖ campo). Il valore assente e' la **lapide**.
// Mai nomi, id o posti nei log (D37).

/** Le regole del nome di una persona (D24, §2.4): da 1 a [MAX_CHARS] caratteri, senza spazi ai bordi, regole di `TextRules` di core-trust. */
object PersonNames {
  const val MAX_CHARS: Int = 64

  /**
   * Il nome come si salva: senza spazi ai bordi, oppure null se non e' ammesso (vuoto, troppo lungo, caratteri di controllo, di
   * direzione o invisibili: `TextRules.hasForbidden`). La stessa regola in scrittura e in lettura (chi legge scarta il segmento con un nome
   * non ammesso, P3).
   */
  fun normalize(raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty() || name.length > MAX_CHARS) return null
    if (TextRules.hasForbidden(name)) return null
    return name
  }

  /**
   * Il nome **di una persona che l'utente sta nominando adesso** (R9, correzioni D): come [normalize], piu' **almeno un carattere che si veda** ([VisibleText]): un
   * nome fatto solo di giunti ZWJ e selettori di variante appare vuoto e non si scrive. Un emoji, un simbolo o una lettera di qualunque alfabeto vanno bene. Solo
   * in scrittura: la lettura di un nome degli altri dispositivi segue il formato (`PeopleForms.isName`, che e' la stessa regola di [normalize]) e la ricerca salta
   * da sola un nome senza lettere ne' cifre (`PersonEntry.of`).
   */
  fun forNewName(raw: String): String? = normalize(raw)?.takeIf { VisibleText.hasVisible(it) }
}

/**
 * Una modifica delle persone, una voce `people` (§2.4). [subject] e [field] sono le forme del formato: la persona in base32 (26 caratteri)
 * o il localizzatore del volto; [value] e' il testo del valore (null = lapide).
 */
sealed interface PeopleEdit {
  val subject: String
  val field: String
  val value: String?

  /** `name`: rinomina; null = senza nome (lapide). */
  data class Name(val person: PersonId, val name: String?) : PeopleEdit {
    init {
      require(name == null || PersonNames.normalize(name) == name) { "Nome non ammesso." }
    }

    override val subject: String get() = person.base32()
    override val field: String get() = "name"
    override val value: String? get() = name

    override fun toString(): String = "PeopleEdit.Name(${name != null})"
  }

  /** `hidden`: Nascondi persona (vero/falso). */
  data class Hidden(val person: PersonId, val hidden: Boolean) : PeopleEdit {
    override val subject: String get() = person.base32()
    override val field: String get() = "hidden"
    override val value: String get() = hidden.toString()

    override fun toString(): String = "PeopleEdit.Hidden($hidden)"
  }

  /** `forget`: Dimentica persona (solo vero; si accompagna alla lapide del nome). */
  data class Forget(val person: PersonId) : PeopleEdit {
    override val subject: String get() = person.base32()
    override val field: String get() = "forget"
    override val value: String get() = "true"

    override fun toString(): String = "PeopleEdit.Forget"
  }

  /** `alias`: [person] e' stata unita in [into] (D22: unire = alias, mai un id che cambia). */
  data class Alias(val person: PersonId, val into: PersonId) : PeopleEdit {
    init {
      require(person != into) { "Una persona non si unisce a se stessa." }
    }

    override val subject: String get() = person.base32()
    override val field: String get() = "alias"
    override val value: String get() = into.base32()

    override fun toString(): String = "PeopleEdit.Alias"
  }

  /** `apart:<altra persona>`: mai unite in automatico ("Separati", "No" a una proposta, "Dividi"). [apart] falso = lapide. */
  data class Apart(val person: PersonId, val other: PersonId, val apart: Boolean) : PeopleEdit {
    init {
      require(person != other) { "Una coppia e' di due persone diverse." }
    }

    override val subject: String get() = person.base32()
    override val field: String get() = "apart:${other.base32()}"
    override val value: String? get() = if (apart) "true" else null

    override fun toString(): String = "PeopleEdit.Apart($apart)"
  }

  /** `born`: [person] e' nata da "Dividi" di [splitFrom] (valore `split:<id d'origine>`). */
  data class Born(val person: PersonId, val splitFrom: PersonId) : PeopleEdit {
    override val subject: String get() = person.base32()
    override val field: String get() = "born"
    override val value: String get() = "split:${splitFrom.base32()}"

    override fun toString(): String = "PeopleEdit.Born"
  }

  /** `cover`: il volto scelto come copertina; null = automatica (lapide). */
  data class Cover(val person: PersonId, val face: FaceLocator?) : PeopleEdit {
    override val subject: String get() = person.base32()
    override val field: String get() = "cover"
    override val value: String? get() = face?.encode()

    override fun toString(): String = "PeopleEdit.Cover(${face != null})"
  }

  /** `is`: "E' …", il volto fissato in [person]; null = lapide. */
  data class Is(val face: FaceLocator, val person: PersonId?) : PeopleEdit {
    override val subject: String get() = face.encode()
    override val field: String get() = "is"
    override val value: String? get() = person?.base32()

    override fun toString(): String = "PeopleEdit.Is(${person != null})"
  }

  /** `not:<persona>`: "Non e' …", il volto escluso per sempre da [person]; [not] falso = lapide. */
  data class Not(val face: FaceLocator, val person: PersonId, val not: Boolean) : PeopleEdit {
    override val subject: String get() = face.encode()
    override val field: String get() = "not:${person.base32()}"
    override val value: String? get() = if (not) "true" else null

    override fun toString(): String = "PeopleEdit.Not($not)"
  }

  /** `ignore`: "Ignora questo volto" (poster, TV, statue: E-F3); [ignored] falso = lapide. */
  data class Ignore(val face: FaceLocator, val ignored: Boolean) : PeopleEdit {
    override val subject: String get() = face.encode()
    override val field: String get() = "ignore"
    override val value: String? get() = if (ignored) "true" else null

    override fun toString(): String = "PeopleEdit.Ignore($ignored)"
  }
}
