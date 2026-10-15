package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nasops.contract.NameRules
import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import java.util.Locale
import kotlin.random.Random

// Il modello del banco: cosa c'e' sul NAS secondo CHI SCRIVE IL TEST, indipendentemente dal codice sotto prova. Gli originali (con il loro
// SHA-256) sono la verita' di partenza; il modello dice dove devono stare dopo ogni passo; il verificatore (ChaosVerifier.kt) confronta il
// NAS vero (o finto) con questo.

/**
 * Un file originale: impronta e dimensione note. Nessun originale ha lo stesso contenuto di un altro. I byte ci sono nel banco (che li scrive
 * sul NAS); non ci sono nel verificatore da solo, che conosce gli originali dal manifesto.
 */
class Original(val id: Int, val sha: Sha256, val size: Long, val bytes: ByteArray? = null, val label: String = "originale#$id") {
  override fun toString(): String = "$label($size byte)"

  companion object {
    fun of(id: Int, bytes: ByteArray): Original = Original(id, Sha256.of(bytes), bytes.size.toLong(), bytes)
  }
}

/** Tutti gli originali di un giro, anche quelli che il PC aggiunge a meta' giro. */
class Ledger {
  val originals: MutableList<Original> = ArrayList()
  private val bySha = HashMap<Sha256, Original>()

  fun add(original: Original) {
    originals += original
    bySha[original.sha] = original
  }

  fun bySha(sha: Sha256): Original? = bySha[sha]

  fun byId(id: Int): Original = originals[id]

  val shas: Set<Sha256> get() = bySha.keys
}

/** Un posto sul NAS: share e percorso (componenti separati da `/`, maiuscole come scritte). Il confronto non distingue le maiuscole, come Samba. */
data class Place(val share: String, val path: String) {
  val key: String get() = share.lowercase(Locale.ROOT) + "|" + path.lowercase(Locale.ROOT)

  val name: String get() = path.substringAfterLast('/')

  val parentPath: String? get() = if ('/' in path) path.substringBeforeLast('/') else null

  fun child(name: String): Place = Place(share, if (path.isEmpty()) name else "$path/$name")

  /** Vero se questo posto e' [dir] o sta sotto [dir] (stessa share, senza distinguere le maiuscole). */
  fun isWithin(dir: Place): Boolean {
    if (!share.equals(dir.share, ignoreCase = true)) return false
    val mine = path.lowercase(Locale.ROOT)
    val other = dir.path.lowercase(Locale.ROOT)
    return mine == other || mine.startsWith("$other/")
  }

  /** Il posto che questo avrebbe se la cartella [from] diventasse [to] (stessa share di partenza). */
  fun moved(from: Place, to: Place): Place {
    require(isWithin(from))
    val rest = path.substring(from.path.length)
    return Place(to.share, to.path + rest)
  }

  /** Il percorso della cartella che contiene questo posto, come [Place]. */
  fun parent(): Place? = parentPath?.let { Place(share, it) }

  override fun toString(): String = "$share:$path"
}

/** Una voce del cestino secondo il modello: l'elemento cestinato (file o cartella intera) e gli originali che contiene. */
class TrashSlot(
  val trashId: String,
  val share: String,
  val reason: TrashReason,
  val isFolder: Boolean,
  val originalPlace: Place,
  /** Gli originali contenuti e il loro percorso relativo alla voce ("" per un file). */
  val members: Map<Int, String>,
  /** Le sottocartelle (relative) che la voce contiene, vuote comprese. */
  val subdirs: Set<String>,
) {
  override fun toString(): String = "voce del cestino (${if (isFolder) "cartella" else "file"}, ${members.size} originali, ${reason.code})"
}

/**
 * Lo stato atteso del NAS dopo l'ultimo passo finito. Per ogni originale: dove e' visibile, e in quali voci del cestino ne sta una copia
 * (una copia sola nel cestino se e' stato eliminato; una nel cestino piu' una visibile se e' stato spostato tra share).
 */
class FsModel {
  /** Originale -> dove e' visibile. */
  val visible: LinkedHashMap<Int, Place> = LinkedHashMap()

  /**
   * Tutte le cartelle che esistono o possono esistere, vuote comprese (quelle da cui sono usciti tutti i file restano sul NAS; dopo lo
   * spostamento di una cartella tra share il codice sotto prova puo' toglierla o no). Serve a NON scegliere un nome che collide. Chiave -> posto.
   */
  val dirs: LinkedHashMap<String, Place> = LinkedHashMap()

  /** Le cartelle che DEVONO esistere anche vuote: create da un'operazione o rinominate/ripristinate da una. Sottoinsieme di [dirs]. */
  val asserted: MutableSet<String> = HashSet()

  val slots: LinkedHashMap<String, TrashSlot> = LinkedHashMap()

  /** Le voci del cestino che una eliminazione definitiva sta portando via: il loro contenuto puo' mancare dal NAS senza che sia una perdita. */
  val pendingPurge: MutableSet<String> = HashSet()

  /** Registra [place] (e le cartelle sopra) fra quelle che esistono; con [assert] dice che [place] deve esserci anche vuota. */
  fun addDir(place: Place, assert: Boolean = false) {
    var current: Place? = place
    while (current != null) {
      dirs.putIfAbsent(current.key, current)
      current = current.parent()
    }
    if (assert) asserted += place.key
  }

  fun originalAt(place: Place): Int? = visible.entries.firstOrNull { it.value.key == place.key }?.key

  /** C'e' qualcosa (un file o una cartella) a questo posto, secondo il modello. */
  fun occupied(place: Place): Boolean =
    originalAt(place) != null || dirs.containsKey(place.key) || visible.values.any { it.isWithin(place) }

  fun dirExists(place: Place): Boolean = dirs.containsKey(place.key) || visible.values.any { it != place && it.isWithin(place) }

  /** Le cartelle note, anche solo possibili, per scegliere dove spostare o dove creare. */
  fun knownDirs(share: String): List<Place> {
    val all = LinkedHashMap<String, Place>()
    dirs.values.filter { it.share.equals(share, ignoreCase = true) }.forEach { all[it.key] = it }
    for (place in visible.values.filter { it.share.equals(share, ignoreCase = true) }) {
      var parent = place.parent()
      while (parent != null) {
        all.putIfAbsent(parent.key, parent)
        parent = parent.parent()
      }
    }
    return all.values.toList()
  }

  /** Le cartelle di cui si e' sicuri che esistano: con almeno un file dentro, o dichiarate ([asserted]). Le sole che si rinominano, spostano, eliminano. */
  fun certainDirs(share: String): List<Place> =
    knownDirs(share).filter { it.key in asserted || visible.values.any { file -> file.isWithin(it) } }

  fun filesUnder(dir: Place): List<Pair<Int, Place>> = visible.entries.filter { it.value != dir && it.value.isWithin(dir) }.map { it.key to it.value }

  /**
   * Il posto con i nomi delle cartelle come le scrive il NAS: Samba non distingue le maiuscole, quindi un percorso che passa da una
   * cartella gia' esistente con un'altra grafia (`Foto/x.jpg` quando la cartella e' stata rinominata `fOTO`) finisce dentro quella.
   */
  fun canonicalize(place: Place): Place {
    val parts = place.path.split('/')
    val fixed = ArrayList<String>()
    for (i in parts.indices) {
      if (i < parts.lastIndex) {
        val prefix = Place(place.share, parts.subList(0, i + 1).joinToString("/"))
        val known = dirs[prefix.key]
        fixed += known?.name ?: parts[i]
      } else {
        fixed += parts[i]
      }
    }
    return Place(place.share, fixed.joinToString("/"))
  }

  /** Gli originali che hanno almeno una copia che resta (visibile o in una voce del cestino che nessuna eliminazione sta portando via). */
  fun survives(originalId: Int): Boolean =
    originalId in visible || slots.values.any { originalId in it.members && it.trashId !in pendingPurge }

  fun slotsOf(originalId: Int): List<TrashSlot> = slots.values.filter { originalId in it.members }

  /** Sposta una cartella e tutto cio' che contiene (rinomina o spostamento nella stessa share, ripristino con un altro nome). */
  fun renameDir(from: Place, to: Place) {
    for ((id, place) in visible.entries.toList()) {
      if (place.isWithin(from)) visible[id] = place.moved(from, to)
    }
    val moved = dirs.values.filter { it.isWithin(from) }
    val assertedMoved = moved.filter { it.key in asserted }
    moved.forEach {
      dirs.remove(it.key)
      asserted.remove(it.key)
    }
    moved.forEach { addDir(it.moved(from, to)) }
    assertedMoved.forEach { asserted += it.moved(from, to).key }
    addDir(to, assert = true)
  }

  /** La cartella esce dal mondo visibile (eliminata): lei e quelle che contiene non esistono piu'. */
  fun removeDirTree(dir: Place) {
    dirs.values.filter { it.isWithin(dir) }.toList().forEach {
      dirs.remove(it.key)
      asserted.remove(it.key)
    }
  }

  /** Le cartelle sotto [dir] non sono piu' sicure (spostamento di cartella tra share: il codice sotto prova puo' averle tolte). */
  fun unassertTree(dir: Place) {
    dirs.values.filter { it.isWithin(dir) }.forEach { asserted.remove(it.key) }
  }

  /** Gli originali eliminati per sempre di proposito. */
  val gone: MutableSet<Int> = HashSet()
}

/**
 * I nomi alternativi che il codice sotto prova puo' scegliere quando la destinazione e' occupata (`foto.jpg` -> `foto (2).jpg`). Si accettano:
 *  - quelli di [NameRules.alternative] applicati al nome del piano;
 *  - quelli che partono dal nome senza il contatore che ha gia' (`Cena (1).jpg` -> `Cena (2).jpg`, non `Cena (1) (2).jpg`);
 *  - il nome alternativo di un nome alternativo (`foto (2) (2).jpg`): succede se la ripresa dopo un'interruzione riparte dal nome gia'
 *    scelto invece che da quello del piano. Non e' una perdita ne' un doppione, ma e' brutto: il banco lo conta.
 */
object AlternativeNames {
  private val COUNTER = Regex(""" \(\d+\)(?=(\.[^./ ]*)?$)""")

  private fun stripped(name: String): String = COUNTER.replaceFirst(name, "")

  fun isAlternativeOf(planned: String, actual: String): Boolean =
    actual == planned || isSingle(planned, actual) || isSingle(stripped(planned), actual) || isDouble(planned, actual)

  fun isAlternativeOfSingle(planned: String, actual: String): Boolean = isSingle(planned, actual) || isSingle(stripped(planned), actual)

  fun isDouble(planned: String, actual: String): Boolean =
    (2..99).any { first -> isSingle(NameRules.alternative(planned, first), actual) || isSingle(NameRules.alternative(stripped(planned), first), actual) }

  private fun isSingle(planned: String, actual: String): Boolean = (2..99).any { NameRules.alternative(planned, it) == actual }
}

/** I nomi che il banco usa: pochi apposta, cosi' le collisioni (E-D*) nascono da sole. Tutti validi per `SharePath`. */
object Vocabulary {
  val dirs: List<String> = listOf(
    "Foto", "Viaggi", "Famiglia", "Archivio", "Scuola", "Estate", "Mare", "Montagna", "Compleanni", "Natale",
    "2024", "2025", "Lavoro", "Ricordi", "Città di Lucca", "Perché no", "Gita e festa", "Amici",
  )

  val files: List<String> = listOf(
    "IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.JPG", "DSC_0100.jpg", "PXL_2024.png", "VID_0007.mp4", "Scatto 1.heic", "Scatto 2.heic",
    "Festa.jpg", "Gita.png", "Mare.JPG", "Tramonto.jpg", "Nonna.jpg", "Cena (1).jpg", "Cane.mov", "Torta.jpg",
  )

  /** Nomi per le cartelle che l'utente crea o a cui rinomina: mai uguali a un nome gia' in uso nel giro. */
  val renames: List<String> = listOf(
    "Da rivedere", "Preferite", "Vacanze 2025", "Stampe", "Album nuovo", "Da condividere", "Senza data", "Lavori", "Zio Carlo", "Sole",
  )
}

/** Il contenuto di un originale: un'intestazione col suo numero (per non averne due uguali) e byte casuali. */
object OriginalBytes {
  fun make(random: Random, id: Int, size: Int): ByteArray {
    val bytes = random.nextBytes(maxOf(size, 16))
    val header = "ORIG".toByteArray() + byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
    System.arraycopy(header, 0, bytes, 0, header.size)
    return bytes
  }

  /**
   * La taglia: quasi sempre piccola, spesso qualche decina di KiB, ogni tanto [mediumBytes] (piu' blocchi del ripiego dal telefono), e
   * [bigPercent] volte su cento [bigBytes] (sul NAS vero e' `chaos.bigFileMb`).
   */
  fun size(random: Random, mediumBytes: Int, bigBytes: Int, bigPercent: Int): Int {
    if (random.nextInt(100) < bigPercent) return bigBytes
    return when (random.nextInt(100)) {
      in 0 until 70 -> 200 + random.nextInt(3_000)
      in 70 until 95 -> 3_000 + random.nextInt(40_000)
      else -> mediumBytes - random.nextInt(maxOf(1, mediumBytes / 4))
    }
  }
}
