package dev.pampa.mycloudgallery.core.nasops.faces

import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import dev.pampa.mycloudgallery.core.nasops.testing.FakeFacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryFacesNas
import dev.pampa.mycloudgallery.core.nasops.testing.InMemoryFacesNasEraser
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/** Fase 8 (P0, §5.2): l'esecutore di riferimento della cancellazione dei volti passa la suite che passera' quello vero di P8. */
class InMemoryFacesNasEraserContractTest : FacesNasEraserContract() {

  override fun world(privateShare: String, commonShare: String, maxFilesPerRound: Int): World {
    val nas = InMemoryFacesNas(commonShare)
    val facts = FakeFacesEraseFacts()
    var blocked: FacesEraseStop? = null
    val eraser = InMemoryFacesNasEraser(nas, privateShare, facts, gate = { blocked }, maxFilesPerRound = maxFilesPerRound)
    return object : World {
      override val eraser: FacesNasEraser = eraser
      override val facts: FakeFacesEraseFacts = facts

      override fun put(share: String, path: SharePath) = nas.put(share, path)

      override fun files(share: String): List<SharePath> = nas.files(share)

      override fun removed(): List<Pair<String, SharePath>> = nas.removed.toList()

      override fun failNext(operations: Int) {
        nas.failNext = operations
      }

      override fun block(stop: FacesEraseStop?) {
        blocked = stop
      }

      override fun recycleBin(name: String?) {
        nas.recycleBin = name
      }
    }
  }
}
