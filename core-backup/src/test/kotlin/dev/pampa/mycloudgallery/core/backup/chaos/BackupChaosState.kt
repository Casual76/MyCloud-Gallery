package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import java.util.Locale

// Lo STATO su cui lavora il verificatore degli invarianti del §5.4 (BackupChaosVerifier.kt): fatti semplici, senza oggetti vivi. Si cattura da
// un mondo che gira (BackupChaosWorld.capture), si scrive in un file di testo e si rilegge: il verificatore gira cosi' anche DA SOLO, su uno
// stato salvato, senza il banco, il motore o il NAS (`BackupStateFile`, `BackupVerifierTest`). Nessun segreto: percorsi finti del banco e
// impronte di contenuti finti.

/**
 * Gli invarianti del §5.4: da 1 a 11 (la 5A) e, dalla 5B, 12 (l'attuale al solito percorso), 13 (versioni conservate) e 14 ("Libera spazio" non
 * sbaglia), piu' uno del banco: il motore non lancia per un guasto del NAS o del telefono (il suo contratto, §1.2). Quest'ultimo era il 12 nel
 * banco della 5A (P6); dalla 5B porta il 15, perche' il 12 del §5.4 e' un altro.
 */
enum class Invariant(val number: Int, val label: String) {
  HALF_FILE(1, "nessun file a meta' visibile"),
  DUPLICATE(2, "nessun doppione creato"),
  REPLACED(3, "nessuna sostituzione"),
  LOSS(4, "nessuna perdita"),
  HASH_100(5, "hash al 100%"),
  TEMP(6, "temporanei"),
  CATALOG(7, "catalogo coerente"),
  DIARY(8, "diario coerente"),
  CONVERGENCE(9, "il lavoro arriva in fondo"),
  OUT_OF_PLACE(10, "nessun file fuori posto"),
  NAS_READS(11, "il NAS si legge"),
  USUAL_PATH(12, "l'attuale al solito percorso"),
  VERSIONS_KEPT(13, "versioni conservate"),
  FREE_SPACE(14, "\"Libera spazio\" non sbaglia"),
  CONTRACT(15, "il motore non lancia per un guasto"),

  /** D-13 (prove sul campo): un giro non finisce "completo" mentre il NAS tiene occupato un temporaneo di un elemento aperto. */
  BUSY(16, "un file occupato non chiude il giro"),
}

class Violation(val invariant: Invariant, val detail: String) {
  override fun toString(): String = "[${invariant.number}. ${invariant.label}] $detail"
}

/** Quando si controlla: a meta' (dopo una morte o a fine passo) o a lavoro finito (dopo le riprese). */
enum class Phase { MID, FINAL }

/** Un file sul NAS: share, percorso come sul server, dimensione, `fileId` e SHA-256 dei byte riletti. */
class NasFileFact(val share: String, val path: String, val size: Long, val fileId: Long?, val sha: Sha256) {
  val key: String get() = keyOf(share, path)

  val name: String get() = path.substringAfterLast('/')

  /** Sta nella cartella dell'app (`.mycloudgallery/`): temporanei e file propri, non media. */
  val appArea: Boolean get() = isAppArea(path)

  override fun toString(): String = "NasFileFact($size B)"

  companion object {
    fun keyOf(share: String, path: String): String = share.lowercase(Locale.ROOT) + ":" + path.lowercase(Locale.ROOT)

    fun isAppArea(path: String): Boolean = path.lowercase(Locale.ROOT).startsWith(APP_DIR)

    const val APP_DIR: String = ".mycloudgallery/"
  }
}

/** Un elemento del diario, come lo vede il verificatore (stati e tipi con il loro codice stabile). */
class ItemFact(
  val id: Long,
  val kind: String,
  val state: String,
  val media: String,
  val version: String,
  val size: Long,
  val share: String,
  val planned: String,
  val finalPath: String?,
  val temp: String?,
  val resumeAt: Long,
  val sha256: Sha256?,
  val verifyAttempts: Int,
) {
  override fun toString(): String = "ItemFact(#$id, $kind, $state)"
}

class TransitionFact(val item: Long, val from: String, val to: String)

/** Un media del catalogo: [onPhone] = la riga c'e' sul telefono con il timbro che il catalogo conosce (se e' cosi' dovrebbe essere al sicuro). */
class MediaFact(
  val media: String,
  val version: String,
  val size: Long,
  val fullHash: Sha256?,
  val safe: Boolean,
  val problem: String?,
  val onPhone: Boolean,
) {
  override fun toString(): String = "MediaFact($size B, safe=$safe)"
}

/** Una copia sul NAS che il catalogo conosce per il media. */
class CopyFact(val media: String, val version: String, val share: String, val path: String, val size: Long, val verified: Boolean)

/** Qualcosa che il banco ha visto e che e' una violazione per costruzione (una scansione che non riesce, un'eccezione del motore...). */
class Note(val invariant: Invariant, val text: String)

class BackupState(
  val deviceId: String,
  val privateShare: String,
  val commonShare: String?,
  /** I file che c'erano prima del giro, piu' quelli che il "PC" ha messo durante. */
  val before: List<NasFileFact>,
  /** I file adesso, riletti per intero. */
  val files: List<NasFileFact>,
  /** Le impronte di tutti i contenuti che il telefono ha avuto durante il giro (prima e dopo una riscrittura). */
  val phoneShas: Set<Sha256>,
  val items: List<ItemFact>,
  val transitions: List<TransitionFact>,
  val media: List<MediaFact>,
  val copies: List<CopyFact>,
  /** I temporanei che il diario cita adesso (elementi non finiti). */
  val cited: Set<String>,
  /** I temporanei citati dal diario che la pulizia ha tolto. */
  val cleanerRemovedCited: List<String>,
  val notes: List<Note>,
  /** Quanti file della scansione sono venuti da una cache invece che da una lettura (alla fine deve essere 0). */
  val cacheHits: Int,
  val filesHashed: Int,
)

/** Lo stato scritto in un file di testo, una riga per fatto, per verificarlo da solo (`BackupVerifierTest`, `area.ps1 -Mode backup -Stage state`). */
object BackupStateFile {
  private const val NONE = "-"

  fun write(state: BackupState, file: File) {
    file.parentFile?.mkdirs()
    file.writeText(text(state), Charsets.UTF_8)
  }

  fun text(state: BackupState): String = buildString {
    appendLine("# mcg-backup-state 1")
    appendLine("device=${state.deviceId}")
    appendLine("private=${state.privateShare}")
    appendLine("common=${state.commonShare.orEmpty()}")
    appendLine("cache=${state.cacheHits}|${state.filesHashed}")
    state.phoneShas.forEach { appendLine("phone=$it") }
    state.before.forEach { appendLine("before=${fileLine(it)}") }
    state.files.forEach { appendLine("file=${fileLine(it)}") }
    state.items.forEach {
      appendLine(
        "item=${it.id}|${it.kind}|${it.state}|${it.media}|${it.version}|${it.size}|${it.share}|${it.planned}|${it.finalPath ?: NONE}|" +
          "${it.temp ?: NONE}|${it.resumeAt}|${it.sha256 ?: NONE}|${it.verifyAttempts}",
      )
    }
    state.transitions.forEach { appendLine("transition=${it.item}|${it.from}|${it.to}") }
    state.media.forEach {
      appendLine("media=${it.media}|${it.version}|${it.size}|${it.fullHash ?: NONE}|${flag(it.safe)}|${it.problem ?: NONE}|${flag(it.onPhone)}")
    }
    state.copies.forEach { appendLine("copy=${it.media}|${it.version}|${it.share}|${it.path}|${it.size}|${flag(it.verified)}") }
    state.cited.forEach { appendLine("cited=$it") }
    state.cleanerRemovedCited.forEach { appendLine("removed=$it") }
    state.notes.forEach { appendLine("note=${it.invariant.number}|${it.text.replace('\n', ' ')}") }
  }

  fun read(file: File): BackupState = parse(file.readLines(Charsets.UTF_8))

  fun parse(lines: List<String>): BackupState {
    var device: String? = null
    var privateShare: String? = null
    var commonShare: String? = null
    var cache = 0 to 0
    val phone = LinkedHashSet<Sha256>()
    val before = ArrayList<NasFileFact>()
    val files = ArrayList<NasFileFact>()
    val items = ArrayList<ItemFact>()
    val transitions = ArrayList<TransitionFact>()
    val media = ArrayList<MediaFact>()
    val copies = ArrayList<CopyFact>()
    val cited = LinkedHashSet<String>()
    val removed = ArrayList<String>()
    val notes = ArrayList<Note>()
    for ((number, raw) in lines.withIndex()) {
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#")) continue
      fun bad(): Nothing = throw IllegalArgumentException("stato: riga ${number + 1} non valida")
      val key = line.substringBefore('=')
      val value = line.substringAfter('=', "")
      val p = value.split('|')
      fun opt(s: String): String? = s.takeIf { it != NONE }
      fun need(n: Int) {
        if (p.size < n) bad()
      }
      when (key) {
        "device" -> device = value
        "private" -> privateShare = value
        "common" -> commonShare = value.ifEmpty { null }
        "cache" -> {
          need(2)
          cache = (p[0].toIntOrNull() ?: bad()) to (p[1].toIntOrNull() ?: bad())
        }
        "phone" -> phone += Sha256.parse(value)
        "before" -> before += fileOf(p) ?: bad()
        "file" -> files += fileOf(p) ?: bad()
        "item" -> {
          // 13 campi; un quattordicesimo (il segno del difetto F1, corretto) in uno stato scritto prima si ignora.
          need(13)
          items += ItemFact(
            id = p[0].toLongOrNull() ?: bad(), kind = p[1], state = p[2], media = p[3], version = p[4], size = p[5].toLongOrNull() ?: bad(),
            share = p[6], planned = p[7], finalPath = opt(p[8]), temp = opt(p[9]), resumeAt = p[10].toLongOrNull() ?: bad(),
            sha256 = opt(p[11])?.let(Sha256::parse), verifyAttempts = p[12].toIntOrNull() ?: bad(),
          )
        }
        "transition" -> {
          need(3)
          transitions += TransitionFact(p[0].toLongOrNull() ?: bad(), p[1], p[2])
        }
        "media" -> {
          need(7)
          media += MediaFact(p[0], p[1], p[2].toLongOrNull() ?: bad(), opt(p[3])?.let(Sha256::parse), p[4] == "1", opt(p[5]), p[6] == "1")
        }
        "copy" -> {
          need(6)
          copies += CopyFact(p[0], p[1], p[2], p[3], p[4].toLongOrNull() ?: bad(), p[5] == "1")
        }
        "cited" -> cited += value
        "removed" -> removed += value
        "note" -> {
          need(2)
          val n = p[0].toIntOrNull() ?: bad()
          notes += Note(Invariant.entries.firstOrNull { it.number == n } ?: bad(), value.substringAfter('|'))
        }
        else -> bad()
      }
    }
    return BackupState(
      deviceId = device ?: throw IllegalArgumentException("stato senza 'device'"),
      privateShare = privateShare ?: throw IllegalArgumentException("stato senza 'private'"),
      commonShare = commonShare,
      before = before,
      files = files,
      phoneShas = phone,
      items = items,
      transitions = transitions,
      media = media,
      copies = copies,
      cited = cited,
      cleanerRemovedCited = removed,
      notes = notes,
      cacheHits = cache.first,
      filesHashed = cache.second,
    )
  }

  private fun fileLine(f: NasFileFact) = "${f.share}|${f.path}|${f.size}|${f.fileId ?: NONE}|${f.sha}"

  private fun fileOf(p: List<String>): NasFileFact? {
    if (p.size < 5) return null
    return NasFileFact(p[0], p[1], p[2].toLongOrNull() ?: return null, p[3].takeIf { it != NONE }?.toLongOrNull(), Sha256.parse(p[4]))
  }

  private fun flag(b: Boolean) = if (b) "1" else "0"
}
