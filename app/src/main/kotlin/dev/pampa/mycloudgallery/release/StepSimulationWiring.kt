package dev.pampa.mycloudgallery.release

import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.gallery.GalleryGraph
import dev.pampa.mycloudgallery.nas.NasGraph
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.feature.diagnostics.release.StepSources

/**
 * Le porte che la build **debug** riempie all'avvio (`DeveloperTools.installSearch` e `installPeople`): senza, "Simula il passo" dice "l'app non e'
 * ancora pronta". In release nessuno le riempie e nessuno le legge (R8 toglie le classi: `tools/check.ps1`, passo "nessun resto di debug").
 */
object StepSimulationHolder {
  @Volatile var gallery: GalleryGraph? = null

  @Volatile var faces: FacesGraph? = null
}

/** Il montaggio delle fonti vere della simulazione sopra i grafi dell'app. Solo letture: [LiveStepSources] non ha altro. */
object StepSimulationWiring {

  fun live(gallery: GalleryGraph, faces: FacesGraph?): StepSources {
    val nas = gallery.nas
    return LiveStepSources(
      backup = { gallery.backup?.summary() },
      index = { indexFacts(gallery, nas) },
      faces = { facesFacts(gallery, nas, faces) },
      shares = { sharesOf(nas) },
      nasSpace = nas?.let { StepNasSpaces.over(it.organizer.space) } ?: StepNasSpace { null },
    )
  }

  /** Dalle fonti messe nelle porte del debug (null se l'app non e' ancora montata). */
  fun liveFromHolder(): StepSources? = StepSimulationHolder.gallery?.let { live(it, StepSimulationHolder.faces) }

  /** Le due share per ruolo: l'area privata dell'account e l'area comune, se ci sono. */
  internal fun sharesOf(nas: NasGraph?): StepShares? {
    val areas = nas?.runtime?.areas ?: return null
    val priv = areas.firstOrNull { it.area == NasArea.PRIVATE }?.share ?: return null
    return StepShares(priv, areas.firstOrNull { it.area == NasArea.COMMON }?.share)
  }

  /** Quanti media del NAS il catalogo conosce per area (la sola lettura del catalogo) e i byte per voce della stima della replica. */
  private suspend fun indexFacts(gallery: GalleryGraph, nas: NasGraph?): StepIndexFacts? {
    val runtime = nas?.runtime ?: return null
    val catalog = gallery.coreIfReady()?.catalog ?: return null
    var private = 0L
    var common = 0L
    for (area in runtime.areas) {
      val count = catalog.countPresentNas(runtime.account, area.share).toLong()
      if (area.area == NasArea.COMMON) common += count else private += count
    }
    val perRecord = nas.quality.value?.estimates?.values?.filter { it.items > 0 }?.maxOfOrNull { it.bytes / it.items }
    return StepIndexFacts(private, common, perRecord)
  }

  /** Persone attiva qui, e i media della Libreria (telefono dal piano del backup, NAS dal catalogo) da cui si stima. */
  private suspend fun facesFacts(gallery: GalleryGraph, nas: NasGraph?, faces: FacesGraph?): StepFacesFacts? {
    val graph = faces ?: return null
    if (!graph.activeHere()) return StepFacesFacts(activeHere = false, mediaCount = 0)
    val phone = gallery.backup?.summary()?.let { it.linked.items + it.similar.items + it.fresh.items }?.toLong() ?: 0L
    val onNas = indexFacts(gallery, nas)?.let { it.privateRecords + it.commonRecords } ?: 0L
    return StepFacesFacts(activeHere = true, mediaCount = phone + onNas)
  }
}
