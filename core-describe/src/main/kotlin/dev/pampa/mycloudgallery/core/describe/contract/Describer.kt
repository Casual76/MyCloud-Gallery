package dev.pampa.mycloudgallery.core.describe.contract

import dev.pampa.mycloudgallery.core.model.MediaId

// IL DESCRITTORE E LE SUE PORTE (docs/fase10-progetto.md §1.2 "Descrittore", "Porte", §2.6, D22; C438, L549, L550). Contratto di P0; il
// `DefaultMediaDescriber` vero e la ripulitura del testo sono di P7 (`rules/`) e passano la suite `MediaDescriberContract`. Le porte le lega :app
// (P13): `DescribePeople` sopra `PeopleVisibility` dei volti, `DescribeText` sopra la replica del testo letto, `A11yActivity` sopra
// `AccessibilityManager`.

/**
 * Le descrizioni (D22). [describe] e' **sincrona** e usa solo dati in memoria: i fatti, i luoghi offline e, se [warm] li ha gia' caricati, i
 * nomi e le prime parole del media. [warm] carica nomi e prime parole per le celle in vista **solo** con uno strumento di accessibilita' attivo
 * (`A11yActivity.toolEnabled`), in una cache di [DescribeLimits.WARM_CACHE] voci in memoria; [forget] la vuota (blocco dell'app, secondo piano).
 * Mai salvate, mai nei log, mai sulla Home oltre a tipo e data, mai nell'esportazione (la superficie non esiste).
 */
interface MediaDescriber {
  fun describe(id: MediaId, facts: MediaFacts, surface: DescribeSurface): String

  suspend fun warm(visible: List<MediaId>)

  fun forget()
}

/**
 * I nomi delle persone di ogni media (D22, E-AC2): **solo** se Persone e' attiva su questo dispositivo (`On` con `ACTIVE` o `LOCAL_ONLY`), solo
 * persone **con un nome, non nascoste, non dimenticate**, nell'ordine in cui vanno dette. Un media senza persone dicibili non compare nella
 * mappa. Un errore = mappa vuota. Il null-object non dice mai un nome.
 */
fun interface DescribePeople {
  suspend fun names(ids: Collection<MediaId>): Map<MediaId, List<String>>
}

/**
 * Le prime parole del testo letto (D22, E-AC3): **solo** se la lettura del testo e' attiva per l'account e non esclusa su questo dispositivo;
 * al piu' [max] parole, gia' ripulite dalla regola di P7 (niente caratteri di controllo o di direzione, NFC). Un errore = mappa vuota.
 */
fun interface DescribeText {
  suspend fun firstWords(ids: Collection<MediaId>, max: Int): Map<MediaId, String>
}

/** Se c'e' uno strumento di accessibilita' attivo (TalkBack, Switch Access, Voice Access: `isAccessibilityTool`). Mai in Diagnostica (D39). */
fun interface A11yActivity {
  fun toolEnabled(): Boolean
}

/**
 * Le parole delle descrizioni (in italiano: `strings_describe_*.xml`, legate da :app in P7; i valori di oggi sono quelli di `GridStrings` di
 * feature-library). Date e ore le scrive `GalleryDates` di core-library, i numeri `GalleryNumbers`. I campi con `%1$s` sono formati.
 */
data class DescribeStrings(
  val kindPhoto: String,
  val kindVideo: String,
  val kindMotion: String,
  val kindRaw: String,
  val kindAnimated: String,
  val favoriteFeminine: String,
  val favoriteMasculine: String,
  val editedFeminine: String,
  val editedMasculine: String,
  val unreadable: String,
  val estimated: String,
  /** "durata %1$s". */
  val duration: String,
  /** "%1$s scatti". */
  val group: String,
  /** "%1$s copie simili". */
  val similarCopies: String,
  val phoneOnly: String,
  val nasOnly: String,
  val both: String,
  val safe: String,
  val toVerify: String,
  val backupProblem: String,
  /** "con %1$s": i nomi, gia' uniti ("Anna e Marco", "Anna, Marco, Luca e altri 2"). */
  val withPeople: String,
  /** "e": fra gli ultimi due nomi. */
  val and: String,
  /** "e altri %1$s". */
  val others: String,
  /** "con la scritta «%1$s»". */
  val withText: String,
)
