package dev.pampa.mycloudgallery.core.nasops.faces

import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import dev.pampa.mycloudgallery.core.nasops.testing.FakeFacesEraseFacts
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import org.junit.After

/**
 * Fase 8, P8 (§5.2): la suite di contratto della cancellazione stretta dei volti ([FacesNasEraserContract], la stessa dell'esecutore di
 * riferimento di P0) sul [NasFacesEraser] **vero**, sopra il simulatore SMB con la sessione e le primitive vere: stessi file tolti, stessi
 * esiti.
 */
class NasFacesEraserContractTest : FacesNasEraserContract() {

  private val opened = mutableListOf<FacesSmbWorld>()

  @After
  fun closeWorlds() = opened.forEach { it.close() }

  override fun world(privateShare: String, commonShare: String, maxFilesPerRound: Int): World {
    val smb = FacesSmbWorld(privateShare, commonShare, maxFilesPerRound).also { opened += it }
    return object : World {
      override val eraser: FacesNasEraser = smb.eraser
      override val facts: FakeFacesEraseFacts = smb.facts

      override fun put(share: String, path: SharePath) {
        smb.put(share, path)
      }

      override fun files(share: String): List<SharePath> = smb.files(share)

      override fun removed(): List<Pair<String, SharePath>> = smb.removed()

      override fun failNext(operations: Int) = smb.failNext(operations)

      override fun block(stop: FacesEraseStop?) {
        smb.blocked = stop
      }

      override fun recycleBin(name: String?) {
        smb.recycleBin = name
      }
    }
  }
}
