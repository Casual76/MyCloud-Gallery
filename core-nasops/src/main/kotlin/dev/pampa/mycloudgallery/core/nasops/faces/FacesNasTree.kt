package dev.pampa.mycloudgallery.core.nasops.faces

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.FacesOwnFile
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// L'ALBERO DEI DATI DEI VOLTI SUL NAS, LETTO IN MODO STRETTO (Fase 8, P8; docs/fase8-progetto.md §2.4, §2.8 passi 8-9, D31; formato §9
// punto 6-bis). La cancellazione dei volti e' l'unica cancellazione guidata da un dato remoto oltre l'auto-azzeramento: per questo chi la
// esegue **non scende mai in una cartella qualunque**. Sotto `faces/<G>/` si visitano solo le cartelle con la forma che l'app scrive
// (`<dispositivo>/head`, `<dispositivo>/seg`, `leases/<impronta>/<gruppo>`) e si raccolgono solo i file con la forma esatta di
// `FacesOwnFile.classify` della stessa generazione. Tutto il resto (nomi strani, file sciolti, punti di rianalisi, cartelle gia' viste:
// un ciclo) si **ignora e si conta**: un collegamento messo da chi ha la password non porta mai la cancellazione su un media.
//
// Il percorso di ogni file lo costruisce `NasLayout` dalla generazione e dai nomi validati, mai una stringa letta dal NAS (i nomi letti
// servono solo a dire "questo c'e'": un nome che non ha la forma giusta non diventa mai un percorso).

/** Un file dei volti trovato: il posto (forma esatta, generazione giusta) e la voce dell'elenco (dimensione, data, identita'). */
internal class FacesFound(val file: FacesOwnFile, val entry: NasEntry) {
  override fun toString(): String = "FacesFound(${entry.size} B)"
}

/** Cio' che una visita ha trovato: i file dei volti (al piu' il tetto chiesto), quante voci ha ignorato e se non ha visto tutto. */
internal class FacesTreeWalk(val files: List<FacesFound>, val ignored: Int, val incomplete: Boolean) {
  override fun toString(): String = "FacesTreeWalk(${files.size}, ignorati=$ignored, incompleto=$incomplete)"
}

/** Il NAS non risponde (o nega l'elenco): la visita si ferma, niente si decide. */
internal class FacesTreeUnreachable(val error: NasError) : RuntimeException(null, null, false, false)

/** La visita stretta di `faces/<G>/` di una share (vedi sopra). Solo lettura. */
internal class FacesNasTree(private val reader: NasMediaReader, private val purpose: Purpose = Purpose.BACKGROUND) {

  /**
   * I file dei volti della generazione [generation] nella share [share]: tutti (cartelle dei dispositivi e prenotazioni) o, con [deviceOnly],
   * solo quelli della cartella di quel dispositivo. Al piu' [max] file: oltre, [FacesTreeWalk.incomplete].
   *
   * @throws FacesTreeUnreachable se un elenco non riesce (una cartella che non c'e' e' vuota, non un errore).
   */
  suspend fun walk(share: String, generation: FaceGeneration, deviceOnly: String?, max: Int): FacesTreeWalk {
    val state = WalkState(max)
    val root = NasLayout.facesGenerationDir(generation)
    val top = list(share, root, state) ?: return state.result()
    for (entry in top) {
      if (state.full) break
      if (!state.enterable(entry)) continue
      when {
        entry.name == NasLayout.FACES_LEASES && deviceOnly == null -> leases(share, generation, root.child(NasLayout.FACES_LEASES), state)
        TrustIds.isValid(entry.name) && (deviceOnly == null || entry.name == deviceOnly) -> device(share, generation, entry.name, state)
        else -> state.ignored++
      }
    }
    return state.result()
  }

  /** Vero se sotto `faces/` c'e' una voce che il cestino di rete della radice [bin] tiene (E-F34: `<cestino>/.mycloudgallery/faces/...`). */
  suspend fun recycleBinHolds(share: String, bin: String): Boolean {
    val dir = runCatching { NasLayout.FACES_DIR.components.fold(SharePath.of(bin)) { acc, c -> acc.child(c) } }.getOrNull() ?: return false
    var any = false
    when (val r = reader.list(share, dir, purpose, pageSize = 1) { page -> if (page.isNotEmpty()) any = true }) {
      is NasResult.Ok -> Unit
      is NasResult.Err -> if (r.error != NasError.NotFound) throw FacesTreeUnreachable(r.error)
    }
    return any
  }

  /** Le cartelle di generazione in `faces/`: i nomi validi (cartelle vere, non collegamenti) e quanti nomi si sono ignorati. */
  suspend fun generations(share: String, max: Int): Triple<List<FaceGeneration>, Int, Boolean> {
    val state = WalkState(Int.MAX_VALUE)
    val entries = list(share, NasLayout.FACES_DIR, state) ?: return Triple(emptyList(), 0, false)
    val found = ArrayList<FaceGeneration>()
    for (entry in entries) {
      val generation = FaceGeneration.parse(entry.name)
      if (!entry.isDirectory || entry.isReparsePoint || generation == null) {
        state.ignored++
        continue
      }
      if (found.none { it == generation }) found += generation
    }
    return Triple(found.take(max), state.ignored, found.size > max || state.incomplete)
  }

  // --- implementazione --------------------------------------------------------------------------------------------------------

  private class WalkState(val max: Int) {
    val files = ArrayList<FacesFound>()
    var ignored = 0
    var incomplete = false

    /** Le identita' delle cartelle gia' visitate: una cartella che torna e' un ciclo (o un collegamento) e non si visita due volte. */
    private val seenDirs = HashSet<Long>()

    val full: Boolean get() = files.size >= max

    /** Una cartella in cui si puo' scendere: una cartella vera, non un punto di rianalisi, mai vista prima. */
    fun enterable(entry: NasEntry): Boolean {
      val id = entry.fileId
      val ok = entry.isDirectory && !entry.isReparsePoint && (id == null || seenDirs.add(id))
      if (!ok) ignored++
      return ok
    }

    fun add(file: FacesOwnFile?, entry: NasEntry, accept: (FacesOwnFile) -> Boolean) {
      if (entry.isDirectory || entry.isReparsePoint || file == null || !accept(file)) {
        ignored++
        return
      }
      if (full) {
        incomplete = true
        return
      }
      files += FacesFound(file, entry)
    }

    fun result() = FacesTreeWalk(files.toList(), ignored, incomplete)
  }

  private suspend fun device(share: String, generation: FaceGeneration, deviceId: String, state: WalkState) {
    val dir = NasLayout.facesDeviceDir(generation, deviceId)
    val children = list(share, dir, state) ?: return
    for (entry in children) {
      if (state.full) {
        state.incomplete = true
        return
      }
      if (!state.enterable(entry)) continue
      val sub = when (entry.name) {
        "head" -> NasLayout.facesHeadDir(generation, deviceId)
        "seg" -> NasLayout.facesSegmentDir(generation, deviceId)
        else -> {
          state.ignored++
          continue
        }
      }
      for (file in list(share, sub, state) ?: continue) {
        val path = runCatching { sub.child(file.name) }.getOrNull()
        state.add(path?.let(FacesOwnFile::classify), file) { found ->
          found.generation == generation && when (found) {
            is FacesOwnFile.Head -> found.deviceId == deviceId && entry.name == "head"
            is FacesOwnFile.Segment -> found.deviceId == deviceId && entry.name == "seg"
            is FacesOwnFile.LeaseGeneration -> false
          }
        }
      }
    }
  }

  private suspend fun leases(share: String, generation: FaceGeneration, root: SharePath, state: WalkState) {
    for (resource in list(share, root, state) ?: return) {
      if (state.full) {
        state.incomplete = true
        return
      }
      if (!state.enterable(resource)) continue
      if (!Hashes.isHashName(resource.name)) {
        state.ignored++
        continue
      }
      val resourceDir = root.child(resource.name)
      for (shard in list(share, resourceDir, state) ?: continue) {
        if (!state.enterable(shard)) continue
        if (NasLayout.parseVersionName(shard.name) == null) {
          state.ignored++
          continue
        }
        val shardDir = resourceDir.child(shard.name)
        for (file in list(share, shardDir, state) ?: continue) {
          val path = runCatching { shardDir.child(file.name) }.getOrNull()
          state.add(path?.let(FacesOwnFile::classify), file) { found -> found is FacesOwnFile.LeaseGeneration && found.generation == generation }
        }
      }
    }
  }

  /** Le voci di [dir] (null se la cartella non c'e'); un elenco troncato dal tetto del server rende la visita incompleta. */
  private suspend fun list(share: String, dir: SharePath, state: WalkState): List<NasEntry>? {
    val out = ArrayList<NasEntry>()
    return when (val r = reader.list(share, dir, purpose) { page -> if (out.size < MAX_LISTED) out += page.take(MAX_LISTED - out.size) }) {
      is NasResult.Ok -> {
        if (r.value.cappedAt != null || r.value.count > out.size) state.incomplete = true
        out
      }
      is NasResult.Err -> if (r.error == NasError.NotFound) null else throw FacesTreeUnreachable(r.error)
    }
  }

  private companion object {
    /** Voci per cartella considerate in una visita (come la pulizia dei propri file). */
    const val MAX_LISTED = 20_000
  }
}
