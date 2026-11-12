package dev.pampa.mycloudgallery.core.faces.sync.chaos

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags
import dev.pampa.mycloudgallery.core.faces.FencedFlags
import dev.pampa.mycloudgallery.core.faces.chaos.ChaosInjector
import dev.pampa.mycloudgallery.core.faces.chaos.Surface
import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.FacesWriteGate
import dev.pampa.mycloudgallery.core.faces.sync.FacesSyncIdentity
import dev.pampa.mycloudgallery.core.faces.sync.MemoryShare
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesLeases
import dev.pampa.mycloudgallery.core.faces.sync.NasFacesRemote
import dev.pampa.mycloudgallery.core.faces.sync.RecordingShare
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasListing
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.IOException
import kotlinx.coroutines.Dispatchers

// Qui sta `NasFileSystem` (Phase8ArchitectureTest: in core-faces solo sotto faces/sync/, test compresi): il NAS FINTO del banco delle interruzioni, la
// share in memoria di P3 con i colpi di P12 su ogni richiesta, e i pezzi che ne hanno bisogno per costruirsi (la porta del NAS dei volti e le
// prenotazioni). Il resto del banco (`faces/chaos/`) non nomina mai il tipo: riceve da qui `NasFacesRemote` e `NasFacesLeases` gia' pronti.

/**
 * La share del NAS finto vista da un dispositivo: ogni richiesta e' una chiamata che l'iniettore conta e puo' colpire (rete caduta, sessione scaduta,
 * stallo, spazio finito, morte del processo, prima o dopo); un dispositivo spento non raggiunge niente. Lo spazio finito scatta solo dove si scrive.
 */
internal class ChaosFs(
  private val inner: MemoryShare,
  private val inj: ChaosInjector,
  private val device: Int,
  private val online: () -> Boolean,
  private val onWrite: (SharePath) -> Unit = {},
) : NasFileSystem {
  override val shareName: String get() = inner.shareName

  override fun list(dir: SharePath, limit: Int): NasListing = call(false) { inner.list(dir, limit) }

  override fun read(path: SharePath, maxBytes: Int): NasRead = call(false) { inner.read(path, maxBytes) }

  override fun publishExclusive(path: SharePath, bytes: ByteArray): PublishResult = call(true) {
    onWrite(path)
    inner.publishExclusive(path, bytes)
  }

  private inline fun <T> call(allocates: Boolean, block: () -> T): T {
    inj.checkAlive(device)
    if (!online()) throw IOException("NAS non raggiungibile")
    return inj.around(device, Surface.NAS, allocates) { block() }
  }

  override fun toString(): String = "ChaosFs"
}

/** Cio' che il NAS finto da' a un dispositivo per pubblicare, replicare e prenotare. */
internal class ChaosFsKit(val remote: NasFacesRemote, val leases: NasFacesLeases)

/**
 * Costruisce la porta del NAS dei volti e le prenotazioni di un dispositivo sopra la share finta [privateShare], con le scritture registrate in [written] (le riuscite)
 * e [attempts] (tutti i tentativi, anche quelli finiti con un guasto dopo l'effetto).
 */
internal fun chaosFsKit(
  privateShare: MemoryShare,
  inj: ChaosInjector,
  index: Int,
  deviceId: String,
  online: () -> Boolean,
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
  val fs = RecordingShare(ChaosFs(privateShare, inj, index, online, onWrite), deviceId, written, attempts)
  val remote = NasFacesRemote(fs, deviceId, commonShare, Dispatchers.Unconfined)
  val leases = NasFacesLeases(identity, switch, fs, clock, gate, flags, Dispatchers.Unconfined)
  return ChaosFsKit(remote, leases)
}

/**
 * La prova "nell'area comune non si scrive niente": una porta dei volti costruita proprio sull'area comune deve rifiutare ogni chiamata senza mandare
 * nessuna richiesta (L23, E-F42). Restituisce le richieste arrivate alla share comune finta.
 */
internal fun commonAreaProbe(common: MemoryShare, deviceId: String, commonName: String, probe: suspend (NasFacesRemote) -> Boolean): Pair<Boolean, Int> {
  val before = common.writes.size + common.reads.size
  val remote = NasFacesRemote(common, deviceId, { commonName }, Dispatchers.Unconfined)
  val refused = kotlinx.coroutines.runBlocking { probe(remote) }
  return refused to (common.writes.size + common.reads.size - before)
}
