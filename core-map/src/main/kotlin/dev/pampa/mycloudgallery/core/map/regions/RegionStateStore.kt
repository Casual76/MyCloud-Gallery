package dev.pampa.mycloudgallery.core.map.regions

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.map.contract.GeoBox
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.map.contract.RegionId
import dev.pampa.mycloudgallery.core.map.contract.RegionKind
import dev.pampa.mycloudgallery.core.map.contract.RegionRadius
import dev.pampa.mycloudgallery.core.map.contract.RegionSpec
import dev.pampa.mycloudgallery.core.map.contract.RegionState
import dev.pampa.mycloudgallery.core.map.contract.RegionStatus
import dev.pampa.mycloudgallery.core.map.contract.RegionStop
import java.io.File
import java.io.IOException

// LO STATO DELLE REGIONI SU DISCO (Fase 9, P5; docs/fase9-progetto.md §2.9, D26, D37, E-MAP11). Un file di testo in `noBackupFilesDir/map/` (cosi' l'azzeramento lo toglie con la cartella e il
// backup di Android non lo porta fuori): una riga per regione, scritta in modo atomico (`StateFiles`). E' la memoria di "quali zone ho scaricato, quanto, e se sono complete": il database di
// MapLibre ha le tessere, qui c'e' cio' che serve a sapere se una regione e' completa (`covers`) e se riprende. Mai nei log: le righe dicono le zone scaricate.

/** Dove si tiene lo stato delle regioni: un file in produzione, la memoria nelle prove. */
interface RegionStateStore {
  /** Le regioni salvate; un file che non c'e' o che non si legge vale "nessuna" (le righe rovinate si saltano). */
  fun load(): List<RegionState>

  /** Salva tutte le regioni (sostituisce lo stato precedente). Puo' lanciare `IOException`: chi chiama la prende (resta lo stato in memoria). */
  fun save(regions: List<RegionState>)

  /** Toglie lo stato (l'azzeramento). */
  fun delete()
}

class MemoryRegionStateStore(initial: List<RegionState> = emptyList()) : RegionStateStore {
  var saved: List<RegionState> = initial
    private set

  var saves: Int = 0
    private set

  var failing: Boolean = false

  override fun load(): List<RegionState> = saved

  override fun save(regions: List<RegionState>) {
    if (failing) throw IOException("prova")
    saved = regions
    saves++
  }

  override fun delete() {
    saved = emptyList()
  }
}

/** Il file di stato delle regioni (`noBackupFilesDir/map/regions.state`). */
class FileRegionStateStore(private val file: File) : RegionStateStore {

  override fun load(): List<RegionState> {
    val text = try {
      if (!file.isFile) return emptyList()
      StateFiles.readText(file) ?: return emptyList()
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "stato delle regioni non letto (${e.javaClass.simpleName})" }
      return emptyList()
    }
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.firstOrNull() != HEADER) return emptyList()
    return lines.drop(1).mapNotNull { parse(it) }
  }

  override fun save(regions: List<RegionState>) {
    file.parentFile?.mkdirs()
    StateFiles.writeText(file, (listOf(HEADER) + regions.map(::format)).joinToString("\n", postfix = "\n"), label = LABEL)
  }

  override fun delete() {
    try {
      StateFiles.delete(file, label = LABEL)
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "stato delle regioni non tolto (${e.javaClass.simpleName})" }
    }
  }

  companion object {
    const val FILE_NAME: String = "regions.state"
    private const val HEADER = "mcg-regions-1"
    private const val LABEL = "regions.state"
    private const val TAG = "RegionStore"
    private const val SEP = '\t'

    /** Una riga: tipo, id, riquadro, luogo, raggio, centro, citta', nome, stato, motivo, livello, tessere, arrivate, byte, giorno. Il nome senza tabulazioni ne' a capo. */
    internal fun format(state: RegionState): String {
      val spec = state.spec
      val place = spec as? RegionSpec.AroundPlace
      val box = spec.box
      return listOf(
        spec.kind.code, spec.id.code, box.westE6, box.southE6, box.eastE6, box.northE6,
        place?.placeId ?: 0, place?.radius?.km ?: 0, place?.center?.latE6 ?: 0, place?.center?.lonE6 ?: 0, if (place?.urban == true) 1 else 0,
        clean((spec as? RegionSpec.Named)?.name ?: place?.name.orEmpty()),
        state.status.code, state.stop?.code ?: "", state.maxZoom, state.totalTiles, state.completedTiles, state.bytes, state.completedDay ?: 0,
      ).joinToString(SEP.toString())
    }

    internal fun parse(line: String): RegionState? = try {
      val f = line.split(SEP)
      require(f.size == 19)
      val kind = RegionKind.entries.first { it.code == f[0] }
      val id = RegionId(f[1])
      val box = GeoBox(f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt())
      val spec: RegionSpec = if (kind == RegionKind.AROUND_PLACE) {
        RegionSpec.AroundPlace(f[6].toInt(), RegionRadius.entries.first { it.km == f[7].toInt() }, GeoE6(f[8].toInt(), f[9].toInt()), f[10] == "1", f[11])
      } else {
        RegionSpec.Named(id, kind, box, f[11])
      }
      require(spec.id == id)
      val stop = f[13].takeIf { it.isNotEmpty() }?.let { code -> RegionStop.entries.first { it.code == code } }
      RegionState(
        spec = spec,
        status = RegionStatus.entries.first { it.code == f[12] },
        completedTiles = f[16].toLong(),
        totalTiles = f[15].toLong(),
        bytes = f[17].toLong(),
        completedDay = f[18].toInt().takeIf { it != 0 },
        maxZoom = f[14].toInt(),
        stop = stop,
      )
    } catch (_: RuntimeException) {
      null
    }

    private fun clean(text: String): String = text.filter { it >= ' ' && it != '\u007f' }.take(80)
  }
}
