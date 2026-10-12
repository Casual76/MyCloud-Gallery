package dev.pampa.mycloudgallery.core.family.survey

import dev.pampa.mycloudgallery.core.family.contract.ExcludedFolder
import dev.pampa.mycloudgallery.core.family.contract.ExclusionReason
import dev.pampa.mycloudgallery.core.family.contract.FolderProposal
import dev.pampa.mycloudgallery.core.family.contract.FolderSurvey
import dev.pampa.mycloudgallery.core.nas.contract.DirEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasBrowser
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Il sondaggio delle cartelle del passo 5 (E-N14, E-N15): propone di tenere fuori dalla galleria le cartelle di
 * musica e di film e le copertine degli album, che sul NAS di casa sono la parte piu' grossa delle immagini
 * (`docs/inventario-nas.md` §4-bis: nella share comune 5.285 JPG con mediana di 40 KB, in gran parte copertine,
 * e 380 video che non sono video di famiglia).
 *
 * **Solo lettura e a misura** (non sveglia i dischi senza motivo: si fa durante l'onboarding, con l'utente che
 * aspetta):
 * - **2 livelli**: si elencano la radice e le cartelle di primo livello; le voci di secondo livello si vedono
 *   (il loro nome basta per riconoscere "Musica" o "@eaDir") ma non si aprono;
 * - **al massimo [MAX_ENTRIES] voci** in tutto (la scansione completa di una share con 2.356 cartelle e'
 *   gia' fuori scala per un passo saltabile): oltre, `truncated = true` e si propone cio' che si e' visto;
 * - **annullabile**: ogni elenco controlla l'annullamento della coroutine; chi chiude la schermata ferma tutto.
 *
 * Una cartella si riconosce dal **nome** (parole intere: "Musica", "Film", non "Filmati" ne' "Album foto":
 * i video e le foto di famiglia non devono finire nella proposta) o dal **contenuto** di quelle che si aprono:
 * almeno [MIN_AUDIO_FILES] file audio = musica; almeno [MIN_MOVIE_FILES] file di film (MKV, AVI, ISO...: i
 * telefoni producono MP4 e MOV, quindi un MP4 da solo non basta) = film; una cartella con audio le cui immagini
 * sono tutte piccole (sotto [SMALL_IMAGE_BYTES]) = copertine. Quando una cartella e' proposta non si guarda
 * dentro. E' una **proposta**: l'utente toglie e aggiunge, e il passo si puo' saltare.
 *
 * Un errore del NAS non lancia: la radice illeggibile da' una proposta vuota, una sottocartella illeggibile si salta.
 */
class DefaultFolderSurvey(
  private val browserFor: (String) -> NasBrowser,
  private val maxEntries: Int = MAX_ENTRIES,
) : FolderSurvey {

  constructor(sessions: NasSessionManager) : this({ share -> sessions.browser(share) })

  override suspend fun propose(share: String): FolderProposal {
    val browser = browserFor(share)
    val found = ArrayList<ExcludedFolder>()
    var scanned = 0
    var truncated = false

    // Coda per livelli: la radice (livello 0) e le cartelle di primo livello che non sono gia' proposte.
    var level: List<SharePath?> = listOf(null)
    var depth = 0
    scan@ while (level.isNotEmpty() && depth < LISTED_DEPTH) {
      val next = ArrayList<SharePath?>()
      for (dir in level) {
        currentCoroutineContext().ensureActive()
        val remaining = maxEntries - scanned
        if (remaining <= 0) {
          truncated = true
          break@scan
        }
        val page = browser.list(dir, remaining).okOrNull() ?: continue
        val entries = page.entries.take(remaining)
        scanned += entries.size
        if (page.truncated || page.entries.size > remaining) truncated = true

        val files = entries.filter { !it.isDirectory }
        val contentReason = if (dir == null) null else Rules.byContent(files)
        if (dir != null && contentReason != null) {
          found += ExcludedFolder(dir.value, contentReason)
          continue // proposta intera: dentro non si guarda
        }
        val children = entries.filter { it.isDirectory && SharePath.isValidComponent(it.name) }
          .sortedBy { it.name.lowercase(Locale.ROOT) }
        for (child in children) {
          val path = dir?.child(child.name) ?: SharePath.of(child.name)
          val nameReason = Rules.byName(child.name)
          if (nameReason != null) found += ExcludedFolder(path.value, nameReason) else next += path
        }
      }
      level = next
      depth++
    }
    return FolderProposal(found.sortedBy { it.path.lowercase(Locale.ROOT) }, scanned, truncated)
  }

  companion object {
    /** Voci guardate in tutto: oltre, la proposta e' parziale (`truncated`). */
    const val MAX_ENTRIES: Int = 5000

    /** Livelli di cartelle che si aprono: la radice e le sue figlie. Le nipoti si vedono per nome, non si aprono. */
    const val LISTED_DEPTH: Int = 2

    /** Una cartella con almeno tanti file audio e' musica (una cartella con uno o due e' un ospite: copertine). */
    const val MIN_AUDIO_FILES: Int = 3

    /** Una cartella con almeno tanti file di film e' una cartella di film. */
    const val MIN_MOVIE_FILES: Int = 2

    /** Un'immagine sotto questa dimensione, accanto a file audio, e' una copertina (mediana vera: 40 KB). */
    const val SMALL_IMAGE_BYTES: Long = 512L * 1024
  }
}

/** Le regole di riconoscimento, separate dalla scansione per poterle provare da sole. */
internal object Rules {

  private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "ape", "alac", "aiff", "aif", "mka", "mpc", "wv", "dsf")

  /** Contenitori dei film. Niente MP4, MOV ne' MTS: sono i formati dei telefoni e delle videocamere di famiglia. */
  private val MOVIE = setOf("mkv", "avi", "wmv", "divx", "m2ts", "iso", "vob", "mpg", "mpeg", "flv", "rmvb")

  private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp")

  private val COVER_BASENAMES = setOf("cover", "folder", "front", "back", "cd", "disc", "booklet", "inlay", "label", "scan", "artwork")

  private val SYSTEM_NAMES = setOf(
    "#recycle", "@recycle", "\$recycle.bin", "recycle bin", "system volume information", "lost+found", "@eadir",
    ".spotlight-v100", ".fseventsd", ".trashes",
  )

  private val APP_NAMES = setOf(
    ".mycloudgallery", "plex media server", ".plex", ".twonkymedia", "twonkydata", "twonky", "@appstore", ".wdmc",
    ".thumbnails", ".cache",
  )

  private val MUSIC_WORDS = setOf("musica", "music", "mp3", "audio", "canzoni", "songs", "flac", "itunes", "discografia", "discografie", "soundtrack")

  private val MOVIE_WORDS = setOf("film", "films", "movie", "movies", "serie", "series", "cinema", "telefilm", "dvd", "bluray", "bdrip", "divx")

  private val COVER_WORDS = setOf("cover", "covers", "copertine", "copertina", "artwork", "albumart")

  private val TOKENS = Regex("[^\\p{L}\\p{N}]+")

  /** Il motivo per cui una cartella si propone guardando solo il nome, o null. */
  fun byName(name: String): ExclusionReason? {
    val lower = name.lowercase(Locale.ROOT)
    if (lower in SYSTEM_NAMES || lower.startsWith(".trash")) return ExclusionReason.SYSTEM_FOLDER
    if (lower in APP_NAMES) return ExclusionReason.APP_FOLDER
    val words = lower.split(TOKENS).filter { it.isNotEmpty() }
    return when {
      words.any { it in MUSIC_WORDS } -> ExclusionReason.MUSIC
      words.any { it in MOVIE_WORDS } -> ExclusionReason.MOVIES
      words.any { it in COVER_WORDS } -> ExclusionReason.SMALL_COVERS
      else -> null
    }
  }

  /** Il motivo per cui una cartella si propone guardando i suoi file (gia' elencati), o null. */
  fun byContent(files: List<DirEntry>): ExclusionReason? {
    var audio = 0
    var movies = 0
    val images = ArrayList<DirEntry>()
    for (file in files) {
      when (extensionOf(file.name)) {
        in AUDIO -> audio++
        in MOVIE -> movies++
        in IMAGE -> images += file
      }
    }
    return when {
      audio >= DefaultFolderSurvey.MIN_AUDIO_FILES -> ExclusionReason.MUSIC
      movies >= DefaultFolderSurvey.MIN_MOVIE_FILES -> ExclusionReason.MOVIES
      images.isEmpty() -> null
      // Immagini piccole accanto a un po' di audio, o immagini che si chiamano "cover" e "folder": copertine.
      images.all { it.size < DefaultFolderSurvey.SMALL_IMAGE_BYTES } && (audio > 0 || images.all { isCoverName(it.name) }) ->
        ExclusionReason.SMALL_COVERS
      else -> null
    }
  }

  private fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot < 0 || dot == name.lastIndex) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
  }

  private fun isCoverName(name: String): Boolean {
    val base = name.substringBeforeLast('.').lowercase(Locale.ROOT)
    return base in COVER_BASENAMES || base.startsWith("albumart") || base.startsWith("cover") || base.startsWith("folder")
  }
}
