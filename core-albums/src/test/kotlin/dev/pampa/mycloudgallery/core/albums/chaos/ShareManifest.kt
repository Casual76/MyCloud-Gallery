package dev.pampa.mycloudgallery.core.albums.chaos

import java.io.File
import java.security.MessageDigest
import java.util.Locale

// IL MANIFESTO DELLA CONDIVISIONE SUL TELEFONO e il suo verificatore (docs/prova-500.md Parte E, §E5). `s25.ps1 -Mode share -Step seed` scrive il manifesto
// (`# mcg-share-manifest 1`): il giro, l'id del dispositivo (se lo si sa), dove stanno le copie private del backup e le cartelle degli album, le impronte e
// i byte di ogni media seminato sul telefono. Il verificatore legge l'area in SOLA LETTURA ([ReadOnlyArea]) e controlla cio' che il NAS puo' dire da solo:
// il diario della condivisione e quello del motore stanno nel telefono; le voci degli album sono firmate (le guarda il banco sulla JVM).

class ShareManifest(
  val runId: String,
  val device: String?,
  val privateShare: String,
  val privateDir: String,
  val commonShare: String,
  val commonDir: String,
  /** Le impronte dei media seminati sul telefono, con byte ed etichetta. */
  val media: Map<String, Pair<Long, String>>,
  /** I fixture che non sono media (l'app li ignora): sul NAS devono mancare. */
  val notMedia: Set<String>,
  /** I media che il telefono non ha piu' (guardiano, "Libera spazio"): sul NAS non possono mancare. */
  val freed: Set<String>,
) {
  companion object {
    private val SIMPLE_DIR = Regex("^[A-Za-z0-9._ -]+(/[A-Za-z0-9._ -]+)*$")

    fun read(file: File): ShareManifest = parse(file.readLines(Charsets.UTF_8))

    fun parse(lines: List<String>): ShareManifest {
      var run: String? = null
      var device: String? = null
      var private: Pair<String, String>? = null
      var common: Pair<String, String>? = null
      val media = LinkedHashMap<String, Pair<Long, String>>()
      val notMedia = LinkedHashSet<String>()
      val freed = LinkedHashSet<String>()
      var header = false
      for ((number, raw) in lines.withIndex()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
          if (line == "# mcg-share-manifest 1") header = true
          continue
        }
        fun bad(): Nothing = throw IllegalArgumentException("manifesto: riga ${number + 1} non valida")
        val key = line.substringBefore('=')
        val parts = line.substringAfter('=', "").split('|')
        when (key) {
          "run" -> run = parts.singleOrNull()?.takeIf { ShareAreaGuard.RUN_ID.matches(it) } ?: bad()
          "device" -> device = parts.singleOrNull()?.ifEmpty { null }
          "private" -> private = (parts.getOrNull(0) ?: bad()) to (parts.getOrNull(1) ?: bad())
          "common" -> common = (parts.getOrNull(0) ?: bad()) to (parts.getOrNull(1) ?: bad())
          "phone" -> {
            if (parts.size < 3 || !SHA.matches(parts[0])) bad()
            media[parts[0]] = (parts[1].toLongOrNull() ?: bad()) to parts.drop(2).joinToString("|")
          }
          "notmedia" -> notMedia += parts.firstOrNull()?.takeIf { SHA.matches(it) } ?: bad()
          "freed" -> freed += parts.firstOrNull()?.takeIf { SHA.matches(it) } ?: bad()
          else -> bad()
        }
      }
      require(header) { "manifesto: manca l'intestazione '# mcg-share-manifest 1'" }
      val (privateShare, privateDir) = private ?: throw IllegalArgumentException("manifesto senza 'private'")
      val (commonShare, commonDir) = common ?: throw IllegalArgumentException("manifesto senza 'common'")
      // Le cartelle da leggere le scrive lo script, ma il manifesto e' un file: la guardia non si fida (share di prova, cartelle semplici, niente `..`).
      require(privateShare.lowercase(Locale.ROOT) == "test" && commonShare.lowercase(Locale.ROOT) == "test-comune") { "manifesto: share non di prova" }
      for (dir in listOf(privateDir, commonDir)) {
        require(SIMPLE_DIR.matches(dir) && dir.split('/').none { it == ".." || it == "." }) { "manifesto: cartella non valida" }
      }
      return ShareManifest(run ?: throw IllegalArgumentException("manifesto senza 'run'"), device, privateShare, privateDir, commonShare, commonDir, media, notMedia, freed)
    }

    private val SHA = Regex("^[0-9a-f]{64}$")
  }
}

class ManifestVerification(val violations: List<Violation>, val visible: Int, val trash: Int, val temps: Int, val filesHashed: Int, val bytesHashed: Long, val settled: Boolean) {
  val ok: Boolean get() = violations.isEmpty()

  fun text(): String = buildString {
    appendLine("=== Verifica dell'area della condivisione sul telefono (${if (settled) "a operazioni finite" else "a meta': solo gli invarianti che valgono sempre"}) ===")
    appendLine("file visibili: $visible; nel cestino: $trash; temporanei: $temps; file letti con SHA-256: $filesHashed (${bytesHashed / 1024} KiB)")
    if (violations.isEmpty()) {
      appendLine("TUTTI GLI INVARIANTI RISPETTATI")
    } else {
      appendLine("${violations.size} VIOLAZIONI")
      violations.take(50).forEach { appendLine("  $it") }
    }
  }
}

object ShareManifestVerifier {

  private class Found(val share: String, val path: String, val sha: String)

  /**
   * Gli invarianti del §5.5 che il NAS puo' dire da solo, sull'area del telefono. [settled]: la prova e' ferma (ogni lavoro e' finito).
   *  1. nessun file a meta': ogni file visibile ha l'impronta di un media seminato; a prova ferma nessun temporaneo, altrimenti solo temporanei propri;
   *  2. nessun doppione: un media non sta due volte nell'area comune, e a prova ferma non sta due volte in tutto (la sorgente e' nel cestino);
   *  3. nessuna perdita: un media che il telefono non ha piu' sta sul NAS, fuori dal cestino;
   *  un fixture che non e' un media non e' mai sul NAS (E-M12).
   */
  fun verify(area: ReadOnlyArea, manifest: ShareManifest, settled: Boolean, phase: String = "verifica"): ManifestVerification {
    val violations = ArrayList<Violation>()
    var hashed = 0
    var bytes = 0L

    fun walk(share: String, dir: String, into: MutableList<Found>, keep: (String) -> Boolean = { true }) {
      for (entry in area.entries(share, dir)) {
        val path = "$dir/${entry.name}"
        if (entry.isDirectory) {
          walk(share, path, into, keep)
        } else if (keep(path)) {
          val data = area.read(share, path) ?: continue
          hashed++
          bytes += data.size
          into += Found(share, path, sha256(data))
        }
      }
    }

    val visible = ArrayList<Found>()
    walk(manifest.privateShare, manifest.privateDir, visible)
    walk(manifest.commonShare, manifest.commonDir, visible)
    val trash = ArrayList<Found>()
    val temps = ArrayList<Found>()
    for (share in listOf(manifest.privateShare, manifest.commonShare)) {
      walk(share, "$APP/trash", trash) { "/files/" in it.lowercase(Locale.ROOT) }
      walk(share, "$APP/tmp", temps)
    }

    for (file in visible) {
      if (file.sha in manifest.notMedia) violations += Violation(ShareInvariant.NO_HALF_FILE, phase, "un file che non e' un media e' sul NAS (${file.share})")
      else if (file.sha !in manifest.media) violations += Violation(ShareInvariant.NO_HALF_FILE, phase, "file visibile con byte che non sono di nessun media seminato (${file.share}/${file.path})")
    }
    for (temp in temps) {
      val own = "-op-" in temp.path || "-bk-" in temp.path
      if (settled) violations += Violation(ShareInvariant.NO_HALF_FILE, phase, "temporaneo rimasto a prova ferma (${temp.share}/${temp.path})")
      else if (!own) violations += Violation(ShareInvariant.NO_HALF_FILE, phase, "temporaneo che non e' di questa app (${temp.share}/${temp.path})")
    }
    val byMedia = visible.filter { it.sha in manifest.media }.groupBy { it.sha }
    for ((sha, copies) in byMedia) {
      val inCommon = copies.count { it.share.equals(manifest.commonShare, ignoreCase = true) }
      val label = manifest.media[sha]!!.second
      if (inCommon > 1) violations += Violation(ShareInvariant.NO_DUPLICATE, phase, "$inCommon copie nell'area comune ($label)")
      if (settled && copies.size > 1) violations += Violation(ShareInvariant.NO_DUPLICATE, phase, "${copies.size} copie visibili a prova ferma ($label)")
      if (!settled && copies.size > 2) violations += Violation(ShareInvariant.NO_DUPLICATE, phase, "${copies.size} copie visibili ($label)")
    }
    val trashed = trash.map { it.sha }.toSet()
    for (sha in manifest.freed) {
      val label = manifest.media[sha]?.second ?: "?"
      when {
        sha !in byMedia && sha in trashed -> violations += Violation(ShareInvariant.NO_LOSS, phase, "solo nel cestino, senza una copia completa altrove ($label)")
        sha !in byMedia -> violations += Violation(ShareInvariant.NO_LOSS, phase, "perso: ne' sul telefono ne' sul NAS ($label)")
      }
    }
    return ManifestVerification(violations, visible.size, trash.size, temps.size, hashed, bytes, settled)
  }

  private const val APP = ".mycloudgallery"

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  /** Le cartelle leggibili per il manifesto: le sue e `.mycloudgallery/{tmp,trash}` delle due share, per [ReadOnlyAreaTransport]. */
  fun readable(manifest: ShareManifest): Map<String, List<String>> = mapOf(
    manifest.privateShare.lowercase(Locale.ROOT) to listOf(manifest.privateDir.replace('/', '\\').lowercase(Locale.ROOT), ".mycloudgallery\\tmp", ".mycloudgallery\\trash"),
    manifest.commonShare.lowercase(Locale.ROOT) to listOf(manifest.commonDir.replace('/', '\\').lowercase(Locale.ROOT), ".mycloudgallery\\tmp", ".mycloudgallery\\trash"),
  )
}
