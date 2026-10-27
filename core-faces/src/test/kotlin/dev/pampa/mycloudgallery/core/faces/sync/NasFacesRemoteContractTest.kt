package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemoteContract
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

/**
 * Il NAS dei volti VERO (`NasFacesRemote` sopra `NasFileSystem`) passa la suite di contratto di P0 (`FacesRemoteContract`, docs/fase8-progetto.md
 * §5.2; riga P3 del §4.4: "`FacesRemote` vero passa la suite di contratto di P0"). La share e' la [MemoryShare] con la semantica di Samba; le
 * scritture di ogni dispositivo si registrano con il suo id ([RecordingShare]).
 */
class NasFacesRemoteContractTest : FacesRemoteContract() {
  override fun world(share: String, common: String): World {
    val area = MemoryShare(share)
    val log = java.util.Collections.synchronizedList(mutableListOf<Pair<String, SharePath>>())
    return object : World {
      override fun remote(deviceId: String): FacesRemote = NasFacesRemote(RecordingShare(area, deviceId, log), deviceId, { common })

      override fun put(path: SharePath, bytes: ByteArray) = area.plant(path, bytes)

      override fun files(): List<SharePath> = area.paths()

      override fun writes(): List<Pair<String, SharePath>> = log.toList()

      override fun failNext(operations: Int) {
        area.failNext = operations
      }
    }
  }
}
