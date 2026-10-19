package dev.pampa.mycloudgallery.core.metadata.contract

import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.StableCode
import java.io.File

// Fase 5B (docs/fase5-progetto.md §1.2 "core-metadata", D19, D26, L40, L41; riga P0-B del §4.4): SCRIVERE i metadati di un file e TOGLIERE la
// posizione. Solo contratti, null-object e fake di P0-B; la scrittura vera (`write/`, `ExifInterface`) e' di P8, la rimozione della
// posizione (`strip/`) di P11.
//
// **Mai sul file vero.** Entrambi lavorano da un file sorgente a un file di destinazione: il sorgente non si tocca mai, e se qualcosa va
// storto la destinazione non resta (un file rovinato dalla scrittura si scarta con l'originale intatto, D19). Il chiamante non si fida del
// risultato: lo rilegge con l'estrattore e lo decodifica prima che valga (D19), e per la posizione lo ricontrolla con due lettori (D26).
// Nessuna rete, nessun orologio: file in, file out.

/**
 * Che cosa fare del luogo di un file. Un punto GPS e' un dato sensibile (`GeoPoint` non lo mostra nel `toString`).
 */
sealed interface GeoChange {
  /** Il luogo del file non cambia. */
  data object Keep : GeoChange

  /** Scrive [point] (correzione di luogo, E-L8). */
  data class Set(val point: GeoPoint) : GeoChange

  /** Toglie la posizione dal file. */
  data object Clear : GeoChange
}

/**
 * Una correzione da scrivere **nel file** (D19): data e fuso, luogo, orientamento. I campi `null`/[GeoChange.Keep] non si toccano.
 *
 * @property takenAtUtcMillis l'istante di scatto, in UTC; con [offsetMinutes] (il fuso del luogo, minuti dall'UTC) diventa data e ora
 *   locali dell'EXIF (`DateTimeOriginal`, `OffsetTimeOriginal`). Dato dell'utente: mai nei log.
 * @property orientation il valore del tag EXIF `Orientation` (la rotazione e lo specchio, E-M15).
 */
data class MetadataChange(
  val takenAtUtcMillis: Long? = null,
  val offsetMinutes: Int? = null,
  val geo: GeoChange = GeoChange.Keep,
  val orientation: ExifOrientation? = null,
) {
  init {
    require(offsetMinutes == null || offsetMinutes in -MAX_OFFSET_MINUTES..MAX_OFFSET_MINUTES) { "Fuso fuori misura." }
    require(offsetMinutes == null || takenAtUtcMillis != null) { "Il fuso accompagna una data." }
  }

  /** Nessun campo cambia: non c'e' niente da scrivere. */
  val isEmpty: Boolean get() = takenAtUtcMillis == null && geo == GeoChange.Keep && orientation == null

  /** Quali campi tocca, nei termini del modello (`CorrectionField`): data, luogo, rotazione. */
  val fields: Set<dev.pampa.mycloudgallery.core.model.CorrectionField>
    get() = buildSet {
      if (takenAtUtcMillis != null) add(dev.pampa.mycloudgallery.core.model.CorrectionField.DATE)
      if (geo != GeoChange.Keep) add(dev.pampa.mycloudgallery.core.model.CorrectionField.LOCATION)
      if (orientation != null) add(dev.pampa.mycloudgallery.core.model.CorrectionField.ROTATION)
    }

  /** Una riga muta: data, luogo e fuso sono dell'utente. */
  override fun toString(): String = "MetadataChange(${fields.joinToString { it.code }})"

  companion object {
    /** 14 ore: il fuso piu' lontano dall'UTC (UTC+14, Kiribati). */
    const val MAX_OFFSET_MINUTES: Int = 14 * 60
  }
}

/** Perche' un file non si scrive o non si trasforma, senza che sia un guasto (formato fuori dai tre ammessi, nessun cambiamento). */
enum class WriteUnsupported(override val code: String) : StableCode {
  /** Il formato non si scrive nel file: HEIC, RAW, video (Q6, L40); la correzione resta nell'app e nell'indice. */
  FORMAT("format"),

  /** La correzione non cambia niente. */
  NOTHING_TO_DO("nothing_to_do"),

  /** Nessuno scrittore legato (null-object): finche' P8 non lo porta, niente si scrive. */
  NOT_AVAILABLE("not_available"),
}

/** Perche' una scrittura e' fallita (un guasto, non un limite): codici stabili, mai un messaggio con dati dell'utente. */
enum class WriteFailure(override val code: String) : StableCode {
  /** Il sorgente non si legge o non e' quello che dice di essere (tipo falso, rovinato, oltre i tetti dei pixel). */
  SOURCE_UNREADABLE("source_unreadable"),

  /** La destinazione esiste gia': non si sostituisce niente. */
  DESTINATION_EXISTS("destination_exists"),

  /** Disco del telefono pieno, destinazione non scrivibile. */
  IO("io"),
}

/** L'esito di [MetadataWriter.apply]. */
sealed interface WriteOutcome {
  /** La destinazione esiste ed e' lunga [bytes]: il chiamante la rilegge e la decodifica prima che valga (D19). */
  data class Written(val bytes: Long) : WriteOutcome

  data class Unsupported(val reason: WriteUnsupported) : WriteOutcome

  /** Fallita: la destinazione **non** esiste (se l'ha creata, l'ha tolta); il sorgente e' intatto. */
  data class Failed(val reason: WriteFailure) : WriteOutcome
}

/**
 * Scrive una correzione di data, luogo o orientamento **nel file** (D19): JPEG, PNG e WebP con `ExifInterface` (Q6: HEIC, RAW e video
 * restano fuori, L40). Da un sorgente a una destinazione nuova; il sorgente non si tocca. Sincrono e bloccante (lavora su file locali):
 * il chiamante lo gira su un dispatcher di I/O.
 */
interface MetadataWriter {

  /** Se questo tipo si scrive nel file ([mime] come lo dice il MediaStore o `MediaSniffer`). */
  fun canWrite(mime: String): Boolean

  /**
   * Copia [src] in [dst] con [change] applicato. [dst] non deve esistere ([WriteFailure.DESTINATION_EXISTS]). Su un formato non ammesso o
   * senza cambiamenti risponde [WriteOutcome.Unsupported] senza creare niente.
   */
  fun apply(src: File, dst: File, change: MetadataChange): WriteOutcome
}

/** Perche' la posizione non si toglie (il formato non e' trattabile, nessun togli-posizione legato). */
enum class StripUnsupported(override val code: String) : StableCode {
  /** Un formato che non si sa trattare: la condivisione senza posizione si ferma con il motivo (L41). */
  FORMAT("format"),

  /** Nessuno strumento legato (null-object): mai un successo finto. */
  NOT_AVAILABLE("not_available"),
}

/** L'esito di [LocationStripper.strip]. */
sealed interface StripOutcome {
  /**
   * La destinazione esiste e **non ha posizione** (ricontrollata con l'estrattore dell'app e con `MediaMetadataRetriever`, D26).
   * [mime] e' il tipo della destinazione: un HEIC diventa JPEG, e l'intento di condivisione usa questo tipo, non quello del sorgente.
   */
  data class Stripped(val mime: String) : StripOutcome

  data class Unsupported(val reason: StripUnsupported) : StripOutcome

  /** Dopo il tentativo resta una posizione in uno dei due lettori: la destinazione e' stata tolta, la condivisione si ferma (D26). */
  data object StillHasLocation : StripOutcome

  /** Il sorgente non si legge o la scrittura e' fallita: la destinazione non c'e'. */
  data class Failed(val reason: WriteFailure) : StripOutcome
}

/**
 * Toglie la posizione da una **copia** da condividere (D26, E-X5): JPEG, PNG e WebP con `ExifInterface`; MP4 e MOV rinominando in `free`
 * le scatole della posizione (stessa dimensione, nessuna ricodifica); HEIC convertito in JPEG. Da un sorgente a una destinazione nuova:
 * la copia in cache che la condivisione consegna, mai il media. **Dimostra, non presume**: ogni destinazione si ricontrolla con due
 * lettori prima di restituire [StripOutcome.Stripped]; se resta una posizione risponde [StripOutcome.StillHasLocation] e toglie la
 * destinazione.
 */
interface LocationStripper {

  /** [mime] e' il tipo del sorgente; la destinazione non deve esistere. */
  fun strip(src: File, dst: File, mime: String): StripOutcome
}
