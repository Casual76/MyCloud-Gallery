package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.Fields
import dev.pampa.mycloudgallery.core.trust.codec.JsonObject
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.ensure
import dev.pampa.mycloudgallery.core.trust.violation

// LE VOCI `people` (classe 20; Fase 8, P3; docs/fase8-progetto.md §2.4 "Voci people", D13, D23, D24): nomi e correzioni delle persone,
// JSON canonico `{at, author, field, subject, value?}` (valore assente = lapide), una voce per chiave
// SHA-256("mcg-people\0" ‖ soggetto ‖ "\0" ‖ campo). Il codec controlla la FORMA di ogni campo (un campo sconosciuto o un valore d'altra
// forma rifiuta il segmento); chi legge giudica poi l'autore (un dispositivo dell'account, mai revocato) e l'ora (non nel futuro) voce per
// voce, come i dati personali. Mai un nome nei `toString()` (D37).

/** Le forme dei campi `people` (§2.4): le stesse in scrittura e in lettura, e le stesse di `PeopleEdit` e `PersonNames` di core-faces. */
object PeopleForms {
  const val NAME: String = "name"
  const val HIDDEN: String = "hidden"
  const val FORGET: String = "forget"
  const val ALIAS: String = "alias"
  const val BORN: String = "born"
  const val COVER: String = "cover"
  const val IS: String = "is"
  const val IGNORE: String = "ignore"
  const val APART_PREFIX: String = "apart:"
  const val NOT_PREFIX: String = "not:"
  const val SPLIT_PREFIX: String = "split:"
  const val TRUE: String = "true"
  const val FALSE: String = "false"

  /** Il campo piu' lungo e' `apart:` + 26 caratteri. */
  const val MAX_FIELD_CHARS: Int = 32

  /** Il soggetto o il valore piu' lungo e' un localizzatore (D23). */
  const val MAX_TEXT_CHARS: Int = TrustLimits.MAX_FACE_LOCATOR_CHARS

  /** Una persona nella sua forma dei file: 26 caratteri base32 canonici, mai l'id riservato tutto zero. */
  fun isPerson(text: String): Boolean = text.length == 26 && PersonId.fromBase32(text)?.isZero() == false

  /** Un localizzatore nella sua sola scrittura (D23): quella che `FaceLocator.encode` produce. */
  fun isLocator(text: String): Boolean = text.length <= TrustLimits.MAX_FACE_LOCATOR_CHARS && FaceLocator.parse(text)?.encode() == text

  /**
   * Il nome di una persona (D24): da 1 a 64 caratteri, senza spazi ai bordi, nessun carattere che `TextRules` vieta (controlli, direzione,
   * invisibili, surrogati spaiati). E' la regola di `PersonNames.normalize` di core-faces (lo prova un test di core-faces).
   */
  fun isName(text: String): Boolean =
    text.isNotEmpty() && text.length <= TrustLimits.MAX_PERSON_NAME_CHARS && text.trim() == text && !TextRules.hasForbidden(text)

  /** Vero se ([subject], [field], [value]) e' una voce del formato (§2.4); null = lapide, ammessa solo dove ha un senso. */
  fun fits(subject: String, field: String, value: String?): Boolean = when {
    field == NAME -> isPerson(subject) && (value == null || isName(value))
    field == HIDDEN -> isPerson(subject) && (value == TRUE || value == FALSE)
    field == FORGET -> isPerson(subject) && value == TRUE
    field == ALIAS -> isPerson(subject) && value != null && isPerson(value) && value != subject
    field == BORN -> isPerson(subject) && value != null && value.startsWith(SPLIT_PREFIX) &&
      value.substring(SPLIT_PREFIX.length).let { isPerson(it) && it != subject }
    field == COVER -> isPerson(subject) && (value == null || isLocator(value))
    field.startsWith(APART_PREFIX) -> isPerson(subject) && field.substring(APART_PREFIX.length).let { isPerson(it) && it != subject } &&
      (value == null || value == TRUE)
    field == IS -> isLocator(subject) && (value == null || isPerson(value))
    field.startsWith(NOT_PREFIX) -> isLocator(subject) && isPerson(field.substring(NOT_PREFIX.length)) && (value == null || value == TRUE)
    field == IGNORE -> isLocator(subject) && (value == null || value == TRUE)
    else -> false
  }

  private val DOMAIN: ByteArray = "mcg-people".toByteArray(Charsets.US_ASCII) + 0

  /** La chiave di una voce: SHA-256("mcg-people\0" ‖ soggetto ‖ "\0" ‖ campo), in UTF-8. */
  fun key(subject: String, field: String): ByteArray =
    Hashes.sha256(DOMAIN, subject.toByteArray(Charsets.UTF_8), byteArrayOf(0), field.toByteArray(Charsets.UTF_8))
}

/**
 * Una voce `people` come sta nel file (§2.4): [subject] e [field] nelle forme del formato, [value] (null = lapide), [at] l'ora del NAS della
 * modifica (dal 2020 al 2100), [author] il dispositivo che l'ha fatta. Ogni dispositivo ripubblica lo **stato unito** (F4-D26): l'autore non e'
 * per forza chi firma la testa; lo giudica chi legge (un dispositivo approvato o dismesso dell'account, mai revocato).
 */
data class PeopleDoc(val subject: String, val field: String, val value: String?, val at: Long, val author: String) {
  init {
    ensure(at in TrustLimits.MIN_PLAUSIBLE_TIME_MILLIS..TrustLimits.MAX_PLAUSIBLE_TIME_MILLIS, RejectReason.INVALID_FIELD, "voce people: ora")
    ensure(TrustIds.isValid(author), RejectReason.INVALID_FIELD, "voce people: autore")
    ensure(PeopleForms.fits(subject, field, value), RejectReason.INVALID_FIELD, "voce people: campo o valore d'altra forma")
  }

  /** La chiave della voce (32 byte): l'ultima scrittura vince per chiave. */
  val key: ByteArray get() = PeopleForms.key(subject, this.field)

  override fun toString(): String = "PeopleDoc(lapide=${value == null})"
}

/** Il JSON canonico delle voci `people` (al piu' `MAX_PEOPLE_ENTRY_BYTES`). */
internal object PeopleJson {

  fun write(doc: PeopleDoc): ByteArray {
    val bytes = CanonicalJson.encode(
      jsonObject(
        "at" to doc.at.json(),
        "author" to doc.author.json(),
        "field" to doc.field.json(),
        "subject" to doc.subject.json(),
        "value" to doc.value?.json(),
      ),
    )
    require(bytes.size <= TrustLimits.MAX_PEOPLE_ENTRY_BYTES) { "Voce people oltre il tetto." }
    return bytes
  }

  fun read(json: ByteArray): PeopleDoc {
    ensure(json.size <= TrustLimits.MAX_PEOPLE_ENTRY_BYTES, RejectReason.TOO_LARGE, "voce people: oltre il tetto")
    val obj = CanonicalJson.parseCanonical(json) as? JsonObject ?: violation(RejectReason.MALFORMED, "voce people: oggetto atteso")
    return Fields.read(obj) { f ->
      PeopleDoc(
        at = f.time("at"),
        author = f.string("author", TrustIds.LENGTH),
        field = f.string("field", PeopleForms.MAX_FIELD_CHARS),
        subject = f.string("subject", PeopleForms.MAX_TEXT_CHARS),
        value = f.optString("value", PeopleForms.MAX_TEXT_CHARS),
      )
    }
  }
}
