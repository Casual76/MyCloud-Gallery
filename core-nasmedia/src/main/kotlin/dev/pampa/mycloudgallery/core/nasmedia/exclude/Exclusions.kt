package dev.pampa.mycloudgallery.core.nasmedia.exclude

import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.text.Normalizer
import java.util.Locale

// Cosa la scansione guarda e cosa no (docs/fase4-progetto.md §2.3, D11; E-N8, E-N14, E-N15, E-N16, E-M12). Tutto puro: si
// prova senza NAS.

/** Il genere di un file secondo l'estensione: i media da rilevare, e la musica che serve solo a riconoscere le copertine. */
enum class FileClass { IMAGE, VIDEO, AUDIO }

/**
 * Le estensioni che la scansione prende in considerazione. Sono **solo** formati che l'estrattore sa riconoscere dai
 * byte: un `.jpg` che dentro e' testo e' un falso media (E-M12), non un formato che non sappiamo leggere. La musica
 * non entra mai in Libreria (E-N14, E-N15): si riconosce solo per sapere che una cartella e' un album.
 */
object MediaTypes {

  private val IMAGES = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg",
    "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
    "tif" to "image/tiff", "tiff" to "image/tiff", "heic" to "image/heic", "heif" to "image/heif",
    "avif" to "image/avif", "dng" to "image/x-adobe-dng",
  )

  private val VIDEOS = mapOf(
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp",
    "3g2" to "video/3gpp2", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
  )

  private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "ape", "wv", "mka", "mpc")

  /** L'estensione in minuscolo, senza punto; vuota se il nome non ne ha (un nome che comincia per punto non ne ha). */
  fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase(Locale.ROOT)
  }

  fun classOf(name: String): FileClass? {
    val ext = extensionOf(name)
    return when {
      ext in IMAGES -> FileClass.IMAGE
      ext in VIDEOS -> FileClass.VIDEO
      ext in AUDIO -> FileClass.AUDIO
      else -> null
    }
  }

  /** Il tipo MIME dichiarato dall'estensione (indizio per l'estrattore, mai verita'). */
  fun mimeOf(name: String): String? {
    val ext = extensionOf(name)
    return IMAGES[ext] ?: VIDEOS[ext]
  }

  /** Il tipo di media dall'estensione, per quando i byte non dicono di piu' (formato rovinato o non letto). */
  fun kindOf(name: String): MediaKind? = when (classOf(name)) {
    FileClass.IMAGE -> MediaKind.PHOTO
    FileClass.VIDEO -> MediaKind.VIDEO
    else -> null
  }

  /** Vero per un file che la scansione rileva (immagine o video), falso per tutto il resto. */
  fun isMedia(name: String): Boolean = classOf(name).let { it == FileClass.IMAGE || it == FileClass.VIDEO }
}

/** Nomi di sistema (E-N8, E-N16): cestini, indici e cartelle di servizio dei NAS e dei sistemi operativi. */
object SystemNames {

  private val FOLDERS = setOf(
    "@eadir", "#recycle", "\$recycle.bin", "recycler", "system volume information", "lost+found", "@recycle",
  )

  private val FILES = setOf("thumbs.db", "desktop.ini", "ehthumbs.db", "folder.htt")

  /**
   * Cartelle mai scansionate e mai mostrate: ogni nome che comincia con un punto (`.recycle`, `.Recycle Bin`, `.wdmc`,
   * `.AppleDouble`, `.mycloudgallery`, `.Trash-1000`...) e le cartelle di servizio note (`@eaDir`, `$RECYCLE.BIN`...).
   */
  fun isSystemFolder(name: String): Boolean = name.startsWith('.') || name.lowercase(Locale.ROOT) in FOLDERS

  /** File nascosti per convenzione: i punti (`._IMG.jpg` di macOS, `.DS_Store`) e i file di servizio di Windows. */
  fun isSystemFile(name: String): Boolean = name.startsWith('.') || name.lowercase(Locale.ROOT) in FILES
}

/**
 * Nomi temporanei di chi sta ancora copiando o scaricando (E-N4): `*.part`, `*.tmp`, `*.crdownload`... Un file cosi'
 * non e' ancora un media; quello vero comparira' con il nome finale.
 */
object TemporaryNames {

  private val SUFFIXES = listOf(".part", ".partial", ".tmp", ".temp", ".crdownload", ".download", ".filepart", ".!qb", ".aria2", ".swp")

  /** Il nome e' temporaneo: suffisso noto, oppure il segnaposto di Office `~$nome`. */
  fun isTemporary(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return lower.startsWith("~$") || SUFFIXES.any { lower.endsWith(it) }
  }

  /**
   * Il nome e' quello di **un media in arrivo**: temporaneo e, tolto il suffisso, con l'estensione di un'immagine o di un
   * video (`video.mp4.part`, `foto.jpg.crdownload`). Gli altri temporanei (`~$relazione.docx`, `x.tmp`) non sono media.
   */
  fun isArrivingMedia(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    val suffix = SUFFIXES.firstOrNull { lower.endsWith(it) } ?: return false
    return MediaTypes.isMedia(name.substring(0, name.length - suffix.length))
  }
}

private fun fold(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

/**
 * Le esclusioni risolte di una share (D11): le cartelle escluse (dall'area comune la configurazione firmata, dall'area
 * privata le scelte dell'onboarding) e se le copertine degli album si mostrano. Il confronto non distingue maiuscole e
 * forma Unicode, come SMB (E-N7). Una cartella esclusa non si elenca mai oltre la sua radice (E-N15).
 */
class ExclusionSet internal constructor(paths: Collection<SharePath>, val showAlbumCovers: Boolean) {

  private val folded: List<List<String>> = paths.map { p -> p.components.map(::fold) }.distinct()

  val size: Int get() = folded.size

  /** Vero se [folder] e' una cartella esclusa o sta sotto una di esse. */
  fun excludes(folder: SharePath): Boolean {
    if (folded.isEmpty()) return false
    val parts = folder.components.map(::fold)
    return folded.any { ex -> parts.size >= ex.size && parts.subList(0, ex.size) == ex }
  }

  /**
   * Una copertina di album (D11, E-N14)? Solo se le copertine sono nascoste e la cartella ha file audio. [folderHasAudio]
   * lo calcola chi elenca (una volta per cartella): con migliaia di file un controllo sui fratelli per ogni file sarebbe
   * quadratico.
   */
  fun isCover(entry: NasEntry, folderHasAudio: Boolean): Boolean =
    !showAlbumCovers && folderHasAudio && Exclusions.looksLikeCover(entry)

  override fun toString(): String = "ExclusionSet($size cartelle, copertine=$showAlbumCovers)"
}

object Exclusions {

  /** Sotto questa dimensione un'immagine in una cartella di musica e' una copertina, qualunque nome abbia (D11). */
  const val COVER_MAX_BYTES: Long = 300L * 1024

  private val COVER_PREFIXES = listOf("cover", "folder", "front", "back", "albumart")

  /**
   * Le esclusioni di una share: quelle della configurazione firmata ([config]) e quelle locali ([local]) insieme;
   * [showCovers] e' l'opzione "Mostra le copertine degli album" (spenta di default).
   */
  fun build(config: Collection<SharePath>, local: Collection<SharePath>, showCovers: Boolean): ExclusionSet =
    ExclusionSet(config + local, showCovers)

  /**
   * Una copertina di album secondo l'elenco della cartella (D11): [entry] e' un'immagine, fra i [siblings] c'e' almeno un
   * file audio e il nome comincia come una copertina (`cover`, `folder`, `front`, `AlbumArt*`) oppure il file pesa meno
   * di 300 KB. Una foto grande in una cartella di musica non e' una copertina.
   */
  fun isAlbumCover(entry: NasEntry, siblings: Collection<NasEntry>): Boolean =
    looksLikeCover(entry) && siblings.any { !it.isDirectory && MediaTypes.classOf(it.name) == FileClass.AUDIO }

  internal fun looksLikeCover(entry: NasEntry): Boolean {
    if (entry.isDirectory || MediaTypes.classOf(entry.name) != FileClass.IMAGE) return false
    if (entry.size < COVER_MAX_BYTES) return true
    val base = entry.name.substringBeforeLast('.').lowercase(Locale.ROOT)
    return COVER_PREFIXES.any { base.startsWith(it) }
  }
}
