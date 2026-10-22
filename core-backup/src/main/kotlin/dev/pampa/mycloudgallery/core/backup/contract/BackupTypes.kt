package dev.pampa.mycloudgallery.core.backup.contract

import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// Fase 5A (docs/fase5-progetto.md §1.2, §2.1, D6, D7, D11): i tipi di base del backup. Contratto di P0; li usano P2 (caricamento),
// P3 (verifiche), P4 (piano), P5 (UI), P6 (banco) e P7 (integrazione). Nessun nome di file, percorso, share o id nei `toString`.

/** Che cosa salva un giro (D11): prima le foto (`PHOTO_BACKUP`, leggera), poi i video (`VIDEO_BACKUP`, pesante). */
enum class BackupKind(override val code: String) : StableCode {
  PHOTOS("photos"),
  VIDEOS("videos"),
  ;

  companion object {
    /** Il giro che salva [kind]: i video sono solo [MediaKind.VIDEO]; le motion photo, i RAW e le animate vanno con le foto. */
    fun of(kind: MediaKind): BackupKind = if (kind.isVideo) VIDEOS else PHOTOS
  }
}

/** Chi ha chiesto il giro (D11, D34, proposta 10). */
enum class BackupTrigger(override val code: String) : StableCode {
  /** Il lavoro notturno o di giorno secondo il preset (WorkManager, tappe da 8 minuti). */
  SCHEDULED("scheduled"),

  /** Il primo backup avviato dall'utente ("Inizia ora", UIDT). */
  FIRST_BACKUP("first_backup"),

  /** "Fai ora" (UIDT; sui dati mobili con conferma e stima). */
  USER_NOW("user_now"),

  /** D34: "Proteggi subito le foto nuove" (solo foto della fotocamera, Wi-Fi di casa, batteria sopra la soglia). */
  QUICK_NEW_PHOTOS("quick_new_photos"),

  /** 5B (D24): un caricamento in arrivo da una condivisione. Dichiarato, nessun esecutore della 5A lo accetta. */
  INCOMING("incoming"),
}

/** Chi ha fermato il backup. */
enum class StopBy(override val code: String) : StableCode {
  /** "Ferma" dalla pagina o dalla notifica: si ferma prima del file successivo, ogni temporaneo resta con il suo punto di ripresa. */
  USER("user"),

  /** Android ha fermato il lavoro (termica, memoria, fine della tappa): la ripresa e' un lavoro normale. */
  SYSTEM("system"),

  /** Azzeramento dell'app (`BackupGraph.stop()` per primo, §1.4). */
  RESET("reset"),
}

/**
 * Una sorgente del backup (§2.1, Q8): la fotocamera e gli screenshot (gia' salvati dal passo 6 dell'onboarding) e le cartelle aggiuntive
 * del MediaStore (app di messaggistica, Quick Share, altre cartelle di `DCIM` e `Pictures`, la microSD). Una cartella si riconosce dal
 * volume e dal percorso relativo; il nome che finisce sul NAS (`<Nome>/AAAA/MM/`) lo decide il piano (P4).
 */
sealed interface BackupSource {
  /** L'identificativo stabile (impostazioni del dispositivo, conteggi per sorgente). */
  val id: String

  data object Camera : BackupSource {
    override val id: String = "camera"
  }

  data object Screenshots : BackupSource {
    override val id: String = "screenshots"
  }

  /** Una cartella aggiuntiva: [relativePath] come il MediaStore lo scrive (`Pictures/Telegram/`). Dato personale: mai nei log. */
  class Folder(val volume: String, val relativePath: String) : BackupSource {
    init {
      require(volume.isNotBlank() && relativePath.isNotBlank()) { "Cartella del telefono vuota." }
    }

    override val id: String get() = "folder:$volume:$relativePath"

    override fun equals(other: Any?): Boolean = other is Folder && other.volume == volume && other.relativePath == relativePath

    override fun hashCode(): Int = volume.hashCode() * 31 + relativePath.hashCode()

    override fun toString(): String = "BackupSource.Folder"
  }
}

/**
 * La sorgente "Ritagli" (Fase 6, P13, D47, risposta Q12): la cartella `Pictures/MyCloud Gallery/Ritagli/` dove "Salva come nuova foto" scrive i
 * ritagli del soggetto. E' una [BackupSource.Folder] del volume principale (la cartella e' sua: nome e destinazione vengono da li', sul NAS
 * `Backup/<dispositivo>/Ritagli/AAAA/MM/` come D47 vuole) **accesa di default**, come gli screenshot: [BackupSources.DEFAULT] la contiene e
 * `BackupSourceCodec.fromOnboarding` la aggiunge. Nessuna sorgente nuova nel tipo sigillato: chi lo legge (pagina del backup, impostazioni,
 * catalogo) la tratta come ogni altra cartella.
 *
 * In debug con un account di prova (recinzione F5-D13) i ritagli stanno in `Pictures/mcg-prova/Ritagli/` ([FENCED_RELATIVE_PATH]): la
 * sorgente giusta la sceglie chi ha la recinzione.
 */
object CutoutBackup {

  /** Il volume principale del MediaStore. */
  const val VOLUME: String = "external_primary"

  /** La cartella dei ritagli (D47), come la scrive il MediaStore (`RELATIVE_PATH`, con la barra finale). */
  const val RELATIVE_PATH: String = "Pictures/MyCloud Gallery/Ritagli/"

  /** La cartella dei ritagli con la recinzione delle prove (F5-D13, `mcg-prova`). */
  const val FENCED_RELATIVE_PATH: String = "Pictures/mcg-prova/Ritagli/"

  fun source(fenced: Boolean = false): BackupSource.Folder = BackupSource.Folder(VOLUME, if (fenced) FENCED_RELATIVE_PATH else RELATIVE_PATH)

  /** [source] e' la cartella dei ritagli (di serie o recintata). */
  fun isCutouts(source: BackupSource): Boolean =
    source is BackupSource.Folder && source.volume == VOLUME && (source.relativePath == RELATIVE_PATH || source.relativePath == FENCED_RELATIVE_PATH)
}

/** Le sorgenti scelte (impostazione del dispositivo, non personale: ogni telefono ha le sue cartelle). */
data class BackupSources(val sources: Set<BackupSource>) {
  val isEmpty: Boolean get() = sources.isEmpty()

  operator fun contains(source: BackupSource): Boolean = source in sources

  /** Le stesse sorgenti con la cartella dei ritagli ([CutoutBackup]) accesa: per chi aveva scelto prima dei ritagli (Fase 6). */
  fun withCutouts(fenced: Boolean = false): BackupSources = BackupSources(sources + CutoutBackup.source(fenced))

  companion object {
    val NONE: BackupSources = BackupSources(emptySet())

    /** Quelle accese di serie: fotocamera, screenshot e (Fase 6, D47) i ritagli del soggetto. */
    val DEFAULT: BackupSources = BackupSources(setOf(BackupSource.Camera, BackupSource.Screenshots, CutoutBackup.source()))
  }
}

/**
 * La cartella del dispositivo sul NAS (D7): `Backup/<nome>/…`, scelta **una volta** al primo backup e salvata nelle impostazioni del
 * backup; rinominare il dispositivo non la cambia (E-B7). Il nome e' gia' ripulito per `SharePath` (un solo componente valido).
 */
class DeviceFolder(val name: String) {
  init {
    require(SharePath.isValidComponent(name)) { "Nome della cartella del dispositivo non valido." }
  }

  override fun equals(other: Any?): Boolean = other is DeviceFolder && other.name == name

  override fun hashCode(): Int = name.hashCode()

  /** Il nome del dispositivo e' un dato dell'utente: mai nei log. */
  override fun toString(): String = "DeviceFolder"
}

/**
 * Una riga del MediaStore come la conosce il catalogo: volume, `_ID` e la versione del MediaStore con cui l'`_ID` vale (E-P12, E-P22).
 * **Non e' un'identita'**: se la versione cambia, la riga si ritrova per impronta dal catalogo (`BackupCatalog.phoneRefOf`).
 */
data class PhoneRef(val volume: String, val mediaStoreId: Long, val mediaStoreVersion: String) {
  init {
    require(mediaStoreId > 0) { "Un _ID MediaStore e' positivo." }
  }

  override fun toString(): String = "PhoneRef"
}

/**
 * Un media da mettere al sicuro (§2.1 "Candidati"): **proprio**, con una copia presente sul telefono in una sorgente scelta, per una
 * versione da proteggere senza copia privata verificata, mai una riga `IS_PENDING`. Ogni file di una raffica o di una coppia RAW+JPEG e'
 * un candidato a se'.
 *
 * @property stamp il timbro atteso del file del telefono (se cambia prima del caricamento, la versione non c'e' piu': E-B15).
 * @property quickHash `qh1` della versione (la deduplica cerca le copie private con la stessa dimensione e lo stesso `qh1`, D8).
 * @property localDay il giorno di scatto nell'ora del luogo (`aaaammgg`, catalogo), null = senza data (E-B17).
 * @property dateEstimated la data e' una stima (nome o data del file): la destinazione la usa solo se credibile (D6).
 * @property fileName il nome del file sul telefono: il nome sul NAS (D6). Dato dell'utente: mai nei log.
 * @property sortTime l'ordine del catalogo (dal piu' recente): il cursore delle pagine dei candidati.
 * @property original e' l'originale del media (l'originale si carica prima della sua versione, E-E5).
 */
class BackupCandidate(
  val mediaId: MediaId,
  val versionId: VersionId,
  val phone: PhoneRef,
  val stamp: FileStamp,
  val size: Long,
  val kind: MediaKind,
  val source: BackupSource,
  val localDay: Int?,
  val dateEstimated: Boolean,
  val fileName: String,
  val sortTime: Long,
  val quickHash: QuickHash?,
  val original: Boolean = true,
) {
  init {
    require(size >= 0) { "Dimensione negativa." }
    require(fileName.isNotBlank()) { "Nome del file vuoto." }
  }

  val backupKind: BackupKind get() = BackupKind.of(kind)

  override fun toString(): String = "BackupCandidate(${kind.code}, $size B)"
}

/** Il cursore delle pagine dei candidati: `sort_time` decrescente, poi id (nessun ordinamento in memoria, P1). */
data class CandidateCursor(val sortTime: Long, val mediaId: MediaId) {
  override fun toString(): String = "CandidateCursor"
}

/** Una pagina di candidati (P1: `MediaCatalog.backupCandidates(sources, kind, after, limit)`). */
data class CandidateQuery(val sources: BackupSources, val kind: BackupKind, val after: CandidateCursor?, val limit: Int) {
  init {
    require(limit in 1..MAX_LIMIT) { "Pagina di candidati fuori misura." }
  }

  companion object {
    const val MAX_LIMIT: Int = 1_000
  }
}
