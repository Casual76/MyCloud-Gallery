package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.UploadFlow
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.util.Locale

// Il VERIFICATORE degli invarianti della 5B (docs/fase5-progetto.md §5.4, 12-14, piu' quelli da 1 a 11 che restano validi con le versioni):
// come BackupVerifier per la 5A, lavora su uno stato fatto di FATTI SEMPLICI (file del NAS con SHA-256 e `fileId`, media con le loro versioni e
// copie, lavori del diario delle versioni, righe di "Libera spazio"), senza oggetti vivi: gira nel banco dopo ogni morte e a fine passo, ma
// anche DA SOLO su uno stato salvato in un file (`FiveBStateFile`, `area.ps1 -Mode backup -Stage state`). Non legge il NAS e non conosce i motori.
//
// L'invariante 14 ("Libera spazio" non sbaglia) e' l'unico che il banco giudica **al momento**, quando un lotto esce (BackupChaos5BPhase.checkBatch):
// dopo il dialogo il telefono cambia e il NAS pure, e il momento della consegna non si ricostruisce da uno stato finale. Qui arriva come nota
// dell'invariante 14, insieme alla contabilita' finale delle righe tolte dal telefono.

/** Una versione di un media come la vede il verificatore. */
class FiveBVersionFact(
  val id: String,
  val kind: String,
  val size: Long,
  /** L'impronta dei byte secondo il banco (null = il banco non la sa: la usa dal catalogo). */
  val sha: Sha256?,
  val catalogHash: Sha256?,
  /** I byte della versione sono comparsi come file visibile sul NAS durante la prova (e non sono stati tolti dal PC di proposito). */
  val seenOnNas: Boolean,
) {
  val truth: Sha256? get() = sha ?: catalogHash
}

/** Una copia sul NAS che il catalogo conosce. */
class FiveBCopyFact(val version: String, val share: String, val path: String, val size: Long, val verified: Boolean, val present: Boolean)

/** Un media del banco: le sue versioni (dalla piu' vecchia), le copie del catalogo, dove sta il solito percorso. */
class FiveBMediaFact(
  val label: String,
  val mine: Boolean,
  val category: String,
  val current: String,
  val versions: List<FiveBVersionFact>,
  val copies: List<FiveBCopyFact>,
  val usualShare: String,
  /** Il solito percorso del media (null = il media non ha ancora una copia sul NAS). */
  val usualPath: String?,
  /** Un byte e' cambiato sul NAS conservando il timbro (marciume di un bit): la copia "verificata" non e' piu' integra e solo una rilettura lo dice. */
  val tampered: Boolean,
  /** Gli SHA-256 dei byte che il PC ha messo al solito percorso. */
  val pcShas: List<Sha256>,
  /** Gli SHA-256 delle righe del telefono presenti adesso. */
  val phoneShas: List<Sha256>,
  /** C'e' un file preparato (un'attesa di caricamento) con questi byte. */
  val stagedShas: List<Sha256>,
)

/** Un lavoro del diario delle versioni. */
class FiveBJobFact(
  val id: Long,
  val kind: String,
  val state: String,
  val media: String,
  val version: String,
  val hold: String?,
  val problem: String?,
  val temp: String?,
)

class FiveBTransitionFact(val job: Long, val flow: String, val from: String, val to: String)

/** Una riga di un giro di "Libera spazio" e che ne e' stato della riga del telefono. */
class FiveBFreeFact(val run: Long, val index: Int, val media: String, val state: String, val removedFromPhone: Boolean, val rowOnPhone: Boolean)

class FiveBState(
  val deviceId: String,
  val privateShare: String,
  val commonShare: String?,
  /** I file che c'erano prima dei lavori delle versioni, piu' quelli che il "PC" ha messo o modificato durante. */
  val before: List<NasFileFact>,
  /** I file adesso, riletti per intero. */
  val files: List<NasFileFact>,
  /** Le impronte di tutti i contenuti legittimi (telefono, PC, file preparati, catalogo). */
  val knownShas: Set<Sha256>,
  /** I byte che il PC ha sovrascritto di proposito: non si pretende che si conservino. */
  val lostShas: Set<Sha256>,
  val media: List<FiveBMediaFact>,
  val jobs: List<FiveBJobFact>,
  val transitions: List<FiveBTransitionFact>,
  /** I temporanei che i lavori aperti (versioni e guardiano) citano adesso. */
  val cited: Set<String>,
  val free: List<FiveBFreeFact>,
  val notes: List<Note>,
  val cacheHits: Int,
  val filesHashed: Int,
)

object FiveBVerifier {

  fun check(state: FiveBState, phase: Phase, showNames: Boolean = true): List<Violation> {
    val out = ArrayList<Violation>()
    fun add(invariant: Invariant, detail: String) {
      out += Violation(invariant, detail)
    }
    fun nm(path: String) = if (showNames) path else "un file"

    val privateShare = state.privateShare.lowercase(Locale.ROOT)
    val nowByKey = HashMap<String, NasFileFact>().also { map -> state.files.forEach { map[it.key] = it } }
    val citedLower = state.cited.map { it.lowercase(Locale.ROOT) }.toSet()
    val openMedia = state.jobs.filter { !terminal(it.state) }.map { it.media }.toSet()

    state.notes.forEach { add(it.invariant, it.text) }

    // 5. Hash al 100%.
    if (phase == Phase.FINAL) {
      if (state.cacheHits != 0) add(Invariant.HASH_100, "la rilettura finale ha usato una cache per ${state.cacheHits} file")
      if (state.filesHashed < state.files.size) add(Invariant.HASH_100, "la rilettura finale ha riletto ${state.filesHashed} file su ${state.files.size}")
    }

    // 3. Nessuna sostituzione: un file che c'era ha ancora i suoi byte e il suo `fileId`, allo stesso posto o spostato da/verso Versioni/
    // (le archiviazioni e gli scambi della 5B conservano byte e `fileId`).
    for (old in state.before.filter { !it.appArea }) {
      val same = nowByKey[old.key]
      if (same != null && same.sha == old.sha && same.size == old.size && same.fileId == old.fileId) continue
      val moved = if (old.fileId == null) null else state.files.firstOrNull { !it.appArea && it.fileId == old.fileId && it.share.equals(old.share, ignoreCase = true) }
      when {
        moved == null -> add(Invariant.REPLACED, "sparito un file che c'era (${nm(old.path)})")
        moved.sha != old.sha || moved.size != old.size -> add(Invariant.REPLACED, "byte cambiati in un file che c'era (${nm(old.path)})")
        VersionPaths.isInVersions(parse(moved.path)) == VersionPaths.isInVersions(parse(old.path)) ->
          add(Invariant.REPLACED, "un file che c'era e' stato spostato, ma non da o verso Versioni/ (${nm(old.path)})")
      }
    }

    // 1, 2, 10. I file nuovi visibili (un file spostato tiene il suo `fileId`: non e' nuovo).
    val beforeIds = state.before.filter { !it.appArea }.mapNotNull { it.fileId }.toSet()
    val knownShas = state.knownShas + state.before.filter { !it.appArea }.map { it.sha }
    val privateBeforeShas = state.before.filter { !it.appArea && it.share.lowercase(Locale.ROOT) == privateShare }.map { it.sha }.toSet()
    val newVisible = state.files.filter { !it.appArea && (it.fileId == null || it.fileId !in beforeIds) && state.before.none { b -> b.key == it.key } }
    for (f in newVisible) {
      val inPrivate = f.share.lowercase(Locale.ROOT) == privateShare
      if (f.sha !in knownShas) add(Invariant.HALF_FILE, "file visibile con byte che non sono di nessun contenuto noto (${nm(f.path)})")
      val lower = f.path.lowercase(Locale.ROOT)
      if (!(inPrivate && (lower.startsWith("backup/") || lower.startsWith("versioni/")))) {
        add(Invariant.OUT_OF_PLACE, "file scritto fuori dalle radici ammesse (${nm(f.path)})")
      }
      if (inPrivate && f.sha in privateBeforeShas) add(Invariant.DUPLICATE, "doppione di un file che c'era gia' (${nm(f.path)})")
    }
    newVisible.filter { it.share.lowercase(Locale.ROOT) == privateShare }.groupBy { it.sha }.filterValues { it.size > 1 }.forEach { (_, same) ->
      add(Invariant.DUPLICATE, "gli stessi byte pubblicati ${same.size} volte (${same.joinToString { nm(it.path) }})")
    }

    // 6. Temporanei.
    for (f in state.files.filter { it.appArea && state.before.none { b -> b.key == it.key } }) {
      val lower = f.path.lowercase(Locale.ROOT)
      if (!lower.startsWith("${NasFileFact.APP_DIR}tmp/")) {
        add(Invariant.OUT_OF_PLACE, "file dell'app che il backup non scrive (${nm(f.path)})")
        continue
      }
      when {
        !isBackupTemp(f.path, state.deviceId) -> add(Invariant.TEMP, "temporaneo che non e' del backup (${nm(f.path)})")
        phase == Phase.FINAL -> add(Invariant.TEMP, "temporaneo rimasto a lavoro finito (${nm(f.path)})")
        lower !in citedLower -> add(Invariant.TEMP, "temporaneo non citato dal diario (${nm(f.path)})")
      }
    }

    // 7. Catalogo coerente con il NAS: ogni copia presente c'e', della sua dimensione, e se e' verificata ha i byte della sua versione.
    // A meta' un media con un lavoro aperto puo' avere la rinomina fatta e il catalogo non ancora aggiornato (la ripresa lo sistema).
    for (m in state.media) {
      if (phase == Phase.MID && m.label in openMedia) continue
      val places = HashSet<String>()
      for (c in m.copies.filter { it.present }) {
        val key = NasFileFact.keyOf(c.share, c.path)
        if (!places.add(key)) add(Invariant.CATALOG, "il catalogo ha due copie dello stesso media nello stesso posto (${nm(c.path)})")
        val file = nowByKey[key]
        val version = m.versions.firstOrNull { it.id == c.version }
        // Un file che il PC ha appena cambiato: il catalogo lo sapra' dalla prossima scansione (a lavoro finito la scansione c'e' stata).
        if (phase == Phase.MID && file != null && file.sha in m.pcShas) continue
        when {
          file == null -> add(Invariant.CATALOG, "il catalogo dice presente una copia che sul NAS non c'e' (${nm(c.path)})")
          file.size != c.size -> add(Invariant.CATALOG, "la copia del catalogo ha un'altra dimensione sul NAS (${nm(c.path)})")
          c.verified && !m.tampered && version?.truth != null && file.sha != version.truth ->
            add(Invariant.CATALOG, "una copia verificata dal catalogo non ha i byte della sua versione (${nm(c.path)})")
        }
      }
    }

    // 8. Diario coerente: ogni transizione e' ammessa dalla macchina a stati del suo cammino.
    for (t in state.transitions) {
      val flow = UploadFlow.entries.firstOrNull { it.code == t.flow }
      val from = UploadState.entries.firstOrNull { it.code == t.from }
      val to = UploadState.entries.firstOrNull { it.code == t.to }
      if (flow == null || from == null || to == null) {
        add(Invariant.DIARY, "stato o cammino sconosciuto nel diario (#${t.job}: ${t.from} -> ${t.to})")
      } else if (!UploadStateMachine.canTransition(flow, from, to)) {
        add(Invariant.DIARY, "transizione non ammessa ${t.from} -> ${t.to} (#${t.job})")
      }
    }

    // 13. Versioni conservate: i byte che sono stati un file visibile del media non spariscono (se non per mano del PC), e a lavoro finito le
    // non attuali stanno in Versioni/ e l'attuale al solito percorso.
    val visibleShas = state.files.filter { !it.appArea && it.share.lowercase(Locale.ROOT) == privateShare }.map { it.sha }.toSet()
    for (m in state.media) {
      for (v in m.versions) {
        val sha = v.truth ?: continue
        if (!v.seenOnNas || sha in state.lostShas) continue
        if (sha !in visibleShas) {
          add(Invariant.VERSIONS_KEPT, "una versione che era sul NAS non c'e' piu' (media ${m.label})")
          continue
        }
        if (phase == Phase.FINAL && m.mine) {
          val inVersions = state.files.any { it.sha == sha && !it.appArea && it.share.equals(m.usualShare, ignoreCase = true) && VersionPaths.isInVersions(parse(it.path)) }
          val atUsual = m.usualPath != null && nowByKey[NasFileFact.keyOf(m.usualShare, m.usualPath)]?.sha == sha
          val isCurrent = v.id == m.current
          if (!isCurrent && !inVersions && !atUsual) {
            add(Invariant.VERSIONS_KEPT, "una versione non attuale non sta in Versioni/ (media ${m.label})")
          }
        }
      }
    }

    // 12. L'attuale al solito percorso, a lavoro finito.
    if (phase == Phase.FINAL) {
      for (m in state.media.filter { it.mine && it.usualPath != null }) {
        val file = nowByKey[NasFileFact.keyOf(m.usualShare, m.usualPath!!)]
        val current = m.versions.firstOrNull { it.id == m.current }
        val sha = current?.truth
        val available = sha != null && (sha in m.phoneShas || sha in m.stagedShas || sha in visibleShas)
        when {
          file == null && available -> add(Invariant.USUAL_PATH, "il solito percorso e' vuoto, ma la versione attuale c'e' (media ${m.label})")
          // Un byte marcito sul NAS (stesso timbro) non e' colpa dei motori: i byte sono cambiati da fuori.
          // Il file del PC al solito percorso non si tocca finche' nessuno l'ha riletto e verificato: lo scambio non lo sposta (D18).
          file != null && available && !m.tampered && file.sha != sha && file.sha !in m.pcShas -> add(Invariant.USUAL_PATH, "al solito percorso non ci sono i byte della versione attuale (media ${m.label})")
        }
      }
    }

    // 9. Il lavoro arriva in fondo.
    if (phase == Phase.FINAL) {
      for (j in state.jobs) {
        if (!terminal(j.state)) add(Invariant.CONVERGENCE, "lavoro #${j.id} (${j.kind}) fermo in ${j.state}${j.hold?.let { " ($it)" }.orEmpty()}")
        if (j.state == UploadState.FAILED.code && j.problem == "unexpected") add(Invariant.CONVERGENCE, "lavoro #${j.id} (${j.kind}) fallito per un errore inatteso")
      }
    }

    // 14 (a fine corsa). "Libera spazio" non perde la traccia: ogni riga tolta dal telefono ha nel diario un elemento liberato o in dubbio, e
    // un elemento dato per liberato ha la riga davvero tolta. A lavoro finito nessuno resta nel dialogo.
    for (f in state.free) {
      val handed = f.state == "in_dialog" || f.state == "freed" || f.state == "kept" || f.state == "in_doubt"
      if (f.removedFromPhone && f.state !in setOf("freed", "in_doubt", "in_dialog")) {
        add(Invariant.FREE_SPACE, "una riga tolta dal telefono senza traccia nel diario (elemento ${f.index} del giro ${f.run}, media ${f.media}, stato ${f.state})")
      }
      if (f.state == "freed" && f.rowOnPhone) add(Invariant.FREE_SPACE, "un elemento dato per liberato con la riga ancora sul telefono (elemento ${f.index} del giro ${f.run})")
      if (handed && f.state == "kept" && f.removedFromPhone) add(Invariant.FREE_SPACE, "un elemento dato per rimasto con la riga tolta (elemento ${f.index} del giro ${f.run}, media ${f.media})")
      if (phase == Phase.FINAL && f.state == "in_dialog") add(Invariant.FREE_SPACE, "un elemento ancora nel dialogo a lavoro finito (elemento ${f.index} del giro ${f.run})")
    }
    return out
  }

  private fun terminal(state: String): Boolean = UploadState.entries.firstOrNull { it.code == state }?.terminal ?: false

  private fun parse(path: String): SharePath = try {
    SharePath.parse(path)
  } catch (_: Exception) {
    SharePath.of("?")
  }

  private fun isBackupTemp(path: String, deviceId: String): Boolean = try {
    NasBackupPaths.isBackupTempOf(SharePath.parse(path), deviceId)
  } catch (_: Exception) {
    false
  }
}

/** Lo stato della 5B scritto in un file di testo, una riga per fatto, per verificarlo da solo (`area.ps1 -Mode backup -Stage state`). */
object FiveBStateFile {
  const val HEADER = "# mcg-backup5b-state 1"
  private const val NONE = "-"

  fun write(state: FiveBState, file: File) {
    file.parentFile?.mkdirs()
    file.writeText(text(state), Charsets.UTF_8)
  }

  fun isFiveB(file: File): Boolean = file.useLines(Charsets.UTF_8) { it.firstOrNull()?.trim() == HEADER }

  fun text(state: FiveBState): String = buildString {
    appendLine(HEADER)
    appendLine("device=${state.deviceId}")
    appendLine("private=${state.privateShare}")
    appendLine("common=${state.commonShare.orEmpty()}")
    appendLine("cache=${state.cacheHits}|${state.filesHashed}")
    state.knownShas.forEach { appendLine("known=$it") }
    state.lostShas.forEach { appendLine("lost=$it") }
    state.before.forEach { appendLine("before=${fileLine(it)}") }
    state.files.forEach { appendLine("file=${fileLine(it)}") }
    for (m in state.media) {
      appendLine("media=${m.label}|${flag(m.mine)}|${m.category}|${m.current}|${m.usualShare}|${m.usualPath ?: NONE}|${flag(m.tampered)}")
      m.pcShas.forEach { appendLine("pcsha=${m.label}|$it") }
      m.phoneShas.forEach { appendLine("phonesha=${m.label}|$it") }
      m.stagedShas.forEach { appendLine("stagedsha=${m.label}|$it") }
      m.versions.forEach { appendLine("version=${m.label}|${it.id}|${it.kind}|${it.size}|${it.sha ?: NONE}|${it.catalogHash ?: NONE}|${flag(it.seenOnNas)}") }
      m.copies.forEach { appendLine("copy=${m.label}|${it.version}|${it.share}|${it.path}|${it.size}|${flag(it.verified)}|${flag(it.present)}") }
    }
    state.jobs.forEach {
      appendLine("job=${it.id}|${it.kind}|${it.state}|${it.media}|${it.version}|${it.hold ?: NONE}|${it.problem ?: NONE}|${it.temp ?: NONE}")
    }
    state.transitions.forEach { appendLine("transition=${it.job}|${it.flow}|${it.from}|${it.to}") }
    state.cited.forEach { appendLine("cited=$it") }
    state.free.forEach { appendLine("free=${it.run}|${it.index}|${it.media}|${it.state}|${flag(it.removedFromPhone)}|${flag(it.rowOnPhone)}") }
    state.notes.forEach { appendLine("note=${it.invariant.number}|${it.text.replace('\n', ' ')}") }
  }

  fun read(file: File): FiveBState = parse(file.readLines(Charsets.UTF_8))

  fun parse(lines: List<String>): FiveBState {
    var device: String? = null
    var privateShare: String? = null
    var commonShare: String? = null
    var cache = 0 to 0
    val known = LinkedHashSet<Sha256>()
    val lost = LinkedHashSet<Sha256>()
    val before = ArrayList<NasFileFact>()
    val files = ArrayList<NasFileFact>()
    class Draft(val label: String, val mine: Boolean, val category: String, val current: String, val usualShare: String, val usualPath: String?, val tampered: Boolean) {
      val pc = ArrayList<Sha256>()
      val phone = ArrayList<Sha256>()
      val staged = ArrayList<Sha256>()
      val versions = ArrayList<FiveBVersionFact>()
      val copies = ArrayList<FiveBCopyFact>()
    }
    val medias = LinkedHashMap<String, Draft>()
    val jobs = ArrayList<FiveBJobFact>()
    val transitions = ArrayList<FiveBTransitionFact>()
    val cited = LinkedHashSet<String>()
    val free = ArrayList<FiveBFreeFact>()
    val notes = ArrayList<Note>()
    for ((number, raw) in lines.withIndex()) {
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#")) continue
      fun bad(): Nothing = throw IllegalArgumentException("stato 5B: riga ${number + 1} non valida")
      val key = line.substringBefore('=')
      val value = line.substringAfter('=', "")
      val p = value.split('|')
      fun opt(s: String): String? = s.takeIf { it != NONE }
      fun need(n: Int) {
        if (p.size < n) bad()
      }
      fun draft(label: String) = medias[label] ?: bad()
      when (key) {
        "device" -> device = value
        "private" -> privateShare = value
        "common" -> commonShare = value.ifEmpty { null }
        "cache" -> {
          need(2)
          cache = (p[0].toIntOrNull() ?: bad()) to (p[1].toIntOrNull() ?: bad())
        }
        "known" -> known += Sha256.parse(value)
        "lost" -> lost += Sha256.parse(value)
        "before" -> before += fileOf(p) ?: bad()
        "file" -> files += fileOf(p) ?: bad()
        "media" -> {
          need(7)
          medias[p[0]] = Draft(p[0], p[1] == "1", p[2], p[3], p[4], opt(p[5]), p[6] == "1")
        }
        "pcsha" -> {
          need(2)
          draft(p[0]).pc += Sha256.parse(p[1])
        }
        "phonesha" -> {
          need(2)
          draft(p[0]).phone += Sha256.parse(p[1])
        }
        "stagedsha" -> {
          need(2)
          draft(p[0]).staged += Sha256.parse(p[1])
        }
        "version" -> {
          need(7)
          draft(p[0]).versions += FiveBVersionFact(
            p[1], p[2], p[3].toLongOrNull() ?: bad(), opt(p[4])?.let(Sha256::parse), opt(p[5])?.let(Sha256::parse), p[6] == "1",
          )
        }
        "copy" -> {
          need(7)
          draft(p[0]).copies += FiveBCopyFact(p[1], p[2], p[3], p[4].toLongOrNull() ?: bad(), p[5] == "1", p[6] == "1")
        }
        "job" -> {
          need(8)
          jobs += FiveBJobFact(p[0].toLongOrNull() ?: bad(), p[1], p[2], p[3], p[4], opt(p[5]), opt(p[6]), opt(p[7]))
        }
        "transition" -> {
          need(4)
          transitions += FiveBTransitionFact(p[0].toLongOrNull() ?: bad(), p[1], p[2], p[3])
        }
        "cited" -> cited += value
        "free" -> {
          need(6)
          free += FiveBFreeFact(p[0].toLongOrNull() ?: bad(), p[1].toIntOrNull() ?: bad(), p[2], p[3], p[4] == "1", p[5] == "1")
        }
        "note" -> {
          need(2)
          val n = p[0].toIntOrNull() ?: bad()
          notes += Note(Invariant.entries.firstOrNull { it.number == n } ?: bad(), value.substringAfter('|'))
        }
        else -> bad()
      }
    }
    return FiveBState(
      deviceId = device ?: throw IllegalArgumentException("stato 5B senza 'device'"),
      privateShare = privateShare ?: throw IllegalArgumentException("stato 5B senza 'private'"),
      commonShare = commonShare,
      before = before,
      files = files,
      knownShas = known,
      lostShas = lost,
      media = medias.values.map {
        FiveBMediaFact(it.label, it.mine, it.category, it.current, it.versions, it.copies, it.usualShare, it.usualPath, it.tampered, it.pc, it.phone, it.staged)
      },
      jobs = jobs,
      transitions = transitions,
      cited = cited,
      free = free,
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
