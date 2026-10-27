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
 * Che cosa descrive una voce. I codici non cambiano. [PERSON] e' riservato (era pensato per i nomi delle persone della Fase 8, E-F5): una
 * voce di quel tipo si rifiuta in lettura ([PersonalVerdict.RESERVED_KIND]) e lo store non la accetta ne' da qui ne' da fuori, quindi non
 * si pubblica mai (`Phase8FacesContractTest`). Fase 8, D13: i nomi e le correzioni stanno nella classe `people` sotto `faces/<G>/`
 * dell'area privata, cosi' "Disattiva e cancella" li toglie con la cartella della generazione, anche dalle teste dei dispositivi spenti.
 */
enum class PersonalKind(override val code: String, val reserved: Boolean = false) : StableCode {
  /** Un flag di un media: preferito, nascosto. Soggetto = id del media, campo = [PersonalFields.FAVORITE] o [PersonalFields.HIDDEN]. */
  MEDIA_FLAG("media_flag"),

  /** Un album manuale: definizione, ordine, copertina, elementi. Soggetto = id dell'album. */
  ALBUM("album"),

  /**
   * Un ricordo (Fase 9, D10). Soggetto = l'id del ricordo (26 caratteri base32, D9); campi [PersonalFields.MEMORY_SEEN] (giorno locale della
   * visione) e [PersonalFields.MEMORY_DISMISSED] ("Non mostrare"). Nessuna impronta.
   */
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

  /**
   * `MEMORY`: l'ultima visione di un ricordo. Dalla Fase 9 (D10, formato §5.5-bis A1) il valore e' il **giorno locale** della visione
   * (`aaaammgg`, [PersonalValue.Number]), non l'ora del NAS: chi segna "visto" fuori casa non la conosce, e l'ora della modifica la porta gia'
   * la voce. Nessuna voce `seen` e' stata scritta prima della Fase 9.
   */
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

  // --- Fase 6 (docs/fase6-progetto.md §1.3 "core-personal", D41, D48, D50; risposte Q4, Q10, Q13, Q16). Firme di P0: le voci
  // si leggono e si scrivono con `SearchPersonalSettings` e `MotionPersonalSettings` (contract/SearchPersonalSettings.kt).

  /**
   * La lettura del testo nelle foto **per l'account** (D41, Q13): un [PersonalValue.Number]: 0 = spenta, n > 0 = attiva con la
   * versione n del testo della pagina di attivazione accettato (se il testo cambia, si richiede). Voce assente = spenta (default).
   * L'esclusione di un singolo dispositivo ("Non su questo dispositivo") e' **locale**, non una voce.
   */
  const val SETTING_TEXT_READING: String = "text_reading"

  /** L'ordine dei risultati (D48, Q10): un [PersonalValue.Text], `recent` (default) o `relevant`. */
  const val SETTING_SEARCH_ORDER: String = "search_order"

  /**
   * "Animazione delle foto in movimento" nelle Impostazioni della Libreria (D50, risposta Q16 (b)): un [PersonalValue.Flag].
   * Voce assente = **accesa** (default). Si somma alle condizioni gia' decise ("Rimuovi animazioni", risparmio energetico).
   */
  const val SETTING_MOTION_ANIMATION: String = "motion_animation"

  // --- Fase 7 (docs/fase7-progetto.md D4, D20, D21, D27, D31, D33, risposta Q5 del 22/10). Firme di P0: **campi nuovi dentro i tipi
  // esistenti** `ALBUM`, `SETTING` e `MEDIA_FLAG`, nessun `PersonalKind` nuovo (un tipo nuovo farebbe scartare il segmento intero alle
  // app vecchie: Phase7ArchitectureTest lo vieta). Nessuna versione nuova del formato: un'app della Fase 6 legge e conserva questi campi
  // senza capirli. I lettori (dal valore al tipo dell'album) sono di P3, le impostazioni delle Raccolte di P11, le scritture di P3, P4,
  // P6, P7, P8. **Ogni voce `item:`, `exclude:`, `accept:`, `smart:example:` porta l'impronta** (`PersonalEntry.fingerprint`, D4).

  /** `ALBUM`: `manual` o `smart` (`AlbumKind.code`); assente = `manual` (gli album della Fase 4). */
  const val ALBUM_KIND: String = "kind"

  /** `ALBUM`: l'ordine dentro l'album (`AlbumSort.code`); assente = il predefinito del tipo (D21). */
  const val ALBUM_SORT: String = "sort"

  /** `ALBUM` smart: il periodo (giorni locali dello scatto, D13, D36), un testo nel formato di P6. */
  const val SMART_PERIOD: String = "smart:period"

  /** `ALBUM` smart: il luogo (id GeoNames, centro, raggio, etichetta), un testo nel formato di P6. */
  const val SMART_PLACE: String = "smart:place"

  /** `ALBUM` smart: la descrizione per la ricerca per contenuto (D15). */
  const val SMART_TEXT: String = "smart:text"

  /** `ALBUM` smart: le sorgenti ammesse (`SmartSource`), un testo con i codici. */
  const val SMART_SOURCES: String = "smart:sources"

  /** `ALBUM` smart: i tipi ammessi (foto, video), un testo con i codici. */
  const val SMART_TYPES: String = "smart:types"

  /**
   * `ALBUM` smart (Fase 8, D27, F7-D4: un campo nuovo dentro `ALBUM`, nessun tipo nuovo): le persone della regola, `any:<id>,<id>` (una qualunque) o
   * `all:<id>,...` (tutte), gli id in base32 (26 caratteri); al piu' 32 persone. Un'app senza la Fase 8 conserva la voce senza capirla.
   */
  const val SMART_PEOPLE: String = "smart:people"

  /** Prefisso di una foto d'esempio (al piu' 6, D13): vedi [smartExample]. */
  const val SMART_EXAMPLE: String = "smart:example"

  /** Prefisso di un'esclusione per sempre (E-AL5): vedi [albumExclude]. */
  const val ALBUM_EXCLUDE: String = "exclude"

  /** Prefisso di un'accettazione di un suggerito (D14): vedi [albumAccept]. */
  const val ALBUM_ACCEPT: String = "accept"

  /** `ALBUM` smart: "Congela" (D16), un [PersonalValue.Flag]. */
  const val ALBUM_FROZEN: String = "frozen"

  /** `ALBUM`: l'id dell'album condiviso nato da questo album personale (D4, D17), un testo. */
  const val ALBUM_SHARED: String = "shared"

  /**
   * `ALBUM`: l'id dell'album condiviso di cui questo album personale e' la **copia** (risposta Q5 (b) del 22/10, D31 adeguata): quando
   * il creatore smette di condividere o elimina l'album scegliendo "ognuno tiene una copia", ogni familiare (e il creatore dopo
   * "Smetti di condividere") ha un album personale con gli stessi elementi, finche' i file restano nell'area comune. Un testo.
   */
  const val ALBUM_COPY_OF: String = "copy_of"

  /** Una foto d'esempio di un album smart: `smart:example:<id del media>` (con l'impronta, D4). */
  fun smartExample(mediaId: String): String = "$SMART_EXAMPLE:$mediaId"

  /** Un'esclusione per sempre da un album smart: `exclude:<id del media>` (con l'impronta). */
  fun albumExclude(mediaId: String): String = "$ALBUM_EXCLUDE:$mediaId"

  /** Un suggerito accettato in un album smart: `accept:<id del media>` (con l'impronta). */
  fun albumAccept(mediaId: String): String = "$ALBUM_ACCEPT:$mediaId"

  /** `SETTING`: l'ordine delle sezioni delle Raccolte (D33), un testo con i codici delle sezioni. */
  const val SETTING_COLLECTIONS_ORDER: String = "collections_order"

  /** Prefisso della visibilita' di una sezione delle Raccolte (D33, E-L14): vedi [collectionsHidden]. */
  const val SETTING_COLLECTIONS_HIDDEN: String = "collections_hidden"

  /** `SETTING`: l'ordine dell'elenco degli album (`AlbumListSort.code`, D21). */
  const val SETTING_ALBUMS_SORT: String = "albums_sort"

  /** Prefisso di una proposta di viaggio scartata ("Non mi interessa", D20): vedi [tripDismissed]. */
  const val SETTING_TRIP_DISMISSED: String = "trip_dismissed"

  /** Prefisso di un album condiviso lasciato ("Non mostrare piu'", E-SH21): vedi [sharedAlbumLeft]. */
  const val SETTING_SHARED_ALBUM_LEFT: String = "shared_album_left"

  /** `SETTING`: l'hash completo dei video del NAS "per tutti, di notte" (D27, Q10 della Fase 4), un [PersonalValue.Flag]; assente = spento. */
  const val SETTING_FULL_HASH_NIGHTLY: String = "full_hash_nightly"

  /**
   * Fase 8 (D3, formato §5.5-bis A4): `SETTING`, Persone **per l'account**, un [PersonalValue.Text] `1|on|<G>|<v>` o `1|off|<G>` (codec:
   * [FacesSettingCodec]). Un campo nuovo dentro `SETTING`, nessun `PersonalKind` nuovo: un'app senza la Fase 8 la conserva senza capirla
   * (E-F11). L'esclusione di un dispositivo ("Non su questo dispositivo", D4) e' **locale**, mai una voce. I nomi delle persone **non**
   * sono dati personali (D13): `PERSON` resta riservato e non si pubblica mai.
   */
  const val SETTING_FACES: String = "faces"

  // --- Fase 9 (docs/fase9-progetto.md D10, D11, risposta Q9 (a); formato-nas.md §5.5-bis A1, A2). Firme di P0: **campi nuovi dentro i tipi
  // esistenti** `MEMORY` e `SETTING`, nessun `PersonalKind` nuovo (Phase7ArchitectureTest): un'app della Fase 8 li conserva senza capirli
  // (`Phase9MemoriesFieldsTest`). Chi li legge e li scrive e' `MemoriesRecords` (contract/MemoriesPersonal.kt); **mai un nome**: le persone
  // sono il loro id casuale in base32 (`PersonId.base32`), i ricordi l'id deterministico del ricordo (26 caratteri base32, D9).

  /** `MEMORY`: "Non mostrare questo ricordo" (D10), un [PersonalValue.Flag] vero; la lapide e' "Annulla" (entro 10 s). Nessuna impronta. */
  const val MEMORY_DISMISSED: String = "dismissed"

  /** Prefisso di "Mostra meno" e "Mai nei ricordi" per una persona (D11): vedi [memoriesLessPerson]. */
  const val SETTING_MEMORIES_LESS: String = "memories_less"

  /** Prefisso di "Mai nei ricordi" per un periodo (D11): vedi [memoriesNeverDays]. */
  const val SETTING_MEMORIES_NEVER: String = "memories_never"

  /** `SETTING`: "Ricordi con le persone" (Q9 (a)), un [PersonalValue.Flag]; **assente = acceso**. */
  const val SETTING_MEMORIES_PEOPLE: String = "memories_people"

  /**
   * Il campo di "Mostra meno"/"Mai nei ricordi" per la persona [personBase32] (l'id della persona in base32, 26 caratteri): valore
   * `less` o `never` ([PersonalValue.Text]), lapide = scelta tolta. Mai il nome (D11).
   */
  fun memoriesLessPerson(personBase32: String): String = "$SETTING_MEMORIES_LESS:person:$personBase32"

  /** L'id in base32 della persona di un campo [memoriesLessPerson], o null se [field] non lo e'. */
  fun memoriesLessPersonOf(field: String): String? = after(field, "$SETTING_MEMORIES_LESS:person:")

  /** Il campo di "Mai nei ricordi" per i giorni locali da [from] a [to] (`aaaammgg`, compresi): `memories_never:days:<from>-<to>`. */
  fun memoriesNeverDays(from: Int, to: Int): String = "$SETTING_MEMORIES_NEVER:days:$from-$to"

  /** I due giorni (`aaaammgg`) di un campo [memoriesNeverDays], o null se [field] non lo e' (la validita' dei giorni la decide chi legge). */
  fun memoriesNeverDaysOf(field: String): Pair<Int, Int>? {
    val text = after(field, "$SETTING_MEMORIES_NEVER:days:") ?: return null
    val match = NEVER_DAYS.matchEntire(text) ?: return null
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
  }

  private val NEVER_DAYS = Regex("^([0-9]{8})-([0-9]{8})$")

  /** Una sezione delle Raccolte nascosta (valore [PersonalValue.Flag]): `collections_hidden:<codice della sezione>`. */
  fun collectionsHidden(section: String): String = "$SETTING_COLLECTIONS_HIDDEN:$section"

  /** Una proposta scartata: `trip_dismissed:<impronta di inizio, fine e luoghi>` (`TripId`), sincronizzata (una scartata non torna). */
  fun tripDismissed(tripId: String): String = "$SETTING_TRIP_DISMISSED:$tripId"

  /** Un album condiviso lasciato: `shared_album_left:<id dell'album>` (E-SH21: non si vede piu' su nessun dispositivo dell'account). */
  fun sharedAlbumLeft(albumId: String): String = "$SETTING_SHARED_ALBUM_LEFT:$albumId"

  /**
   * `MEDIA_FLAG`: la cartella d'origine (area privata, percorso relativo alla share) di un media **proprio** spostato in un album
   * condiviso (D31): serve a "Riporta nelle mie cartelle". Sta nei dati personali (area privata), **mai** nella definizione comune
   * (privacy dei percorsi, L60). Un testo.
   */
  const val SHARED_ORIGIN: String = "shared_origin"

  /** Vero se [field] e' un elemento di un album ([albumItem]). */
  fun isAlbumItem(field: String): Boolean = field.startsWith("item:")

  /** L'id del media di un elemento di album, o null se [field] non e' un elemento. */
  fun albumItemMedia(field: String): String? = field.takeIf { isAlbumItem(it) }?.substring("item:".length)?.takeIf { it.isNotEmpty() }

  // --- Fase 7, P3: i lettori dei campi D4. L'inverso dei costruttori qui sopra: dal nome del campo a cio' che nomina. Nessun tipo
  // nuovo e nessuna regola nuova sul formato; chi legge il valore (testo, numero, flag) e l'impronta e' PersonalAlbums.

  /** Il testo dopo [prefix] nel campo, o null se [field] non comincia cosi' o dopo non c'e' niente. */
  private fun after(field: String, prefix: String): String? = field.takeIf { it.startsWith(prefix) }?.substring(prefix.length)?.takeIf { it.isNotEmpty() }

  /** Vero se [field] e' una foto d'esempio di un album smart ([smartExample]). */
  fun isSmartExample(field: String): Boolean = field.startsWith("$SMART_EXAMPLE:")

  /** L'id del media di una foto d'esempio (`smart:example:<media>`), o null. */
  fun smartExampleMedia(field: String): String? = after(field, "$SMART_EXAMPLE:")

  /** Vero se [field] e' un'esclusione per sempre ([albumExclude]). */
  fun isAlbumExclude(field: String): Boolean = field.startsWith("$ALBUM_EXCLUDE:")

  /** L'id del media di un'esclusione (`exclude:<media>`), o null. */
  fun albumExcludeMedia(field: String): String? = after(field, "$ALBUM_EXCLUDE:")

  /** Vero se [field] e' un suggerito accettato ([albumAccept]). */
  fun isAlbumAccept(field: String): Boolean = field.startsWith("$ALBUM_ACCEPT:")

  /** L'id del media di un suggerito accettato (`accept:<media>`), o null. */
  fun albumAcceptMedia(field: String): String? = after(field, "$ALBUM_ACCEPT:")

  /** I campi `smart:*` che portano una regola intera (un testo); le foto d'esempio sono voci a parte ([smartExample]). */
  val SMART_RULE_FIELDS: List<String> = listOf(SMART_PERIOD, SMART_PLACE, SMART_TEXT, SMART_SOURCES, SMART_TYPES, SMART_PEOPLE)

  /** Il codice della sezione delle Raccolte di una visibilita' (`collections_hidden:<sezione>`), o null. */
  fun collectionsHiddenSection(field: String): String? = after(field, "$SETTING_COLLECTIONS_HIDDEN:")

  /** L'id di una proposta di viaggio scartata (`trip_dismissed:<id>`), o null. */
  fun tripDismissedId(field: String): String? = after(field, "$SETTING_TRIP_DISMISSED:")

  /** L'id dell'album condiviso lasciato (`shared_album_left:<id>`), o null. */
  fun sharedAlbumLeftId(field: String): String? = after(field, "$SETTING_SHARED_ALBUM_LEFT:")

  // --- Fase 5, D36 (docs/fase5-progetto.md §2.12): le scelte dell'utente sulle copie simili. Voci `MEDIA_FLAG` (un flag di un
  // media, come preferito e nascosto) con campi nuovi: il formato della classe `personal` NON cambia (il tipo si riconosce dalla
  // chiave, e `MEDIA_FLAG` c'e' gia'; una versione precedente dell'app le legge e le lascia stare, la proiezione sul catalogo
  // risponde NOT_PROJECTED ai campi che non conosce). Le costruisce [SimilarCopiesRecords]; le proietta sul catalogo P15.
  // 20/10: il valore di una separazione porta l'impronta dell'altro media ([PersonalValue.Apart]), un testo per il formato: vedi li'.

  /** "Mostra questa come principale": [PersonalValue.Flag] vero sulla copia scelta; fra due scelte vince la piu' recente. */
  const val SIMILAR_COVER: String = "similar_cover"

  /** Prefisso del campo di una separazione: vedi [similarApart]. */
  const val SIMILAR_APART: String = "similar_apart"

  /**
   * Il campo della separazione da [otherMediaId] ("Separa", E-L13): `similar_apart:<id dell'altro media>`. Una voce per coppia,
   * sul media minore: la separazione e' simmetrica e vale per sempre ([PersonalValue.Removed] la toglie). Il valore e'
   * [PersonalValue.Apart] (l'impronta dell'altro media), o `true` se la voce e' del formato di prima ([SimilarCopiesRecords.apart]).
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
 * con i campi di [PersonalFields] (vedi la nota li'), e il valore di una separazione e' un testo ([PersonalValue.Apart]).
 */
object SimilarCopiesRecords {

  /** La voce "questa copia e' la principale" di [mediaId] (valore: [PersonalValue.Flag]). */
  fun cover(mediaId: String): PersonalKey = PersonalKey(PersonalKind.MEDIA_FLAG, mediaId, PersonalFields.SIMILAR_COVER)

  /**
   * La voce "[a] e [b] non sono copie simili" (valore: [apart]; lapide = di nuovo unibili). Simmetrica: la stessa chiave per (a, b) e
   * (b, a), sul media minore, cosi' due dispositivi che separano la stessa coppia con gli stessi id scrivono la stessa voce.
   */
  fun separation(a: String, b: String): PersonalKey {
    require(a != b) { "Una separazione e' fra due media diversi." }
    val (low, high) = if (a < b) a to b else b to a
    return PersonalKey(PersonalKind.MEDIA_FLAG, low, PersonalFields.similarApart(high))
  }

  /** La coppia separata da [key] (il soggetto, poi l'altro media del campo), o null se [key] non e' una separazione. */
  fun separatedPair(key: PersonalKey): Pair<String, String>? {
    if (key.kind != PersonalKind.MEDIA_FLAG) return null
    val other = PersonalFields.similarApartOther(key.field) ?: return null
    return key.subject to other
  }

  /**
   * Il valore di una separazione: [PersonalValue.Apart] con l'impronta di [other], l'**altro** media (quello del campo; l'impronta del
   * soggetto la porta la voce, [PersonalEntry.fingerprint]). Senza impronta (un media che questo catalogo non conosce) resta il `true`
   * del formato di prima, che gli altri dispositivi ritrovano solo per id.
   */
  fun apart(other: PersonalFingerprint?): PersonalValue = if (other == null) PersonalValue.Flag(true) else PersonalValue.Apart(other)

  /** Vero se [value] separa: [PersonalValue.Apart] o il `true` del formato di prima. La lapide e il flag spento no (di nuovo unibili). */
  fun separates(value: PersonalValue): Boolean = value is PersonalValue.Apart || (value as? PersonalValue.Flag)?.on == true

  /** L'impronta dell'altro media di una separazione, se la voce la porta (null nel formato di prima: si ritrova solo per id). */
  fun otherFingerprint(value: PersonalValue): PersonalFingerprint? = (value as? PersonalValue.Apart)?.other
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

  /**
   * Una separazione fra copie simili ("Separa", D36), con l'impronta dell'**altro** media della coppia, quello che la chiave nomina nel
   * campo (`similar_apart:<id>`): un altro dispositivo, dove la stessa foto puo' avere un altro id (D7, D8), lo ritrova anche per
   * impronta, come ritrova il soggetto con [PersonalEntry.fingerprint]. Solo nelle voci di una separazione.
   *
   * Nel formato e' un **testo** ([text], §5.5-bis): nessun campo nuovo e nessun tipo nuovo di valore, quindi nessuna versione nuova.
   * Una versione precedente dell'app lo legge come un testo qualunque, non lo prende per una separazione (non separa niente) e non
   * scarta il segmento; questa versione legge come prima il `true` delle voci scritte prima (l'altro media solo per id).
   */
  data class Apart(val other: PersonalFingerprint) : PersonalValue {

    /** La forma nel formato: `qh1` in base32 (52 caratteri, come `fp.qh`), `:`, la dimensione in decimale (senza zeri davanti). */
    val text: String get() = "${Base32.encode(bytesOfHex(other.quickHash.hex))}:${other.size}"

    /** Mai l'impronta di un media dell'utente nei log. */
    override fun toString(): String = "Apart"

    companion object {
      private const val QUICK_HASH_BYTES = 32
      private val SIZE = Regex("^(0|[1-9][0-9]{0,18})$")

      /** Il valore scritto come [text], o null se [text] non ha esattamente quella forma (una sola scrittura per ogni impronta). */
      fun parse(text: String): Apart? {
        val colon = text.indexOf(':')
        if (colon != Base32.encodedLength(QUICK_HASH_BYTES)) return null
        val quick = text.substring(0, colon)
        val size = text.substring(colon + 1)
        if (!SIZE.matches(size) || !Base32.isCanonical(quick, QUICK_HASH_BYTES)) return null
        val bytes = size.toLongOrNull() ?: return null
        return Apart(PersonalFingerprint(bytes, QuickHash(hexOf(Base32.decodeExact(quick, QUICK_HASH_BYTES)))))
      }
    }
  }

  companion object {
    const val MAX_TEXT: Int = 1024
  }
}

private fun hexOf(bytes: ByteArray): String {
  val digits = "0123456789abcdef"
  val out = CharArray(bytes.size * 2)
  bytes.forEachIndexed { i, b ->
    val v = b.toInt() and 0xff
    out[i * 2] = digits[v ushr 4]
    out[i * 2 + 1] = digits[v and 0x0f]
  }
  return String(out)
}

private fun bytesOfHex(text: String): ByteArray = ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

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
    require(value !is PersonalValue.Apart || SimilarCopiesRecords.separatedPair(key) != null) { "L'impronta dell'altro media e' solo di una separazione." }
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
   * L'ordine dei valori a parita' di ora e di autore: flag (falso prima), numeri, lapide, testi, separazioni con l'impronta
   * ([PersonalValue.Apart], per la loro forma nel formato). Nessuna conversione in testo (un numero negativo non deve ordinarsi
   * male): e' un ordine totale perche' ogni valore ha un solo rango e dentro il rango un solo confronto.
   */
  fun compareValues(a: PersonalValue, b: PersonalValue): Int {
    val byRank = rank(a).compareTo(rank(b))
    if (byRank != 0) return byRank
    return when (a) {
      is PersonalValue.Flag -> a.on.compareTo((b as PersonalValue.Flag).on)
      is PersonalValue.Number -> a.value.compareTo((b as PersonalValue.Number).value)
      is PersonalValue.Text -> a.text.compareTo((b as PersonalValue.Text).text)
      is PersonalValue.Apart -> a.text.compareTo((b as PersonalValue.Apart).text)
      PersonalValue.Removed -> 0
    }
  }

  private fun rank(value: PersonalValue): Int = when (value) {
    is PersonalValue.Flag -> 0
    is PersonalValue.Number -> 1
    PersonalValue.Removed -> 2
    is PersonalValue.Text -> 3
    is PersonalValue.Apart -> 4
  }

  /** Senza impronta prima di con impronta; poi dimensione e `qh1`. */
  private fun compareFingerprints(a: PersonalFingerprint?, b: PersonalFingerprint?): Int = when {
    a == null && b == null -> 0
    a == null -> -1
    b == null -> 1
    else -> a.size.compareTo(b.size).let { if (it != 0) it else a.quickHash.hex.compareTo(b.quickHash.hex) }
  }
}
