package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nasops.contract.ItemFlow
import dev.pampa.mycloudgallery.core.nasops.contract.LocalTrashRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpItemState
import dev.pampa.mycloudgallery.core.nasops.contract.OpRecord
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpsStore
import dev.pampa.mycloudgallery.core.nasops.contract.TrashEntryState
import dev.pampa.mycloudgallery.core.trust.TrustFormat
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.security.MessageDigest
import java.util.Locale

// Il VERIFICATORE degli invarianti della prova delle 500 interruzioni (docs/fase4-progetto.md §5.5). Non sa niente del codice sotto prova:
// legge il NAS (finto o vero) solo con `NasMediaReader`, il diario solo se glielo si da', e confronta con gli originali e (se c'e')
// con lo stato atteso. Lo usano il banco JVM dopo ogni morte del processo e a fine passo, il runner sul NAS vero e, da solo, la
// verifica dell'area di prova dopo una prova sul telefono (ChaosAreaVerifier.kt).
//
// Gli invarianti del §5.5:
//   1. nessun file a meta' visibile: ogni file fuori da `.mycloudgallery/` ha l'SHA-256 di uno degli originali;
//   2. nessun doppione: ogni originale compare UNA volta fra i file visibili;
//   3. nessuna perdita: ogni originale e' visibile OPPURE nel cestino della sua share con la voce del diario locale;
//   4. hash al 100%: alla fine si rilegge tutto e ogni SHA-256 coincide;
//   5. temporanei: solo quelli propri ancora citati dal diario;
//   6. catalogo coerente: una scansione completa non trova differenze con lo stato atteso.
// piu' tre controlli di contorno che servono a non far passare un banco cieco: diario coerente, operazioni arrivate in fondo, nessun file
// dell'app fuori posto.

enum class Invariant(val number: Int, val label: String) {
  HALF_FILE(1, "nessun file a meta' visibile"),
  DUPLICATE(2, "nessun doppione"),
  LOSS(3, "nessuna perdita"),
  HASH(4, "hash al 100%"),
  TEMP(5, "temporanei solo propri e citati dal diario"),
  CATALOG(6, "catalogo coerente con lo stato atteso"),
  DIARY(7, "diario coerente con il NAS"),
  CONVERGENCE(8, "le operazioni arrivano in fondo"),
  APP_AREA(9, "nessun file dell'app fuori posto"),
  TRASH_RECORD(10, "ogni file del cestino ha la sua voce nel diario locale"),
  SCAN(11, "il NAS si legge"),
}

data class Violation(val invariant: Invariant, val detail: String) {
  override fun toString(): String = "[${invariant.number}. ${invariant.label}] $detail"
}

// =============================================================================================================================
// La scansione
// =============================================================================================================================

/**
 * Cosa si guarda sul NAS. [MediaRoot]: dove stanno i media (fuori da `.mycloudgallery/`); [AppArea]: dove sta la cartella dell'app, per i
 * temporanei e il cestino. Nel banco JVM e nel runner (area di prova "radicata") la radice dei media e la cartella dell'app sono la
 * radice della share; sul telefono la prova lavora in `mcg-it/<giro>/` per i media ma il telefono tiene `.mycloudgallery/` alla radice
 * della share: i due posti sono indipendenti.
 */
class ScanScope(val mediaRoots: List<MediaRoot>, val appAreas: List<AppArea>) {
  /** I media sotto [dir] (null = tutta la share). I percorsi del rapporto sono relativi a [dir]. */
  data class MediaRoot(val share: String, val dir: SharePath?)

  /**
   * La cartella `.mycloudgallery` di [share] sotto [base] (null = radice). Con [full] si legge tutto l'albero (e ogni file che non sia un
   * temporaneo o un file del cestino proprio e' "fuori posto" nel banco); senza, solo `tmp/` e il cestino dei [ownDevices].
   */
  data class AppArea(val share: String, val base: SharePath?, val full: Boolean)

  companion object {
    /** Il mondo del banco: una share = una radice dei media e una cartella dell'app, tutto letto. */
    fun wholeShares(shares: List<String>): ScanScope =
      ScanScope(shares.map { MediaRoot(it, null) }, shares.map { AppArea(it, null, full = true) })
  }
}

class VisibleFile(val place: Place, val size: Long, val sha: Sha256) {
  override fun toString(): String = "$place (${size} byte)"
}

class TrashFile(val share: String, val trashId: String, val rel: String, val size: Long, val sha: Sha256, val shaTail: Sha256?) {
  override fun toString(): String = "cestino $share:$trashId${if (rel.isEmpty()) "" else "/$rel"} ($size byte)"
}

class TempFile(val share: String, val name: String, val size: Long)

/** Tutto cio' che una scansione ha trovato. [problems] sono le letture che non sono riuscite: una scansione con problemi non prova niente. */
class Snapshot(
  val visible: List<VisibleFile>,
  val trash: List<TrashFile>,
  val temps: List<TempFile>,
  /** File dentro `.mycloudgallery/` che non sono temporanei, file del cestino proprio o record: (share, percorso). */
  val appOthers: List<Pair<String, String>>,
  /** Record firmati del cestino trovati: (share, id della voce). */
  val records: Set<Pair<String, String>>,
  /** Le cartelle trovate sotto le radici dei media. */
  val dirs: Set<Place>,
  val problems: List<String>,
  val filesHashed: Int,
  val bytesHashed: Long,
)

/**
 * Legge il NAS con `NasMediaReader` e calcola l'SHA-256 di OGNI file (invariante 4). Il formato dei file nel cestino e' quello dei byte
 * originali; se si decide l'involucro `MCGW` (formato-nas §2.1, ancora una proposta) il file cestinato ha 8 byte in piu' davanti: [TrashFile.shaTail]
 * e' l'impronta senza i primi 8 byte, cosi' il banco regge entrambe le scelte.
 */
class NasScanner(
  private val reader: NasMediaReader,
  private val scope: ScanScope,
  private val ownDevices: Set<String>,
  /** Con la cache un file con lo stesso timbro dell'ultima lettura non si rilegge (il NAS vero con file da 200 MB). Mai nel banco JVM. */
  private val useCache: Boolean = false,
  private val purpose: Purpose = Purpose.BACKGROUND,
) {
  private class Hashed(val size: Long, val sha: Sha256, val tail: Sha256?)

  private val cache = HashMap<String, Pair<NasEntry, Hashed>>()
  private val problems = ArrayList<String>()
  private var filesHashed = 0
  private var bytesHashed = 0L

  private class Child(val name: String, val entry: NasEntry)

  suspend fun scan(): Snapshot {
    problems.clear()
    filesHashed = 0
    bytesHashed = 0
    val visible = ArrayList<VisibleFile>()
    val dirs = LinkedHashSet<Place>()
    for (root in scope.mediaRoots) scanMedia(root, visible, dirs)
    val trash = ArrayList<TrashFile>()
    val temps = ArrayList<TempFile>()
    val others = ArrayList<Pair<String, String>>()
    val records = LinkedHashSet<Pair<String, String>>()
    for (area in scope.appAreas) scanAppArea(area, trash, temps, others, records)
    return Snapshot(visible, trash, temps, others, records, dirs, problems.toList(), filesHashed, bytesHashed)
  }

  private suspend fun children(share: String, dir: SharePath?): List<Child>? {
    val out = ArrayList<Child>()
    return when (val r = reader.list(share, dir, purpose) { page -> page.forEach { out += Child(it.name, it) } }) {
      is NasResult.Err -> {
        if (r.error != NasError.NotFound) problems += "elenco non riuscito (${r.error::class.simpleName})"
        null
      }
      is NasResult.Ok -> {
        if (r.value.cappedAt != null) problems += "elenco troncato al tetto"
        out
      }
    }
  }

  private suspend fun scanMedia(root: ScanScope.MediaRoot, visible: MutableList<VisibleFile>, dirs: MutableSet<Place>) {
    val queue = ArrayDeque<Pair<SharePath?, String>>()
    queue.add(root.dir to "")
    while (queue.isNotEmpty()) {
      val (dir, rel) = queue.removeFirst()
      val kids = children(root.share, dir) ?: continue
      for (kid in kids) {
        // La cartella dell'app, se i media stanno alla radice della share, non e' un media.
        if (root.dir == null && rel.isEmpty() && kid.name.equals(TrustFormat.ROOT_DIR, ignoreCase = true)) continue
        val relPath = if (rel.isEmpty()) kid.name else "$rel/${kid.name}"
        val path = runCatching { dir?.child(kid.name) ?: SharePath.of(kid.name) }.getOrNull()
        if (path == null) {
          problems += "nome non valido sul NAS"
          continue
        }
        if (kid.entry.isDirectory) {
          dirs += Place(root.share, relPath)
          queue.add(path to relPath)
        } else {
          val hashed = hash(root.share, path, kid.entry) ?: continue
          visible += VisibleFile(Place(root.share, relPath), hashed.size, hashed.sha)
        }
      }
    }
  }

  private suspend fun scanAppArea(
    area: ScanScope.AppArea,
    trash: MutableList<TrashFile>,
    temps: MutableList<TempFile>,
    others: MutableList<Pair<String, String>>,
    records: MutableSet<Pair<String, String>>,
  ) {
    val appRoot = area.base?.child(TrustFormat.ROOT_DIR) ?: SharePath.of(TrustFormat.ROOT_DIR)
    val queue = ArrayDeque<Pair<SharePath, List<String>>>()
    if (area.full) {
      queue.add(appRoot to emptyList())
    } else {
      queue.add(appRoot.child("tmp") to listOf("tmp"))
      for (device in ownDevices) queue.add(appRoot.child("trash").child(device) to listOf("trash", device))
    }
    while (queue.isNotEmpty()) {
      val (dir, rel) = queue.removeFirst()
      val kids = children(area.share, dir) ?: continue
      for (kid in kids) {
        val path = runCatching { dir.child(kid.name) }.getOrNull()
        if (path == null) {
          problems += "nome non valido sul NAS"
          continue
        }
        val here = rel + kid.name
        if (kid.entry.isDirectory) {
          queue.add(path to here)
          continue
        }
        when {
          here.size == 2 && here[0] == "tmp" -> temps += TempFile(area.share, kid.name, kid.entry.size)
          here.size >= 4 && here[0] == "trash" && here[2] == "files" && here[1] in ownDevices -> {
            val hashed = hash(area.share, path, kid.entry, withTail = true) ?: continue
            trash += TrashFile(area.share, here[3], here.drop(4).joinToString("/"), hashed.size, hashed.sha, hashed.tail)
          }
          here.size == 4 && here[0] == "trash" && here[2] == "rec" && here[1] in ownDevices -> records += area.share to here[3]
          else -> others += area.share to here.joinToString("/")
        }
      }
    }
  }

  private suspend fun hash(share: String, path: SharePath, entry: NasEntry, withTail: Boolean = false): Hashed? {
    val key = share.lowercase(Locale.ROOT) + "|" + path.value.lowercase(Locale.ROOT)
    if (useCache) {
      val cached = cache[key]
      if (cached != null && cached.first.size == entry.size && cached.first.modifiedAt == entry.modifiedAt && cached.first.fileId == entry.fileId) {
        return cached.second
      }
    }
    val file = when (val opened = reader.open(share, path, purpose)) {
      is NasResult.Err -> {
        problems += "apertura non riuscita (${opened.error::class.simpleName})"
        return null
      }
      is NasResult.Ok -> opened.value
    }
    try {
      val whole = MessageDigest.getInstance("SHA-256")
      val tail = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(CHUNK)
      var offset = 0L
      var head = ByteArray(0)
      while (true) {
        val n = file.read(offset, buffer, 0, buffer.size)
        if (n <= 0) break
        whole.update(buffer, 0, n)
        if (offset < HEADER_BYTES) {
          val take = minOf(n.toLong(), HEADER_BYTES - offset).toInt()
          head += buffer.copyOfRange(0, take)
          if (n > take) tail.update(buffer, take, n - take)
        } else {
          tail.update(buffer, 0, n)
        }
        offset += n
      }
      if (offset != file.size) {
        problems += "letta una dimensione diversa da quella aperta ($offset su ${file.size})"
        return null
      }
      filesHashed++
      bytesHashed += offset
      val envelope = withTail && head.size == HEADER_BYTES.toInt() && String(head, 0, 4, Charsets.ISO_8859_1) == "MCGW"
      val result = Hashed(offset, Sha256(whole.digest().toHex()), if (envelope) Sha256(tail.digest().toHex()) else null)
      if (useCache) cache[key] = entry to result
      return result
    } catch (e: NasIoException) {
      problems += "lettura non riuscita (${e.error::class.simpleName})"
      return null
    } finally {
      file.close()
    }
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

  private companion object {
    const val CHUNK = 256 * 1024
    const val HEADER_BYTES = 8L
  }
}

// =============================================================================================================================
// Il diario visto dal verificatore
// =============================================================================================================================

/** Il diario locale in sola lettura. Sul telefono non c'e' (vive nell'app): il verificatore salta allora i controlli che lo vogliono. */
interface DiaryView {
  suspend fun ops(): List<OpRecord>

  suspend fun items(): List<OpItemRecord>

  suspend fun trashEntry(trashId: String): LocalTrashRecord?

  /** Le voci del diario locale ancora nel cestino. */
  suspend fun trashed(): List<LocalTrashRecord>
}

class StoreDiary(private val store: OpsStore) : DiaryView {
  override suspend fun ops(): List<OpRecord> = store.ops(OpState.entries.toSet())

  override suspend fun items(): List<OpItemRecord> = ops().flatMap { store.items(it.id) }

  override suspend fun trashEntry(trashId: String): LocalTrashRecord? = store.trashEntry(trashId)

  override suspend fun trashed(): List<LocalTrashRecord> = store.expiredTrash(Long.MAX_VALUE, Int.MAX_VALUE)
}

// =============================================================================================================================
// Lo stato atteso
// =============================================================================================================================

/** Dove deve stare un originale visibile: un posto preciso (con o senza la possibilita' di un nome alternativo) o solo una cartella. */
class ExpectedVisible(val place: Place?, val tolerantName: Boolean, val dir: Place? = null)

/** Quello che il NAS deve contenere a operazioni finite. */
class Expectation(
  /** Gli originali che devono essere visibili una volta sola, per impronta. */
  val visible: Map<Sha256, ExpectedVisible>,
  /** Quante copie nel cestino deve avere ciascun originale (assente = zero). Null = non si controlla il numero. */
  val trashCopies: Map<Sha256, Int>?,
  /** Gli originali eliminati per sempre di proposito: non devono esserci da nessuna parte. */
  val gone: Set<Sha256>,
  /** Le cartelle che devono esistere (anche vuote). */
  val dirs: Set<Place>,
  /** Le voci del cestino attese: id -> gli originali e il loro percorso relativo. Null = non si controlla. */
  val slots: Map<String, Map<Sha256, String>>?,
)

/** Tutto cio' che il verificatore ha in mano oltre alla scansione. */
class VerifyContext(
  val ledger: Ledger,
  val diary: DiaryView?,
  /** Il dispositivo sotto prova (i suoi temporanei e il suo cestino); null = non si controlla chi e' il proprietario. */
  val ownDevice: String?,
  /** Quali originali hanno ancora almeno una copia che deve esserci. Di norma tutti; il modello toglie quelli di una eliminazione definitiva. */
  val survives: (Original) -> Boolean = { true },
  /** Lo stack scrive il record firmato del cestino (formato §5.7): ogni voce deve averlo. */
  val writesTrashRecords: Boolean = false,
  /** Ogni file di `.mycloudgallery/` che non sia un temporaneo o del cestino proprio e' fuori posto (il banco: si'; la share vera del telefono: no). */
  val strictAppArea: Boolean = true,
)

// =============================================================================================================================
// Gli invarianti
// =============================================================================================================================

object Verifier {

  /**
   * I controlli che devono valere SEMPRE, anche a un passo di distanza da una morte del processo: invarianti 1, 3, 5 (temporanei citati dal
   * diario), il cestino col suo diario, e i doppioni solo se spiegati da uno spostamento tra share fra "pubblicato" e "sorgente nel cestino".
   */
  suspend fun midState(s: Snapshot, c: VerifyContext): List<Violation> {
    val v = ArrayList<Violation>()
    scanProblems(s, v)
    halfFiles(s, c, v)
    val items = c.diary?.items()
    // 2 (allentato): due copie visibili sono ammesse solo se un elemento di spostamento tra share e' fra la verifica e il cestino.
    for ((sha, files) in s.visible.groupBy { it.sha }) {
      if (files.size < 2) continue
      val explained = files.size == 2 && items != null && items.any {
        it.flow == ItemFlow.MOVE_ACROSS_SHARES && it.sha256 == sha &&
          it.state in setOf(OpItemState.VERIFIED, OpItemState.PUBLISHED, OpItemState.SOURCE_RECORDED)
      }
      if (!explained) v += Violation(Invariant.DUPLICATE, "${files.size} copie visibili di un originale non spiegate da uno spostamento in corso: ${files.joinToString()}")
    }
    losses(s, c, v)
    // 5: i temporanei visti a meta' sono solo quelli propri citati da un elemento non finito.
    for (temp in s.temps) {
      val own = c.ownDevice == null || temp.name.startsWith(c.ownDevice + "-")
      val cited = items == null || items.any { item ->
        !item.state.terminal && item.tempName?.name == temp.name && (item.dstShare ?: item.srcShare).equals(temp.share, ignoreCase = true)
      }
      if (!own) v += Violation(Invariant.TEMP, "temporaneo di un altro dispositivo in ${temp.share}")
      else if (!cited) v += Violation(Invariant.TEMP, "temporaneo ${temp.name} in ${temp.share} (${temp.size} byte) non citato da nessun elemento non finito del diario")
    }
    trashAndApp(s, c, v)
    return v
  }

  /**
   * Operazioni finite: tutto quello di [midState] piu' la forma stretta (nessun doppione, nessun temporaneo) e il confronto con lo stato atteso.
   * Con [settled] falso (la prova sul telefono non e' ancora ferma) si fanno solo i controlli di [midState].
   */
  suspend fun quiescent(s: Snapshot, c: VerifyContext, e: Expectation?, settled: Boolean = true): List<Violation> {
    if (!settled) return midState(s, c)
    val v = ArrayList<Violation>()
    scanProblems(s, v)
    halfFiles(s, c, v)
    // 2: una volta sola fra i file visibili.
    for ((_, files) in s.visible.groupBy { it.sha }) {
      if (files.size > 1) v += Violation(Invariant.DUPLICATE, "${files.size} copie visibili di uno stesso originale: ${files.joinToString()}")
    }
    losses(s, c, v)
    // 5: a operazioni finite non resta nessun temporaneo.
    for (temp in s.temps) v += Violation(Invariant.TEMP, "temporaneo ${temp.name} in ${temp.share} (${temp.size} byte) rimasto a operazioni finite")
    trashAndApp(s, c, v)
    if (e != null) expectation(s, c, e, v)
    return v
  }

  private fun scanProblems(s: Snapshot, v: MutableList<Violation>) {
    s.problems.forEach { v += Violation(Invariant.SCAN, it) }
  }

  // 1
  private fun halfFiles(s: Snapshot, c: VerifyContext, v: MutableList<Violation>) {
    for (f in s.visible) {
      if (c.ledger.bySha(f.sha) == null) {
        v += Violation(Invariant.HALF_FILE, "file visibile ${f.place} (${f.size} byte) con un'impronta che non e' di nessun originale")
      }
    }
    for (t in s.trash) {
      if (c.ledger.bySha(t.sha) == null && (t.shaTail == null || c.ledger.bySha(t.shaTail) == null)) {
        v += Violation(Invariant.HALF_FILE, "file nel cestino $t con un'impronta che non e' di nessun originale")
      }
    }
  }

  // 3
  private fun losses(s: Snapshot, c: VerifyContext, v: MutableList<Violation>) {
    val present = HashSet<Sha256>()
    s.visible.forEach { present += it.sha }
    s.trash.forEach {
      present += it.sha
      it.shaTail?.let(present::add)
    }
    for (o in c.ledger.originals) {
      if (c.survives(o) && o.sha !in present) v += Violation(Invariant.LOSS, "${o} non c'e' ne' fra i file visibili ne' nel cestino")
    }
  }

  private suspend fun trashAndApp(s: Snapshot, c: VerifyContext, v: MutableList<Violation>) {
    val diary = c.diary
    if (diary != null) {
      for (id in s.trash.map { it.trashId }.toSet()) {
        val entry = diary.trashEntry(id)
        if (entry == null) {
          v += Violation(Invariant.TRASH_RECORD, "un file nel cestino non ha nessuna voce nel diario locale (la voce va scritta PRIMA della rinomina)")
        } else if (entry.state != TrashEntryState.TRASHED) {
          v += Violation(Invariant.TRASH_RECORD, "un file e' ancora nel cestino ma la sua voce del diario dice ${entry.state.code}")
        }
      }
    }
    if (c.writesTrashRecords) {
      for (t in s.trash) {
        if ((t.share to t.trashId) !in s.records) v += Violation(Invariant.TRASH_RECORD, "un file nel cestino non ha il record firmato (rec/)")
      }
    }
    if (c.strictAppArea) {
      for ((share, path) in s.appOthers) {
        if (!(c.writesTrashRecords && path.startsWith("trash/"))) v += Violation(Invariant.APP_AREA, "file dell'app fuori posto in $share: .mycloudgallery/$path")
      }
    }
  }

  private fun expectation(s: Snapshot, c: VerifyContext, e: Expectation, v: MutableList<Violation>) {
    val visibleBySha = s.visible.groupBy { it.sha }
    val trashBySha = HashMap<Sha256, Int>()
    s.trash.forEach {
      trashBySha.merge(it.sha, 1, Int::plus)
      it.shaTail?.let { tail -> trashBySha.merge(tail, 1, Int::plus) }
    }
    for (o in c.ledger.originals) {
      val actual = visibleBySha[o.sha].orEmpty()
      val wanted = e.visible[o.sha]
      if (o.sha in e.gone) {
        if (actual.isNotEmpty() || (trashBySha[o.sha] ?: 0) > 0) v += Violation(Invariant.LOSS, "$o doveva essere eliminato per sempre e c'e' ancora")
        continue
      }
      if (wanted == null) {
        if (actual.isNotEmpty()) v += Violation(Invariant.CATALOG, "$o e' visibile in ${actual.joinToString { it.place.toString() }} ma doveva stare solo nel cestino")
      } else if (actual.size == 1) {
        val at = actual.single().place
        val placeOk = wanted.place?.let { want -> at == want || (wanted.tolerantName && sameDirAlternative(want, at)) } ?: true
        val dirOk = wanted.dir?.let { dir -> at.parent()?.key == dir.key } ?: true
        if (!placeOk || !dirOk) v += Violation(Invariant.CATALOG, "$o e' in $at, doveva stare in ${wanted.place ?: wanted.dir}")
      } else if (actual.isEmpty()) {
        v += Violation(Invariant.CATALOG, "$o doveva essere visibile in ${wanted.place ?: wanted.dir} e non c'e'")
      }
      e.trashCopies?.let { copies ->
        val want = copies[o.sha] ?: 0
        val have = trashBySha[o.sha] ?: 0
        if (want != have) v += Violation(Invariant.CATALOG, "$o ha $have copie nel cestino, ne doveva avere $want")
      }
    }
    e.slots?.let { slots ->
      val seen = s.trash.groupBy { it.trashId }
      for ((id, members) in slots) {
        val files = seen[id].orEmpty()
        for ((sha, rel) in members) {
          if (files.none { it.rel == rel && (it.sha == sha || it.shaTail == sha) }) {
            v += Violation(Invariant.CATALOG, "la voce del cestino attesa non contiene l'originale al suo posto (${if (rel.isEmpty()) "file" else "dentro la cartella"})")
          }
        }
        if (files.size != members.size) v += Violation(Invariant.CATALOG, "la voce del cestino ha ${files.size} file, ne doveva avere ${members.size}")
      }
      for (id in seen.keys) {
        if (id !in slots) v += Violation(Invariant.CATALOG, "nel cestino c'e' una voce che il diario non prevede")
      }
    }
    val dirKeys = s.dirs.map { it.key }.toSet()
    for (dir in e.dirs) {
      if (dir.key !in dirKeys) v += Violation(Invariant.CATALOG, "la cartella $dir doveva esistere")
    }
    // Maiuscole: un posto atteso con maiuscole diverse da quello trovato (rinomina solo di maiuscole) e' gia' un errore di "place".
    for (dir in e.dirs) {
      val found = s.dirs.firstOrNull { it.key == dir.key }
      if (found != null && found != dir) v += Violation(Invariant.CATALOG, "la cartella ha il nome ${found.name} invece di ${dir.name}")
    }
  }

  private fun sameDirAlternative(want: Place, at: Place): Boolean =
    want.share.equals(at.share, ignoreCase = true) &&
      want.parentPath.orEmpty().equals(at.parentPath.orEmpty(), ignoreCase = true) &&
      AlternativeNames.isAlternativeOf(want.name, at.name)

  /**
   * Il diario a operazioni finite: ogni operazione e ogni elemento in uno stato terminale, nessuno FAILED (se [allowFailed] e' falso),
   * nessuno CANCELLED (se [allowCancelled] e' falso), e le voci del cestino ancora TRASHED uguali a quelle attese (se [expectedTrash] c'e').
   */
  suspend fun diary(
    c: VerifyContext,
    allowFailed: Boolean,
    allowCancelled: Boolean,
    expectedTrash: Set<String>?,
    /** Gli id con un file davvero nel cestino: una voce del diario senza file e' innocua ("un record senza file non si mostra", §2.7). */
    trashFilesPresent: Set<String> = emptySet(),
  ): List<Violation> {
    val diary = c.diary ?: return emptyList()
    val v = ArrayList<Violation>()
    for (op in diary.ops()) {
      if (!op.state.terminal) v += Violation(Invariant.CONVERGENCE, "un'operazione ${op.kind.code} e' rimasta ${op.state.code} (motivo nel diario: ${op.errorCode?.code ?: "nessuno"})")
      if (op.state == OpState.FAILED && !allowFailed) v += Violation(Invariant.CONVERGENCE, "un'operazione ${op.kind.code} e' fallita (${op.errorCode?.code}) per un guasto passeggero")
      if (op.state == OpState.CANCELLED && !allowCancelled) v += Violation(Invariant.CONVERGENCE, "un'operazione ${op.kind.code} risulta annullata senza che nessuno l'abbia annullata")
    }
    for (item in diary.items()) {
      if (!item.state.terminal) {
        v += Violation(Invariant.CONVERGENCE, "un elemento ${item.flow.code} e' rimasto ${item.state.code} (${item.errorCode?.code ?: "nessun codice"}, ${item.attempts} tentativi)")
      }
      if (item.state == OpItemState.FAILED && !allowFailed) v += Violation(Invariant.CONVERGENCE, "un elemento ${item.flow.code} e' fallito (${item.errorCode?.code}) per un guasto passeggero")
      if (item.state == OpItemState.CANCELLED && !allowCancelled) v += Violation(Invariant.CONVERGENCE, "un elemento ${item.flow.code} risulta annullato senza che nessuno l'abbia annullato")
    }
    if (expectedTrash != null) {
      val have = diary.trashed().map { it.trashId }.toSet()
      for (id in have - expectedTrash) {
        if (id in trashFilesPresent) v += Violation(Invariant.DIARY, "il diario locale ha una voce del cestino ancora attiva, con il suo file, che il modello non prevede")
      }
      for (id in expectedTrash - have) v += Violation(Invariant.DIARY, "il diario locale non ha piu' una voce del cestino che il modello prevede")
    }
    return v
  }
}
