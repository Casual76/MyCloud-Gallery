package dev.pampa.mycloudgallery.core.faces.sync.chaos

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.FencedFlags
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncIdentity
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesLeases
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesRemote
import dev.pampa.mycloudgallery.core.faces.sync.RecordingShare
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.Dispatchers

// Qui sta `NasFileSystem` per i NAS che passano da una sessione SMB (Phase8ArchitectureTest: in core-faces solo sotto faces/sync/, test compresi): il
// simulatore SMB con la sessione di produzione e il NAS vero di prova dal PC. La porta del NAS dei volti e le prenotazioni di un dispositivo si costruiscono
// sopra `NasSessionManager.fileSystem(share)`, come li costruira' `:app` (P13); i colpi stanno nel trasporto sotto la sessione, non qui.

/** Una share vista da un dispositivo con il controllo dello stato prima di ogni scrittura: l'invariante "mai senza lo stato fresco" (D30). */
internal class WatchedFs(private val inner: NasFileSystem, private val onWrite: (SharePath) -> Unit) : NasFileSystem {
  override val shareName: String get() = inner.shareName

  override fun list(dir: SharePath, limit: Int): NasListing = inner.list(dir, limit)

  override fun read(path: SharePath, maxBytes: Int): NasRead = inner.read(path, maxBytes)

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult {
    onWrite(path)
    return inner.publishExclusive(path, bytes)
  }

  override fun toString(): String = "WatchedFs"
}

/** Cio' che un dispositivo usa per pubblicare, replicare e prenotare sopra la sessione [manager] (la sua, con il suo trasporto che colpisce). */
internal fun sessionFsKit(
  manager: NasSessionManager,
  shareName: String,
  deviceId: String,
  commonShare: () -> String?,
  identity: FacesSyncIdentity,
  switch: FacesSwitch,
  clock: FacesClock,
  gate: FacesWriteGate,
  written: MutableList<Pair<String, SharePath>>,
  attempts: MutableList<Pair<String, SharePath>>,
  onWrite: (SharePath) -> Unit,
  flags: FeatureFlags = FencedFlags.of(),
): ChaosFsKit {
  val fs = RecordingShare(WatchedFs(manager.fileSystem(shareName), onWrite), deviceId, written, attempts)
  val remote = NasFacesRemote(fs, deviceId, commonShare, Dispatchers.Unconfined)
  val leases = NasFacesLeases(identity, switch, fs, clock, gate, flags, Dispatchers.Unconfined)
  return ChaosFsKit(remote, leases)
}

/** La porta di lettura di chi guarda (l'osservatore): una sessione sua, senza colpi, mai una scrittura. */
internal fun sessionObserver(manager: NasSessionManager, shareName: String, deviceId: String, commonShare: () -> String?): NasFacesRemote =
  NasFacesRemote(manager.fileSystem(shareName), deviceId, commonShare, Dispatchers.Unconfined)

/**
 * La prova "nell'area comune non si scrive niente": una porta dei volti costruita proprio sull'area comune deve rifiutare ogni chiamata senza mandare
 * nessuna richiesta (L23, E-F42). [requests] dice quante richieste sono arrivate al NAS finora (il simulatore le conta).
 */
internal fun sessionCommonProbe(
  manager: NasSessionManager,
  commonShareName: String,
  deviceId: String,
  requests: () -> Int,
  probe: suspend (NasFacesRemote) -> Boolean,
): Pair<Boolean, Int> {
  val before = requests()
  val remote = NasFacesRemote(manager.fileSystem(commonShareName), deviceId, { commonShareName }, Dispatchers.Unconfined)
  val refused = kotlinx.coroutines.runBlocking { probe(remote) }
  return refused to (requests() - before)
}
