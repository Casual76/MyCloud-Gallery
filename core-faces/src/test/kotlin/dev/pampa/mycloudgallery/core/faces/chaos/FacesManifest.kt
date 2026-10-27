package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.io.File
import java.security.MessageDigest
import java.util.Locale

// IL MANIFESTO DEI VOLTI SUL TELEFONO e il suo verificatore (docs/prova-500.md Parte F, §F9). `s25.ps1 -Mode faces -Step seed` scrive il manifesto
// (`# mcg-faces-manifest 1`): il giro, l'id del dispositivo (se lo si sa), dove il backup ha messo i media seminati (la cartella da guardare), e le
// impronte e i byte di ogni media (ritratti e composizioni di pubblico dominio: nessun volto di persone vere). Il verificatore legge le due share di prova in
// SOLA LETTURA ([FacesReadOnlyArea]) e controlla cio' che il NAS puo' dire da solo:
//  - nessun media toccato (impronte prima e dopo: ogni file della cartella del backup ha i byte di un media seminato, ogni media c'e', una volta sola);
//  - nessun dato fuori posto: niente dei volti nell'area comune; sotto `.mycloudgallery/faces/` della share privata solo generazioni, dispositivi e file con
//    la forma del formato (teste, segmenti, prenotazioni), solo la cartella di questo dispositivo se lo si conosce (`deviceid`) e non si ammettono altri;
//  - a cancellazione finita (fase `erased`): nessun file sotto `faces/` (le cartelle vuote restano, L191), in nessuna delle due share.
// Il resto (il database cifrato, la chiave, il diario, la cronologia) sta nel telefono: lo guarda `s25.ps1` con `run-as`.

class FacesManifest(
  val runId: String,
  val device: String?,
  val deviceId: String?,
  val privateShare: String,
  val privateDir: String,
  /** Le impronte dei media seminati sul telefono, con byte ed etichetta. */
  val media: Map<String, Pair<Long, String>>,
  /** Altri dispositivi dell'account che possono avere la loro cartella (l'emulatore, nel momento del secondo dispositivo). */
  val otherDevices: Set<String>,
) {
  companion object {
    private val SIMPLE_DIR = Regex("^[A-Za-z0-9._ -]+(/[A-Za-z0-9._ -]+)*$")
    private val SHA = Regex("^[0-9a-f]{64}$")

    fun read(file: File): FacesManifest = parse(file.readLines(Charsets.UTF_8))

    fun parse(lines: List<String>): FacesManifest {
      var run: String? = null
      var device: String? = null
      var deviceId: String? = null
      var private: Pair<String, String>? = null
      val media = LinkedHashMap<String, Pair<Long, String>>()
      val others = LinkedHashSet<String>()
      var header = false
      for ((number, raw) in lines.withIndex()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
          if (line == "# mcg-faces-manifest 1") header = true
          continue
        }
        fun bad(): Nothing = throw IllegalArgumentException("manifesto: riga ${number + 1} non valida")
        val key = line.substringBefore('=')
        val parts = line.substringAfter('=', "").split('|')
        when (key) {
          "run" -> run = parts.singleOrNull()?.takeIf { FacesAreaGuard.RUN_ID.matches(it) } ?: bad()
          "device" -> device = parts.singleOrNull()?.ifEmpty { null }
          "deviceid" -> {
            val value = parts.singleOrNull() ?: bad()
            deviceId = if (value.isEmpty()) null else if (TrustIds.isValid(value)) value else bad()
          }
          "private" -> private = (parts.getOrNull(0) ?: bad()) to (parts.getOrNull(1) ?: bad())
          "phone" -> {
            if (parts.size < 3 || !SHA.matches(parts[0])) bad()
            media[parts[0]] = (parts[1].toLongOrNull() ?: bad()) to parts.drop(2).joinToString("|")
          }
          "other" -> others += parts.singleOrNull()?.takeIf { TrustIds.isValid(it) } ?: bad()
          else -> bad()
        }
      }
      require(header) { "manifesto: manca l'intestazione '# mcg-faces-manifest 1'" }
      val (privateShare, privateDir) = private ?: throw IllegalArgumentException("manifesto senza 'private'")
      // Le cartelle da leggere le scrive lo script, ma il manifesto e' un file: la guardia non si fida (share di prova, cartelle semplici, niente `..`).
      require(privateShare.lowercase(Locale.ROOT) == "test") { "manifesto: share non di prova" }
      require(SIMPLE_DIR.matches(privateDir) && privateDir.split('/').none { it == ".." || it == "." }) { "manifesto: cartella non valida" }
      return FacesManifest(run ?: throw IllegalArgumentException("manifesto senza 'run'"), device, deviceId, privateShare, privateDir, media, others)
    }
  }
}

internal class FacesManifestVerification(val violations: List<Violation>, val mediaFiles: Int, val facesFiles: Int, val temps: Int, val filesHashed: Int, val bytesHashed: Long, val phase: String) {
  val ok: Boolean get() = violations.isEmpty()

  fun text(): String = buildString {
    appendLine("=== Verifica dei dati dei volti sul NAS di prova (fase: $phase) ===")
    appendLine("media letti: $mediaFiles; file dei volti: $facesFiles; temporanei: $temps; file letti con SHA-256: $filesHashed (${bytesHashed / 1024} KiB)")
    if (violations.isEmpty()) {
      appendLine("TUTTI GLI INVARIANTI RISPETTATI")
    } else {
      appendLine("${violations.size} VIOLAZIONI")
      violations.take(50).forEach { appendLine("  $it") }
    }
  }
}

internal object FacesManifestVerifier {

  /** `computed`: il calcolo e' finito o in corso; `erased`: "Disattiva e cancella" e' finita (nessun file dei volti, in nessuna share). */
  const val COMPUTED = "computed"
  const val ERASED = "erased"

  private val HEAD_NAME = Regex("^[0-9]{1,20}$")
  private val SEGMENT_NAME = Regex("^[0-9a-f]{64}$")

  fun verify(area: FacesReadOnlyArea, manifest: FacesManifest, phase: String): FacesManifestVerification {
    require(phase == COMPUTED || phase == ERASED) { "fase sconosciuta" }
    val violations = ArrayList<Violation>()
    var hashed = 0
    var bytes = 0L
    val at = "verifica ($phase)"

    // 1. I media: nessuno toccato.
    val mediaShas = ArrayList<String>()
    fun walkMedia(dir: String) {
      for (entry in area.entries(manifest.privateShare, dir)) {
        val path = "$dir/${entry.name}"
        if (entry.isDirectory) {
          walkMedia(path)
        } else {
          val data = area.read(manifest.privateShare, path) ?: continue
          hashed++
          bytes += data.size
          val sha = sha256(data)
          mediaShas += sha
          if (sha !in manifest.media) violations += Violation(Invariant.OUTSIDE, at, "un file della cartella del backup ha byte che non sono di nessun media seminato ($path)")
        }
      }
    }
    walkMedia(manifest.privateDir)
    for ((sha, info) in manifest.media) {
      val copies = mediaShas.count { it == sha }
      if (copies == 0) violations += Violation(Invariant.OUTSIDE, at, "un media seminato non c'e' piu' sul NAS (${info.second})")
      if (copies > 1) violations += Violation(Invariant.OUTSIDE, at, "un media seminato c'e' $copies volte (${info.second})")
    }

    // 2. L'area comune: niente dei volti, mai.
    var facesFiles = 0
    val common = collect(area, "test-comune", FACES)
    if (common.isNotEmpty()) violations += Violation(Invariant.PLACE, at, "${common.size} file dei volti nell'area comune")

    // 3. L'area privata: solo la forma del formato.
    val generations = area.entries(manifest.privateShare, FACES)
    for (generationEntry in generations) {
      val generation = FaceGeneration.parse(generationEntry.name)
      if (!generationEntry.isDirectory || generation == null) {
        violations += Violation(Invariant.PLACE, at, "un nome estraneo sotto faces/ (${generationEntry.name})")
        continue
      }
      val base = "$FACES/${generationEntry.name}"
      for (child in area.entries(manifest.privateShare, base)) {
        val childPath = "$base/${child.name}"
        if (child.name == NasLayout.FACES_LEASES && child.isDirectory) {
          facesFiles += count(area, manifest.privateShare, childPath)
          continue
        }
        if (!child.isDirectory || !TrustIds.isValid(child.name)) {
          violations += Violation(Invariant.PLACE, at, "un nome estraneo sotto la generazione ($childPath)")
          continue
        }
        val allowed = manifest.deviceId == null || child.name == manifest.deviceId || child.name in manifest.otherDevices
        if (!allowed) violations += Violation(Invariant.PLACE, at, "la cartella di un dispositivo che questa prova non conosce ($childPath)")
        for (kind in area.entries(manifest.privateShare, childPath)) {
          val kindPath = "$childPath/${kind.name}"
          val valid = kind.isDirectory && (kind.name == "head" || kind.name == "seg")
          if (!valid) {
            violations += Violation(Invariant.PLACE, at, "un file o una cartella estranei nella cartella di un dispositivo ($kindPath)")
            continue
          }
          for (file in area.entries(manifest.privateShare, kindPath)) {
            val name = file.name
            val ok = !file.isDirectory && (if (kind.name == "head") HEAD_NAME.matches(name) else SEGMENT_NAME.matches(name))
            if (!ok) violations += Violation(Invariant.PLACE, at, "un nome estraneo fra le ${kind.name} ($kindPath/$name)") else facesFiles++
          }
        }
      }
    }

    // 4. A cancellazione finita: nessun file dei volti, in nessuna share (le cartelle vuote restano).
    if (phase == ERASED && facesFiles > 0) violations += Violation(Invariant.ERASED, at, "$facesFiles file dei volti rimasti dopo 'Disattiva e cancella'")
    val temps = runCatching { collect(area, manifest.privateShare, TMP).size }.getOrDefault(0)
    return FacesManifestVerification(violations, mediaShas.size, facesFiles, temps, hashed, bytes, phase)
  }

  private fun count(area: FacesReadOnlyArea, share: String, dir: String): Int {
    var n = 0
    for (entry in area.entries(share, dir)) n += if (entry.isDirectory) count(area, share, "$dir/${entry.name}") else 1
    return n
  }

  private fun collect(area: FacesReadOnlyArea, share: String, dir: String): List<String> {
    val out = ArrayList<String>()
    for (entry in area.entries(share, dir)) {
      val path = "$dir/${entry.name}"
      if (entry.isDirectory) out += collect(area, share, path) else out += path
    }
    return out
  }

  private const val APP = ".mycloudgallery"
  private const val FACES = "$APP/faces"
  private const val TMP = "$APP/tmp"

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  /** Le cartelle leggibili per il manifesto: la sua, i dati dei volti e i temporanei dell'app delle due share, per [FacesReadOnlyTransport]. */
  fun readable(manifest: FacesManifest): Map<String, List<String>> = mapOf(
    manifest.privateShare.lowercase(Locale.ROOT) to listOf(manifest.privateDir.replace('/', '\\').lowercase(Locale.ROOT), ".mycloudgallery\\faces", ".mycloudgallery\\tmp"),
    "test-comune" to listOf(".mycloudgallery\\faces"),
  )
}
