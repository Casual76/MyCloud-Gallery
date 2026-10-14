package dev.pampa.mycloudgallery.core.model

import java.text.Normalizer
import java.util.Locale

/**
 * La forma "per confronto" di un nome: Unicode NFC e minuscolo.
 *
 * Perche': il NAS espone le share via SMB, che non distingue maiuscole e minuscole, e lo storage del
 * telefono (FUSE con casefold, SD in exFAT) nemmeno. `Foto/IMG.JPG` e `foto/img.jpg` sono lo stesso
 * file: se la chiave li distinguesse nascerebbe un doppione fantasma (E-N7). NFC perche' lo stesso
 * nome accentato puo' arrivare composto o scomposto (E-N6, macOS scrive NFD).
 *
 * Il nome originale si conserva a parte: serve cosi' com'e' per aprire il file.
 */
internal fun foldForKey(name: String): String =
  Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

private fun hasForbiddenChars(s: String): Boolean =
  s.any { it.code < 0x20 || it.code == 0x7f || it == '\\' || it == ':' }

/** Il tipo di una [Location], con il codice salvato nel database. */
enum class LocationType(override val code: String) : StableCode {
  PHONE("phone"),
  NAS("nas"),
}

/**
 * La chiave univoca di una posizione: due osservazioni con la stessa chiave sono **lo stesso posto**.
 * Normalizzata con [foldForKey], quindi insensibile a maiuscole e forma Unicode (E-N7).
 */
@JvmInline
value class LocationKey(val value: String) {
  override fun toString(): String = value
}

/**
 * Un percorso dentro una share del NAS, relativo alla share e con `/` come separatore.
 *
 * Si costruisce solo con [of]/[parseOrNull], che rifiutano cio' che il piano 1.4 chiama ostile:
 * percorsi assoluti (`/x`), UNC (`//server`), segmenti `.` e `..`, barre rovesciate, `:` (lettere di
 * unita' e flussi alternativi NTFS), caratteri di controllo, segmenti vuoti, lunghezze eccessive.
 * Il valore e' conservato **com'e'** (non normalizzato): serve per aprire il file sul NAS. La forma di
 * confronto e' nella [LocationKey].
 */
@JvmInline
value class NasPath private constructor(val value: String) {

  /** Il nome del file, cioe' l'ultimo segmento. */
  val fileName: String get() = value.substringAfterLast('/')

  /** La cartella che lo contiene, relativa alla share; vuota per un file nella radice della share. */
  val parent: String get() = value.substringBeforeLast('/', missingDelimiterValue = "")

  override fun toString(): String = value

  companion object {
    const val MAX_LENGTH: Int = 1024
    const val MAX_SEGMENT_LENGTH: Int = 255

    fun of(raw: String): NasPath =
      parseOrNull(raw) ?: throw IllegalArgumentException("Percorso NAS non valido o non relativo alla share.")

    fun parseOrNull(raw: String): NasPath? {
      if (raw.isEmpty() || raw.length > MAX_LENGTH || hasForbiddenChars(raw)) return null
      val segments = raw.split('/')
      val valid = segments.all { it.isNotEmpty() && it != "." && it != ".." && it.length <= MAX_SEGMENT_LENGTH }
      return if (valid) NasPath(raw) else null
    }
  }
}

private val VOLUME_NAME = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

/** Un nome di volume MediaStore accettabile (`external_primary`, l'ID di una microSD come `1a2b-3c4d`). */
fun isValidMediaStoreVolume(volume: String): Boolean = VOLUME_NAME.matches(volume)

/**
 * Dove si trova fisicamente un media (piano §1, "Posizione"): sul telefono o sul NAS. Un media puo'
 * averne molte; ognuna contiene i byte di **una** versione.
 *
 * Sigillata perche' le regole trattano i due mondi in modo diverso: sul telefono il posto ha una cache
 * MediaStore e una "continuita'" data dall'`_ID`, sul NAS il posto e' il percorso stesso.
 */
sealed interface Location {

  val type: LocationType

  /** Chiave univoca normalizzata: stesso posto se e solo se stessa chiave. */
  val key: LocationKey

  /** Chiave normalizzata della cartella che la contiene (elenchi per cartella, scansioni a blocchi). */
  val folderKey: String

  /**
   * Un file del telefono, come lo descrive MediaStore.
   *
   * L'identita' del posto e' `volume + relativePath + displayName`. [mediaStoreId] e
   * [mediaStoreVersion] sono **solo una cache** per ritrovare in fretta la riga
   * (`docs/android.md` 4.3): l'`_ID` vale solo finche' `MediaStore.getVersion()` resta quella con cui
   * e' stato letto, perche' dopo una ricostruzione del database gli ID ripartono e possono
   * coincidere con quelli vecchi di **altri** file.
   */
  data class Phone(
    /** Il volume MediaStore: `external_primary` o l'ID di una microSD (es. `1a2b-3c4d`). */
    val volume: String,
    /** `RELATIVE_PATH` di MediaStore: vuoto o terminante con `/` (es. `DCIM/Camera/`). */
    val relativePath: String,
    /** `DISPLAY_NAME` di MediaStore. */
    val displayName: String,
    val mediaStoreId: Long? = null,
    val mediaStoreVersion: String? = null,
  ) : Location {

    init {
      require(isValidMediaStoreVolume(volume)) { "Nome di volume MediaStore non valido." }
      require(isValidRelativePath(relativePath)) { "RELATIVE_PATH non valido." }
      require(displayName.isNotEmpty() && displayName.length <= 255 && '/' !in displayName && !hasForbiddenChars(displayName)) {
        "DISPLAY_NAME non valido."
      }
      require(mediaStoreId == null || mediaStoreId > 0) { "Un _ID MediaStore e' positivo." }
    }

    override val type: LocationType get() = LocationType.PHONE

    override val key: LocationKey get() = LocationKey("phone:$volume:" + foldForKey(relativePath + displayName))

    override val folderKey: String get() = "phone:$volume:" + foldForKey(relativePath)

    /** Se l'`_ID` in cache vale ancora per [current]: stesso ID letto con la stessa versione del MediaStore. */
    fun hasSameMediaStoreRowAs(current: Phone): Boolean =
      mediaStoreId != null && mediaStoreId == current.mediaStoreId &&
        mediaStoreVersion != null && mediaStoreVersion == current.mediaStoreVersion

    private companion object {
      fun isValidRelativePath(path: String): Boolean {
        if (path.isEmpty()) return true
        if (!path.endsWith('/') || path.startsWith('/') || hasForbiddenChars(path)) return false
        return path.dropLast(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." }
      }
    }
  }

  /**
   * Un file su una share del NAS, visto con un account. L'account fa parte della chiave: e' con le sue
   * credenziali che il dispositivo vede quel percorso.
   */
  data class Nas(
    val account: AccountId,
    /** Il nome della share come lo riporta il NAS (es. `alessio`, `NAS`). */
    val share: String,
    val path: NasPath,
    /**
     * Fase 4 (firma di P0, colonna di P2): il `FileId` SMB (`FileIdBothDirectoryInformation`), stabile alla rinomina
     * sullo stesso volume. E' solo un **indizio** in piu' per riconoscere uno spostamento fatto dal PC (E-N1): non fa
     * parte della [key] (il posto resta il percorso) e un NAS riavviato puo' cambiarlo (§7 del progetto). `null` =
     * non letto.
     */
    val fileId: Long? = null,
  ) : Location {

    init {
      require(share.isNotEmpty() && share.length <= 80 && '/' !in share && !hasForbiddenChars(share)) {
        "Nome di share non valido."
      }
    }

    override val type: LocationType get() = LocationType.NAS

    /** La share in forma di confronto: SMB non distingue maiuscole e minuscole nei nomi delle share. */
    val shareKey: String get() = foldForKey(share)

    override val key: LocationKey get() = LocationKey("nas:${account.value}:$shareKey:" + foldForKey(path.value))

    override val folderKey: String get() = "nas:${account.value}:$shareKey:" + foldForKey(path.parent)
  }
}

/**
 * Lo stato di una posizione conosciuta.
 *
 * Esiste [ABSENT] perche' una riga sparita non e' una cancellazione (`docs/android.md` 4.3, punto 4):
 * il cestino di Samsung Gallery toglie la riga dal MediaStore (scoperta 8), OneDrive "libera spazio",
 * i permessi possono essere ritirati. La posizione resta per una finestra di tolleranza: se il file
 * ricompare (E-P11, ripristino dal cestino) si ricollega allo **stesso** media senza ricaricarlo.
 */
enum class LocationState(override val code: String) : StableCode {
  /** Vista nell'ultima scansione. */
  PRESENT("present"),

  /** Non piu' vista dove era: forse cancellata, forse spostata o nel cestino di Galleria. */
  ABSENT("absent"),

  /**
   * Il contenitore non e' raggiungibile (microSD tolta, E-P6; share non piu' accessibile, E-R10).
   * Non e' un indizio di cancellazione: il file probabilmente esiste ancora.
   */
  UNAVAILABLE("unavailable"),
  ;

  /** Se il file esiste ancora, secondo cio' che sappiamo (anche se ora non si puo' aprire). */
  val exists: Boolean get() = this != ABSENT
}

/**
 * Cio' che il file system dice di un file senza leggerlo: dimensione, data di modifica e, sul
 * telefono, la `GENERATION_MODIFIED` di MediaStore. Serve a decidere se **rileggere**: un timbro uguale
 * permette di non ricalcolare le impronte (la verifica "senza novita'" della Fase 4.2).
 */
data class FileStamp(
  val size: Long,
  val modifiedAtMillis: Long,
  val generation: Long? = null,
) {

  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  /**
   * Se, da fuori, il file sembra lo stesso di [previous]. Nel dubbio (generazione diversa) no.
   *
   * [ignoreGeneration]: dopo un cambio di `MediaStore.getVersion()` (aggiornamento di One UI o del modulo
   * Mainline, E-P22) le generazioni ripartono e confrontarle non ha piu' senso; restano dimensione e data
   * di modifica, che vengono dal file system e non dal database del MediaStore.
   */
  fun looksUnchangedFrom(previous: FileStamp, ignoreGeneration: Boolean = false): Boolean =
    size == previous.size && modifiedAtMillis == previous.modifiedAtMillis &&
      (ignoreGeneration || generation == null || previous.generation == null || generation == previous.generation)
}

/**
 * Una posizione conosciuta di un media, con i byte di **una** sua versione ([versionId]).
 *
 * Collegare la posizione alla versione (e non al media) e' cio' che permette a una modifica di
 * spostare solo quella posizione sulla nuova versione, lasciando le altre (es. la copia sul NAS)
 * sull'originale: "dove si trova l'originale?" ha sempre una risposta.
 *
 * [verifiedAtMillis] e' l'istante (ora del NAS, piano §1 "Ora di riferimento") in cui i byte in questo
 * posto sono stati **riletti** e il loro SHA-256 e' risultato uguale a quello della versione. Si
 * azzera appena il timbro del file cambia: una verifica vale per i byte che ha visto.
 */
data class MediaLocation(
  val id: LocationId,
  val versionId: VersionId,
  val location: Location,
  val stamp: FileStamp,
  val state: LocationState,
  val absentSinceMillis: Long? = null,
  val verifiedAtMillis: Long? = null,
  val lastSeenAtMillis: Long,
) {

  init {
    require((state == LocationState.ABSENT) == (absentSinceMillis != null)) {
      "absentSinceMillis c'e' se e solo se la posizione e' assente."
    }
  }

  val isPresent: Boolean get() = state == LocationState.PRESENT

  val isPhone: Boolean get() = location is Location.Phone

  val isNas: Boolean get() = location is Location.Nas
}

/** Che cosa e' una share per chi la guarda: l'area privata dell'account o l'area comune di famiglia. */
enum class NasArea(override val code: String) : StableCode {
  /** Area privata (piano §1): accessibile solo all'account. L'unica che rende un media "al sicuro". */
  PRIVATE("private"),

  /** Area comune: tutta la famiglia legge e scrive, quindi chiunque puo' cancellare (piano §7, limiti). */
  COMMON("common"),
}

/**
 * Una share vista da un account, con la sua area. Viene dalla configurazione di famiglia (Fase 2): se
 * l'admin cambia quale share e' l'area comune, si aggiorna una riga e "al sicuro" si ricalcola.
 */
data class NasShare(
  val account: AccountId,
  val name: String,
  val area: NasArea,
) {
  val shareKey: String get() = foldForKey(name)
}

/** Come le regole scoprono l'area di una share. `null` = sconosciuta, trattata come **non** privata. */
fun interface NasAreaLookup {
  fun areaOf(account: AccountId, shareKey: String): NasArea?

  companion object {
    fun of(shares: Collection<NasShare>): NasAreaLookup {
      val byKey = shares.associateBy({ it.account to it.shareKey }, { it.area })
      return NasAreaLookup { account, shareKey -> byKey[account to shareKey] }
    }
  }
}
