package dev.pampa.mycloudgallery.core.nasops.chaos

import dev.pampa.mycloudgallery.core.nasops.contract.TrashReason
import kotlin.random.Random

// I passi di un giro: cio' che un utente fa sul NAS, in ordine casuale ma sempre valido (mai una richiesta che il piano rifiuterebbe).
// Un passo e' simbolico: dice COSA (quali posti, quali voci del cestino); la richiesta vera la costruisce il giro leggendo il NAS.

/** Un elemento scelto dall'utente: un file o una cartella a un certo posto. */
class Src(val place: Place, val isFolder: Boolean) {
  override fun toString(): String = "${if (isFolder) "cartella" else "file"} $place"
}

sealed class StepSpec(val kind: String) {
  /** Le primitive che il passo usa di sicuro: i colpi "puntati" scelgono fra queste. */
  abstract val primitives: List<Primitive>

  /** Quanti elementi tocca (per stimare i punti dove far cadere i colpi). */
  abstract val itemCount: Int

  /** Il passo si puo' annullare dopo una morte del processo (l'utente tocca "Ferma"). */
  open val cancellable: Boolean get() = false

  abstract fun describe(): String

  class CreateFolder(val share: String, val parent: String?, val name: String) : StepSpec("crea cartella") {
    override val primitives = listOf(Primitive.MKDIRS, Primitive.STAT)
    override val itemCount = 1

    override fun describe() = "crea cartella ${Place(share, parent?.let { "$it/$name" } ?: name)}"
  }

  class Rename(val src: Src, val newName: String, val caseOnly: Boolean) : StepSpec(if (caseOnly) "rinomina solo maiuscole" else "rinomina") {
    override val primitives = if (caseOnly) listOf(Primitive.STAT, Primitive.RENAME) else listOf(Primitive.STAT, Primitive.MKDIRS, Primitive.RENAME)
    override val itemCount = 1

    override fun describe() = "rinomina $src in '$newName'"
  }

  class Move(val sources: List<Src>, val destShare: String, val destDir: String?, val crossShare: Boolean, val fallback: Boolean, val cancel: Boolean) :
    StepSpec(if (crossShare) (if (fallback) "sposta tra share (ripiego)" else "sposta tra share") else "sposta") {
    override val primitives = when {
      !crossShare -> listOf(Primitive.MKDIRS, Primitive.RENAME)
      fallback -> listOf(
        Primitive.OPEN, Primitive.OPEN_TEMP, Primitive.WRITE_TEMP, Primitive.FINISH_TEMP, Primitive.READ, Primitive.READ_ALL,
        Primitive.DELETE_OWN_TEMP, Primitive.PUBLISH_TEMP, Primitive.TRASH_MOVE,
      )
      else -> listOf(
        Primitive.STAT, Primitive.LIST, Primitive.SPACE, Primitive.COPY_TO_TEMP, Primitive.OPEN, Primitive.READ_ALL,
        Primitive.PUBLISH_TEMP, Primitive.TRASH_MOVE, Primitive.DELETE_OWN_TEMP,
      )
    }
    override val itemCount = sources.size
    override val cancellable: Boolean get() = cancel

    override fun describe() = "${kind}: ${sources.joinToString("; ")} -> ${Place(destShare, destDir ?: "")}${if (cancel) " (con Ferma)" else ""}"
  }

  class Trash(val sources: List<Src>, val cancel: Boolean) : StepSpec("elimina") {
    override val primitives = listOf(Primitive.STAT, Primitive.TRASH_MOVE)
    override val itemCount = sources.size
    override val cancellable: Boolean get() = cancel

    override fun describe() = "elimina ${sources.joinToString("; ")}${if (cancel) " (con Ferma)" else ""}"
  }

  class Restore(val trashIds: List<String>) : StepSpec("ripristina") {
    override val primitives = listOf(Primitive.STAT, Primitive.MKDIRS, Primitive.RENAME)
    override val itemCount = trashIds.size

    override fun describe() = "ripristina ${trashIds.size} voci del cestino"
  }

  class Purge(val trashIds: List<String>) : StepSpec("elimina subito") {
    override val primitives = listOf(Primitive.DELETE_IF_UNCHANGED, Primitive.LIST, Primitive.RMDIR_IF_EMPTY)
    override val itemCount = trashIds.size

    override fun describe() = "elimina subito ${trashIds.size} voci del cestino"
  }

  /** Il PC (un altro dispositivo) scrive un file nuovo: niente colpi, ma cambia il mondo (e crea collisioni per i ripristini). */
  class PcCreate(val place: Place, val size: Int) : StepSpec("il PC crea un file") {
    override val primitives = emptyList<Primitive>()
    override val itemCount = 0

    override fun describe() = "il PC crea un file in $place"
  }

  /** Passano 31 giorni e parte la pulizia del cestino scaduto (ora finta). */
  class ExpirePurge : StepSpec("pulizia del cestino scaduto") {
    override val primitives = listOf(Primitive.STAT, Primitive.DELETE_IF_UNCHANGED, Primitive.LIST, Primitive.RMDIR_IF_EMPTY)
    override val itemCount = 3

    override fun describe() = "passano 31 giorni e parte la pulizia del cestino"
  }
}

/** Sceglie il passo successivo guardando lo stato del modello. */
class StepPlanner(
  private val random: Random,
  private val model: FsModel,
  private val shares: List<String>,
  private val canAdvanceTime: Boolean,
  private val canFallback: Boolean,
  private val createdFolders: MutableSet<String>,
) {

  fun next(): StepSpec? {
    // Quando c'e' qualcosa nel cestino, ripristinarlo, eliminarlo subito o farlo scadere pesa di piu': sono le macchine a stati piu' rare.
    val inTrash = model.slots.isNotEmpty()
    val options: List<Pair<Int, () -> StepSpec?>> = listOf(
      8 to ::createFolder,
      12 to ::rename,
      6 to ::renameCaseOnly,
      14 to ::moveSameShare,
      16 to ::moveCrossShare,
      (if (inTrash) 12 else 20) to ::trash,
      (if (inTrash) 26 else 0) to ::restore,
      (if (inTrash) 12 else 0) to ::purge,
      5 to ::pcCreate,
      (if (inTrash) 8 else 0) to ::expirePurge,
    )
    repeat(12) {
      var pick = random.nextInt(options.sumOf { it.first })
      for ((weight, generator) in options) {
        if (pick < weight) {
          generator()?.let { return it }
          break
        }
        pick -= weight
      }
    }
    return null
  }

  // --- Gli elementi che l'utente puo' scegliere ----------------------------------------------------------------------------

  private fun allFiles(share: String? = null): List<Src> =
    model.visible.values.filter { share == null || it.share.equals(share, ignoreCase = true) }.map { Src(it, false) }

  /** Le cartelle SICURE (con dei file dentro o create da un'operazione) che contengono almeno [minFiles] file. */
  private fun allFolders(share: String? = null, minFiles: Int = 0): List<Src> =
    shares.filter { share == null || it.equals(share, ignoreCase = true) }.flatMap { model.certainDirs(it) }
      .filter { model.filesUnder(it).size >= minFiles }
      .map { Src(it, true) }

  private fun candidates(share: String? = null, minFolderFiles: Int = 0): List<Src> = allFiles(share) + allFolders(share, minFolderFiles)

  /** Da 1 a [max] elementi che non si contengono a vicenda. */
  private fun pickNonNested(pool: List<Src>, max: Int): List<Src> {
    if (pool.isEmpty()) return emptyList()
    val wanted = 1 + random.nextInt(max)
    val chosen = ArrayList<Src>()
    for (candidate in pool.shuffled(random)) {
      if (chosen.size >= wanted) break
      if (chosen.none { it.place.isWithin(candidate.place) || candidate.place.isWithin(it.place) }) chosen += candidate
    }
    return chosen
  }

  private fun freeName(parent: Place?, share: String, pool: List<String>): String? {
    val options = pool.filter { name -> !model.occupied(parent?.child(name) ?: Place(share, name)) }
    return options.randomOrNull(random)
  }

  // --- Un generatore per passo ----------------------------------------------------------------------------------------

  private fun createFolder(): StepSpec? {
    val share = shares.random(random)
    val parent = if (random.nextInt(100) < 50) null else model.knownDirs(share).randomOrNull(random)
    val name = freeName(parent, share, Vocabulary.renames + Vocabulary.dirs) ?: return null
    val key = (parent?.child(name) ?: Place(share, name)).key
    // Ogni cartella si crea una volta sola in un giro: due piani identici sarebbero la stessa operazione (l'impronta del piano e' l'identita').
    if (!createdFolders.add(key)) return null
    return StepSpec.CreateFolder(share, parent?.path, name)
  }

  private fun rename(): StepSpec? {
    val src = candidates(minFolderFiles = 0).randomOrNull(random) ?: return null
    val parent = src.place.parent()
    val pool = if (src.isFolder) Vocabulary.renames + Vocabulary.dirs else Vocabulary.files + Vocabulary.renames.map { it + ".jpg" }
    val newName = freeName(parent, src.place.share, pool) ?: return null
    return StepSpec.Rename(src, newName, caseOnly = false)
  }

  private fun renameCaseOnly(): StepSpec? {
    val src = candidates().filter { s -> s.place.name.any { it.isLetter() } }.randomOrNull(random) ?: return null
    val name = src.place.name
    val flipped = name.map { if (it.isLowerCase()) it.uppercaseChar() else if (it.isUpperCase()) it.lowercaseChar() else it }.joinToString("")
    val newName = if (flipped != name) flipped else return null
    return StepSpec.Rename(src, newName, caseOnly = true)
  }

  private fun moveSameShare(): StepSpec? {
    val share = shares.random(random)
    val pool = candidates(share, minFolderFiles = 0)
    if (pool.isEmpty()) return null
    val newDir = random.nextInt(100) < 25
    val base = model.knownDirs(share).randomOrNull(random)
    val dest: Place? = if (newDir) {
      val name = freeName(base, share, Vocabulary.renames + Vocabulary.dirs) ?: return null
      base?.child(name) ?: Place(share, name)
    } else if (random.nextInt(100) < 20) {
      null
    } else {
      base
    }
    val movable = pool.filter { src ->
      val sameParent = (src.place.parent()?.key) == dest?.key && (src.place.parent() != null || dest == null)
      val intoItself = src.isFolder && dest != null && dest.isWithin(src.place)
      !sameParent && !intoItself && !(dest == null && src.place.parent() == null)
    }
    val sources = pickNonNested(movable, 3)
    if (sources.isEmpty()) return null
    // Una destinazione che e' dentro un'altra sorgente non va bene.
    if (dest != null && sources.any { it.isFolder && dest.isWithin(it.place) }) return null
    return StepSpec.Move(sources, share, dest?.path, crossShare = false, fallback = false, cancel = false)
  }

  private fun moveCrossShare(): StepSpec? {
    if (shares.size < 2) return null
    val from = shares.random(random)
    val to = shares.first { !it.equals(from, ignoreCase = true) }
    val pool = candidates(from, minFolderFiles = 1).filter { !it.isFolder || model.filesUnder(it.place).isNotEmpty() }
    val sources = pickNonNested(pool, 3)
    if (sources.isEmpty()) return null
    val dest: Place? = when (random.nextInt(100)) {
      in 0 until 55 -> model.knownDirs(to).randomOrNull(random)
      in 55 until 80 -> {
        val base = model.knownDirs(to).randomOrNull(random)
        val name = freeName(base, to, Vocabulary.renames + Vocabulary.dirs) ?: return null
        base?.child(name) ?: Place(to, name)
      }
      else -> null
    }
    val fallback = canFallback && random.nextInt(100) < 25
    return StepSpec.Move(sources, to, dest?.path, crossShare = true, fallback = fallback, cancel = random.nextInt(100) < 10)
  }

  private fun trash(): StepSpec? {
    val pool = candidates(minFolderFiles = 0).filter { !it.isFolder || model.filesUnder(it.place).isNotEmpty() || random.nextInt(100) < 15 }
    val sources = pickNonNested(pool, 3)
    if (sources.isEmpty()) return null
    return StepSpec.Trash(sources, cancel = random.nextInt(100) < 10)
  }

  private fun restore(): StepSpec? {
    val pool = model.slots.values.filter { it.reason == TrashReason.DELETE }
    if (pool.isEmpty()) return null
    val chosen = pool.shuffled(random).take(1 + random.nextInt(3))
    return StepSpec.Restore(chosen.map { it.trashId })
  }

  private fun purge(): StepSpec? {
    val pool = model.slots.values.toList()
    if (pool.isEmpty()) return null
    return StepSpec.Purge(pool.shuffled(random).take(1 + random.nextInt(2)).map { it.trashId })
  }

  private fun pcCreate(): StepSpec? {
    val fromTrash = model.slots.values.filter { it.reason == TrashReason.DELETE && !it.isFolder }.randomOrNull(random)
    val place = if (fromTrash != null && random.nextInt(100) < 50 && !model.occupied(fromTrash.originalPlace)) {
      fromTrash.originalPlace
    } else {
      val share = shares.random(random)
      val parent = if (random.nextInt(100) < 30) null else model.knownDirs(share).randomOrNull(random)
      val name = freeName(parent, share, Vocabulary.files) ?: return null
      parent?.child(name) ?: Place(share, name)
    }
    return StepSpec.PcCreate(place, 200 + random.nextInt(3_000))
  }

  private fun expirePurge(): StepSpec? = if (canAdvanceTime && model.slots.isNotEmpty()) StepSpec.ExpirePurge() else null
}
