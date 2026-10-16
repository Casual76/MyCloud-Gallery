package dev.pampa.mycloudgallery.core.personal.contract

import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest

// Fase 4B (docs/fase4-progetto.md §2.9, D26, L29; formato-nas.md §5.5 classe `personal`, M7): i tipi dei dati personali sincronizzati.
// Tipi e regole pure di P0-B; il codec della classe `personal` (voce <-> record da 4 KiB), la pubblicazione e l'integrazione col
// catalogo sono di P13.
//
// Un dato personale e' una *voce*: (tipo, soggetto, campo) -> valore, con l'ora del NAS della modifica e il dispositivo autore. Lo
// stato di un account e' l'insieme delle voci con, per ogni chiave, quella piu' recente.

/**
 * Che cosa descrive una voce. I codici non cambiano. [PERSON] e' riservato per la Fase 8 (nomi delle persone, E-F5): oggi una voce di
 * quel tipo si rifiuta ([PersonalVerdict.RESERVED_KIND]).
 */
enum class PersonalKind(override val code: String, val reserved: Boolean = false) : StableCode {
  /** Un flag di un media: preferito, nascosto. Soggetto = id del media, campo = [PersonalFields.FAVORITE] o [PersonalFields.HIDDEN]. */
  MEDIA_FLAG("media_flag"),

  /** Un album manuale: definizione, ordine, copertina, elementi. Soggetto = id dell'album. */
  ALBUM("album"),

  /** L'ultima visione di un ricordo (campo pronto per la Fase 9). */
  MEMORY("memory"),

  /**
   * Un'impostazione personale (esclusioni dell'area privata, "Mostra le copertine"). Soggetto = [PersonalFields.SETTINGS_SUBJECT],
   * campo = la chiave dell'impostazione (per un insieme, una voce per elemento: [PersonalFields.privateExclusion]).
   */
  SETTING("setting"),

  /** Riservato (Fase 8). */
  PERSON("person", reserved = true),
}

/** I campi noti. Una voce con un campo qualunque e' valida: il codec li limita (lunghezza e caratteri). */
object PersonalFields {
  const val FAVORITE: String = "favorite"
  const val HIDDEN: String = "hidden"

  const val ALBUM_DEFINITION: String = "definition"
  const val ALBUM_ORDER: String = "order"
  const val ALBUM_COVER: String = "cover"

  /** Un elemento di un album: `item:<id del media>`. */
  fun albumItem(mediaId: String): String = "item:$mediaId"

  const val MEMORY_SEEN: String = "seen"

  /** Il soggetto di ogni impostazione: la chiave dell'impostazione sta nel campo. */
  const val SETTINGS_SUBJECT: String = "settings"

  /** "Mostra le copertine degli album" (E-N14): un [PersonalValue.Flag]. */
  const val SETTING_SHOW_ALBUM_COVERS: String = "show_album_covers"

  /** Prefisso del campo di una cartella esclusa dalla scansione dell'area privata (E-N15): vedi [privateExclusion]. */
  const val SETTING_PRIVATE_EXCLUSIONS: String = "private_exclusions"

  /**
   * Il campo di una cartella esclusa: `private_exclusions:` + l'impronta del percorso (NFC e minuscolo, come la chiave dell'indice:
   * per Samba due nomi diversi solo per maiuscole sono la stessa cartella). Una voce per cartella, valore = il percorso
   * ([PersonalValue.Text]) o la lapide: due dispositivi che aggiungono cartelle diverse le tengono entrambe, e la cartella tolta su
   * uno si toglie ovunque. Un insieme intero in un solo valore perderebbe una delle due aggiunte.
   */
  fun privateExclusion(folder: SharePath): String {
    val digest = Hashes.sha256("mcg-excl\u0000".toByteArray(Charsets.UTF_8), IndexKeys.foldPath(folder.value).toByteArray(Charsets.UTF_8))
    return "$SETTING_PRIVATE_EXCLUSIONS:${Base32.encode(digest)}"
  }

  /** Vero se [field] e' il campo di una cartella esclusa. */
  fun isPrivateExclusion(field: String): Boolean = field.startsWith("$SETTING_PRIVATE_EXCLUSIONS:")

  /** Vero se [field] e' un elemento di un album ([albumItem]). */
  fun isAlbumItem(field: String): Boolean = field.startsWith("item:")

  /** L'id del media di un elemento di album, o null se [field] non e' un elemento. */
  fun albumItemMedia(field: String): String? = field.takeIf { isAlbumItem(it) }?.substring("item:".length)?.takeIf { it.isNotEmpty() }

  // --- Fase 5, D36 (docs/fase5-progetto.md §2.12): le scelte dell'utente sulle copie simili. Voci `MEDIA_FLAG` (un flag di un
  // media, come preferito e nascosto) con campi nuovi: il formato della classe `personal` NON cambia (il tipo si riconosce dalla
  // chiave, e `MEDIA_FLAG` c'e' gia'; una versione precedente dell'app le legge e le lascia stare, la proiezione sul catalogo
  // risponde NOT_PROJECTED ai campi che non conosce). Le costruisce [SimilarCopiesRecords]; le proietta sul catalogo P15.

  /** "Mostra questa come principale": [PersonalValue.Flag] vero sulla copia scelta; fra due scelte vince la piu' recente. */
  const val SIMILAR_COVER: String = "similar_cover"

  /** Prefisso del campo di una separazione: vedi [similarApart]. */
  const val SIMILAR_APART: String = "similar_apart"

  /**
   * Il campo della separazione da [otherMediaId] ("Separa", E-L13): `similar_apart:<id dell'altro media>`. Una voce per coppia,
   * sul media minore: la separazione e' simmetrica e vale per sempre ([PersonalValue.Removed] la toglie).
   */
  fun similarApart(otherMediaId: String): String = "$SIMILAR_APART:$otherMediaId"

  /** Vero se [field] e' una separazione fra copie simili. */
  fun isSimilarApart(field: String): Boolean = field.startsWith("$SIMILAR_APART:")

  /** L'altro media di una separazione, o null se [field] non e' una separazione. */
  fun similarApartOther(field: String): String? =
    field.takeIf { isSimilarApart(it) }?.substring(SIMILAR_APART.length + 1)?.takeIf { it.isNotEmpty() }
}

/**
 * Le chiavi delle due scelte dell'utente sulle copie simili (D36, §2.12), come **firme**: la logica (quale copertina vince, quando un
 * gruppo si scioglie, la proiezione sulle tabelle locali di P1) e' di P15. Nessuna versione nuova del formato: sono voci `MEDIA_FLAG`
 * con i campi di [PersonalFields] (vedi la nota li').
 */
object SimilarCopiesRecords {

  /** La voce "questa copia e' la principale" di [mediaId] (valore: [PersonalValue.Flag]). */
  fun cover(mediaId: String): PersonalKey = PersonalKey(PersonalKind.MEDIA_FLAG, mediaId, PersonalFields.SIMILAR_COVER)

  /**
   * La voce "[a] e [b] non sono copie simili" (valore: [PersonalValue.Flag] vero; lapide = di nuovo unibili). Simmetrica: la stessa
   * chiave per (a, b) e (b, a), sul media minore, cosi' due dispositivi che separano la stessa coppia scrivono la stessa voce.
   */
  fun separation(a: String, b: String): PersonalKey {
    require(a != b) { "Una separazione e' fra due media diversi." }
    val (low, high) = if (a < b) a to b else b to a
    return PersonalKey(PersonalKind.MEDIA_FLAG, low, PersonalFields.similarApart(high))
  }

  /** La coppia separata da [key], o null se [key] non e' una separazione. */
  fun separatedPair(key: PersonalKey): Pair<String, String>? {
    if (key.kind != PersonalKind.MEDIA_FLAG) return null
    val other = PersonalFields.similarApartOther(key.field) ?: return null
    return key.subject to other
  }
}

/**
 * La chiave di una voce: dove vince l'ultima modifica. [digest] e' la chiave dentro il segmento della classe `personal`
 * (formato §5.5-bis): `SHA-256("mcg-pers\0" ‖ tipo ‖ \0 ‖ soggetto ‖ \0 ‖ campo)`. I tre pezzi non contengono mai `\0`.
 */
data class PersonalKey(val kind: PersonalKind, val subject: String, val field: String) {
  init {
    require(subject.isNotEmpty() && subject.length <= MAX_PART && '\u0000' !in subject) { "Soggetto non valido." }
    require(field.isNotEmpty() && field.length <= MAX_PART && '\u0000' !in field) { "Campo non valido." }
  }

  fun digest(): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    md.update("mcg-pers\u0000".toByteArray(Charsets.UTF_8))
    md.update(kind.code.toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update(subject.toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update(field.toByteArray(Charsets.UTF_8))
    return md.digest()
  }

  /** Mai il soggetto nei log: e' l'id di un media o di un album dell'utente. */
  override fun toString(): String = "PersonalKey(${kind.code})"

  companion object {
    const val MAX_PART: Int = 128
  }
}

/** Il valore di una voce. [Removed] e' la lapide: l'elemento non c'e' piu' (album cancellato, elemento tolto da un album). */
sealed interface PersonalValue {
  data class Flag(val on: Boolean) : PersonalValue

  data class Text(val text: String) : PersonalValue {
    init {
      require(text.length <= MAX_TEXT) { "Testo troppo lungo." }
    }

    override fun toString(): String = "Text"
  }

  data class Number(val value: Long) : PersonalValue

  data object Removed : PersonalValue

  companion object {
    const val MAX_TEXT: Int = 1024
  }
}

/** Dimensione e `qh1` del media, per ritrovarlo se le identita' divergono fra dispositivi (§2.9). */
data class PersonalFingerprint(val size: Long, val quickHash: QuickHash) {
  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  /** Mai l'impronta di un media dell'utente nei log. */
  override fun toString(): String = "PersonalFingerprint"
}

/**
 * Una voce. [atNas] e' l'ora del NAS della modifica (D32: mai l'orologio del telefono); [author] e' il dispositivo che l'ha fatta.
 * Una modifica fatta senza ora del NAS ancorata non ha ancora un [atNas]: sta nello store come "da datare" ([PersonalStore.undated]).
 */
data class PersonalEntry(
  val key: PersonalKey,
  val atNas: Long,
  val author: String,
  val value: PersonalValue,
  val fingerprint: PersonalFingerprint? = null,
) {
  init {
    require(TrustIds.isValid(author)) { "L'autore e' un id di dispositivo." }
    require(atNas >= 0) { "Ora negativa." }
  }

  override fun toString(): String = "PersonalEntry(${key.kind.code}, at=$atNas)"
}

/**
 * Perche' una voce vale o no (D26, §2.9 "Validita'"). Le prime quattro sono il filtro di chi legge; [RESERVED_KIND] e' il tipo della
 * Fase 8.
 */
enum class PersonalVerdict(override val code: String) : StableCode {
  VALID("valid"),

  /** L'autore non e' un dispositivo approvato di questo account (un altro account, un dispositivo in attesa o sconosciuto). */
  AUTHOR_NOT_ALLOWED("author_not_allowed"),

  /** L'autore e' revocato: la voce si scarta anche se la ripubblica un altro dispositivo. */
  AUTHOR_REVOKED("author_revoked"),

  /** `atNas` oltre 2 minuti nel futuro dell'ora del NAS. */
  FROM_FUTURE("from_future"),

  /** La voce non sta nell'area privata dichiarata localmente per questo account (l'area comune non conta, C19). */
  NOT_IN_PRIVATE_AREA("not_in_private_area"),
  RESERVED_KIND("reserved_kind"),
}

/** Chi e' ammesso a scrivere voci in questo account, e che ora e' sul NAS. */
data class PersonalRules(
  val nasNowMs: Long,
  /** I dispositivi approvati **di questo account** (registro accettato). */
  val approvedAuthors: Set<String>,
  val revokedAuthors: Set<String>,
)

object PersonalValidity {

  /** Il verdetto su [entry] letta da un file che sta (o no) nell'area privata dichiarata. */
  fun check(entry: PersonalEntry, rules: PersonalRules, inDeclaredPrivateArea: Boolean): PersonalVerdict = when {
    !inDeclaredPrivateArea -> PersonalVerdict.NOT_IN_PRIVATE_AREA
    entry.key.kind.reserved -> PersonalVerdict.RESERVED_KIND
    entry.author in rules.revokedAuthors -> PersonalVerdict.AUTHOR_REVOKED
    entry.author !in rules.approvedAuthors -> PersonalVerdict.AUTHOR_NOT_ALLOWED
    entry.atNas > rules.nasNowMs + TrustLimits.MAX_FUTURE_SKEW_MILLIS -> PersonalVerdict.FROM_FUTURE
    else -> PersonalVerdict.VALID
  }
}

/**
 * La fusione (D26): per ogni chiave vince la voce con `(atNas, autore)` piu' grande, a pari ora l'autore **minore**; a parita' di
 * tutto vince il valore "maggiore" ([compareValues]: falso < vero) e infine l'impronta del media. E' un ordine **totale** sulle voci
 * della stessa chiave, quindi la fusione e' commutativa, associativa e idempotente: qualunque ordine di arrivo e qualunque
 * raggruppamento, stesso stato (provato con permutazioni casuali in `PersonalMergeTest`). Le voci qui dentro sono gia' state
 * giudicate valide ([PersonalValidity]).
 */
object PersonalMerge {

  fun compare(a: PersonalEntry, b: PersonalEntry): Int {
    a.atNas.compareTo(b.atNas).let { if (it != 0) return it }
    // A pari ora vince l'autore minore (D26): per questo il confronto sull'autore e' invertito.
    b.author.compareTo(a.author).let { if (it != 0) return it }
    compareValues(a.value, b.value).let { if (it != 0) return it }
    return compareFingerprints(a.fingerprint, b.fingerprint)
  }

  fun winner(a: PersonalEntry, b: PersonalEntry): PersonalEntry = if (compare(a, b) >= 0) a else b

  /** Unisce [incoming] allo stato [current]. */
  fun merge(current: Map<PersonalKey, PersonalEntry>, incoming: Iterable<PersonalEntry>): Map<PersonalKey, PersonalEntry> {
    val result = HashMap(current)
    for (entry in incoming) {
      val existing = result[entry.key]
      result[entry.key] = if (existing == null) entry else winner(existing, entry)
    }
    return result
  }

  /**
   * L'ordine dei valori a parita' di ora e di autore: flag (falso prima), numeri, lapide, testi. Nessuna conversione in testo
   * (un numero negativo non deve ordinarsi male): e' un ordine totale perche' ogni valore ha un solo rango e dentro il rango un
   * solo confronto.
   */
  fun compareValues(a: PersonalValue, b: PersonalValue): Int {
    val byRank = rank(a).compareTo(rank(b))
    if (byRank != 0) return byRank
    return when (a) {
      is PersonalValue.Flag -> a.on.compareTo((b as PersonalValue.Flag).on)
      is PersonalValue.Number -> a.value.compareTo((b as PersonalValue.Number).value)
      is PersonalValue.Text -> a.text.compareTo((b as PersonalValue.Text).text)
      PersonalValue.Removed -> 0
    }
  }

  private fun rank(value: PersonalValue): Int = when (value) {
    is PersonalValue.Flag -> 0
    is PersonalValue.Number -> 1
    PersonalValue.Removed -> 2
    is PersonalValue.Text -> 3
  }

  /** Senza impronta prima di con impronta; poi dimensione e `qh1`. */
  private fun compareFingerprints(a: PersonalFingerprint?, b: PersonalFingerprint?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    else -> a.size.compareTo(b.size).let { if (it != 0) it else a.quickHash.hex.compareTo(b.quickHash.hex) }
  }
}
