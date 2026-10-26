package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.share.ShareBench
import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import kotlin.random.Random

// IL MONDO DI UN GIRO: i media (nell'area privata, solo sul telefono, gia' nell'area comune, nascosti, di un altro account), come li condivide
// l'utente e che cosa fa l'ambiente mentre lo fa. Tutto dal seme.

/** I media di un giro e il loro stato rispetto al diario della condivisione. */
class ChaosPool(private val bench: ShareBench, rnd: Random, config: ShareChaosConfig) {

  enum class Kind { PRIVATE, PHONE_ONLY, COMMON_REFERENCE, HIDDEN, OTHERS }

  class Entry(val id: MediaId, val kind: Kind)

  val entries: MutableList<Entry> = ArrayList()
  private var next = 1

  init {
    val privateCount = 2 + rnd.nextInt(3)
    var collisions = 0
    val usedSameName = HashSet<String>()
    repeat(privateCount) {
      // Due file con lo stesso nome in cartelle diverse: E-SH4, il secondo prende il nome alternativo.
      val sameName = rnd.nextInt(100) < 30 && collisions < 2
      if (sameName) collisions++
      var folder = FOLDERS[rnd.nextInt(FOLDERS.size)]
      while (sameName && !usedSameName.add(folder)) folder = FOLDERS[rnd.nextInt(FOLDERS.size)]
      val n = number(rnd, config)
      add(Kind.PRIVATE, bench.privateMedia(n, folder = folder, name = if (sameName) "x.jpg" else "img_$n.jpg", onPhone = rnd.nextBoolean(), video = rnd.nextInt(5) == 0))
    }
    repeat(rnd.nextInt(3)) { add(Kind.PHONE_ONLY, bench.phoneMedia(number(rnd, config))) }
    if (rnd.nextInt(100) < 45) add(Kind.COMMON_REFERENCE, bench.commonMedia(number(rnd, config), mine = rnd.nextBoolean()))
    if (rnd.nextInt(100) < 30) add(Kind.HIDDEN, bench.privateMedia(number(rnd, config), hidden = true))
    if (rnd.nextInt(100) < 25) add(Kind.OTHERS, bench.othersMedia(number(rnd, config)))
  }

  private fun add(kind: Kind, id: MediaId) {
    entries += Entry(id, kind)
  }

  /** Il numero che da' il contenuto (e la dimensione: 64 + 7 byte per numero): quasi sempre piccolo, ogni tanto di decine di KB, di rado di centinaia. */
  private fun number(rnd: Random, config: ShareChaosConfig): Int {
    val roll = rnd.nextInt(100)
    val base = when {
      roll < config.bigPercent -> BIG_BASE
      roll < config.bigPercent + config.mediumPercent -> MEDIUM_BASE
      else -> 0
    }
    return base + next++
  }

  /** I media che l'utente puo' ancora condividere: nessun elemento vivo nel diario, non di un altro account. [exclude]: gia' nell'album. */
  fun shareable(album: AlbumId? = null): List<MediaId> {
    val live = bench.items().filter { it.state != ShareItemState.FAILED && it.state != ShareItemState.CANCELLED }
    val inAlbum = if (album == null) emptySet() else bench.diary.allOps().filter { it.album == album }.flatMap { op -> bench.items().filter { it.op == op.id } }
      .filter { it.state != ShareItemState.FAILED && it.state != ShareItemState.CANCELLED }.map { it.media }.toSet()
    return entries.filter { it.kind != Kind.OTHERS }.map { it.id }.filter { id -> live.none { it.media == id } && id !in inAlbum }
  }

  /** I media miei che sono arrivati in un album e che si possono riportare (hanno un elemento `DONE` di spostamento). */
  fun bringable(): List<Pair<AlbumId, MediaId>> {
    val out = ArrayList<Pair<AlbumId, MediaId>>()
    for (op in bench.diary.allOps()) {
      for (item in bench.items().filter { it.op == op.id && it.state == ShareItemState.DONE && it.action == dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction.MOVE_IN }) {
        if (bench.library[item.media]?.place == MediaPlace.COMMON && bench.origins.origins.containsKey(item.media)) out += op.album to item.media
      }
    }
    return out
  }

  fun kindOf(id: MediaId): Kind? = entries.firstOrNull { it.id == id }?.kind

  private companion object {
    val FOLDERS = listOf("Foto", "Viaggi/Oslo", "Famiglia/Estate", "Camera")
    const val MEDIUM_BASE = 3_000
    const val BIG_BASE = 42_000
  }
}

/** Cio' che l'ambiente fa durante un passo, con i media interessati e per quanti giri. */
class Hazard(val kind: HazardKind, val media: MediaId? = null, val share: String = ShareNasTarget.PRIVATE, val rounds: Int = 2) {
  override fun toString(): String = kind.label
}

/** Applica e toglie i guasti dell'ambiente sul mondo del banco. */
class HazardRunner(private val bench: ShareBench, private val verifier: ShareChaosVerifier, private val chronicle: MutableList<String>) {
  private var current: Hazard? = null
  private var locked: Pair<String, String>? = null
  private var age = 0

  fun apply(hazard: Hazard) {
    lift()
    current = hazard
    age = 0
    when (hazard.kind) {
      HazardKind.NONE -> Unit
      HazardKind.LOCK, HazardKind.LOCK_AND_MODIFY -> {
        val m = hazard.media?.let { bench.library[it] }
        val path = m?.path
        if (m != null && path != null && m.place == MediaPlace.MY_PRIVATE) {
          bench.target.lock(ShareNasTarget.PRIVATE, path)
          locked = ShareNasTarget.PRIVATE to path
        }
      }
      HazardKind.FREEZE -> bench.target.gates[hazard.share] = GateStatus.FROZEN
      HazardKind.LOW_SPACE -> bench.target.setFreeBytes(1L shl 20)
      HazardKind.ALBUM_GATE -> bench.me.gate.blocks[ShareNasTarget.COMMON] = AlbumWriteBlock.FROZEN
    }
    if (hazard.kind != HazardKind.NONE) chronicle += "ambiente: ${hazard.kind.label}${hazard.media?.let { " (media ${it.value.takeLast(4)})" } ?: ""}"
  }

  /** L'utente ha toccato e il piano e' nato: il PC cambia il file che la copia non ha ancora letto (E-SH18). */
  fun afterStart() {
    val hazard = current ?: return
    if (hazard.kind != HazardKind.LOCK_AND_MODIFY || locked == null) return
    val id = hazard.media ?: return
    val m = bench.library[id] ?: return
    if (m.place != MediaPlace.MY_PRIVATE) return
    val original = bench.originals[id] ?: return
    bench.modifyFromPc(id, ByteArray(original.size + 17) { (it * 7 + 0x5a).toByte() })
    verifier.modifiedByPc += id
    chronicle += "ambiente: il PC cambia il file del media ${id.value.takeLast(4)}"
  }

  /** Un giro del lavoro e' passato: dopo [Hazard.rounds] giri il guasto passa. */
  fun tick() {
    age++
    if (age >= (current?.rounds ?: 0)) lift()
  }

  fun lift() {
    val hazard = current ?: return
    when (hazard.kind) {
      HazardKind.NONE -> Unit
      HazardKind.LOCK, HazardKind.LOCK_AND_MODIFY -> locked?.let { (share, path) -> bench.target.unlock(share, path) }
      HazardKind.FREEZE -> bench.target.gates.remove(hazard.share)
      HazardKind.LOW_SPACE -> bench.target.setFreeBytes(null)
      HazardKind.ALBUM_GATE -> bench.me.gate.blocks.remove(ShareNasTarget.COMMON)
    }
    locked = null
    current = null
  }
}
