package dev.pampa.mycloudgallery.core.family.registry

import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.family.trust.UNEXPECTED
import dev.pampa.mycloudgallery.core.family.trust.onShare
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.data.DeviceStatusReport
import dev.pampa.mycloudgallery.core.trust.data.DeviceStatusSeries
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RejectionTally
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lo stato dei dispositivi (tipo 8, `docs/formato-nas.md` §5.10): legge l'ultimo stato valido di ogni
 * dispositivo **approvato** (con anti-rollback: i segni stanno nello stato fidato, chiave `status/<id>`) e
 * pubblica quello di questo dispositivo **al massimo una volta al giorno**, approfittando di una sessione
 * gia' aperta. Solo informazione per l'elenco "Dispositivi" (L11): nessuna decisione dipende da qui, salvo
 * l'avviso "registro rimasto indietro" quando un altro dispositivo dichiara di conoscerne uno piu' nuovo.
 */
class DeviceStatusBook(
  private val engine: TrustEngine,
  private val sessions: NasSessionManager,
  private val keys: KeyProvider,
) {
  private val mutex = Mutex()
  private val _latest = MutableStateFlow<Map<String, DeviceStatusReport>>(emptyMap())
  private val _rejected = MutableStateFlow(0)

  /** L'ultimo stato accettato per dispositivo. */
  val latest: StateFlow<Map<String, DeviceStatusReport>> = _latest.asStateFlow()

  /** Quanti stati sono stati scartati nell'ultima lettura (Diagnostica). */
  val rejected: StateFlow<Int> = _rejected.asStateFlow()

  /** Rilegge lo stato dei dispositivi approvati (una ricerca per nome a dispositivo). */
  suspend fun refresh(purpose: Purpose) = mutex.withLock {
    val state = engine.state ?: return@withLock
    val share = engine.profile()?.commonShare ?: state.registry.config.commonShare
    val ids = state.registry.devices.filter { it.status == DeviceStatus.APPROVED }.map { it.id }
    val now = engine.nasNow()
    val read = safely {
      sessions.onShare(share, purpose) { fs ->
        val tally = RejectionTally()
        val found = HashMap<String, Pair<DeviceStatusReport, HeadMark>>()
        var behind = false
        for (id in ids) {
          val scan = DeviceStatusSeries.readLatest(fs, state, id, engine.mark(markKey(id)), now)
          scan.rejected.asMap().forEach { (reason, count) -> repeat(count) { tally.add(reason) } }
          val accepted = scan.latest ?: continue
          found[id] = accepted.report to accepted.mark
          behind = behind || accepted.registryBehind
        }
        Triple(found, tally.total, behind)
      }
    }
    if (read !is NasResult.Ok) return@withLock
    val (found, rejectedCount, behind) = read.value
    engine.updateMarks(found.mapKeys { markKey(it.key) }.mapValues { it.value.second })
    _latest.value = _latest.value.filterKeys { it in ids } + found.mapValues { it.value.first }
    _rejected.value = rejectedCount
    engine.noteRegistryBehind(behind)
    engine.refreshStatus()
  }

  /**
   * Pubblica lo stato di questo dispositivo se e' approvato e se l'ultimo pubblicato ha piu' di un giorno (ora
   * del NAS) o dice cose diverse (versione dell'app, ruoli). Vero se ha pubblicato.
   */
  suspend fun publishIfDue(purpose: Purpose): Boolean = mutex.withLock {
    val state = engine.state ?: return@withLock false
    // R7: con il Keystore che non risponde non si pubblica niente (e l'eccezione non esce dalla rilettura del registro).
    val me = try {
      engine.myDeviceId()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } ?: return@withLock false
    if (state.statusOf(me) != DeviceStatus.APPROVED) return@withLock false
    val profile = engine.profile() ?: return@withLock false
    val now = engine.nasNow()
    val share = profile.commonShare
    var mine = _latest.value[me]
    if (mine == null && engine.mark(markKey(me)) != null) {
      // Processo nuovo: l'ultimo stato proprio si rilegge (una ricerca per nome) prima di decidere.
      val scan = safely { sessions.onShare(share, purpose) { fs -> DeviceStatusSeries.readLatest(fs, state, me, engine.mark(markKey(me)), now) } }
      mine = (scan as? NasResult.Ok)?.value?.latest?.report
    }
    val due = mine == null ||
      now - mine.lastSeenAt >= TrustLimits.DEVICE_STATUS_INTERVAL_MILLIS ||
      mine.appVersion != profile.appVersion ||
      mine.roles != profile.roles
    if (!due) return@withLock false
    val signer = keys.deviceSigner()
    val after = engine.mark(markKey(me))?.version ?: 0L
    var published: DeviceStatusReport? = null
    val result = safely {
      sessions.onShare(share, purpose) { fs ->
        DeviceStatusSeries.publish(fs, me, after) { version ->
          DeviceStatusReport(state.registry.familyId, me, version, profile.appVersion, profile.roles, now, state.registry.version)
            .also { published = it }
            .sign(signer)
        }
      }
    }
    val version = (result as? NasResult.Ok)?.value ?: return@withLock false
    val report = published?.takeIf { it.version == version } ?: return@withLock false
    // Il segno si prende rileggendo: e' l'impronta del contenuto firmato, come per chiunque altro.
    val reread = safely { sessions.onShare(share, purpose) { fs -> DeviceStatusSeries.readLatest(fs, state, me, engine.mark(markKey(me)), now) } }
    (reread as? NasResult.Ok)?.value?.latest?.let { engine.updateMarks(mapOf(markKey(me) to it.mark)) }
    _latest.value = _latest.value + (me to report)
    true
  }

  private suspend fun <T> safely(block: suspend () -> NasResult<T>): NasResult<T> = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    NasResult.Err(UNEXPECTED)
  }

  companion object {
    /** La chiave del segno anti-rollback dello stato di [deviceId] in `TrustSnapshot.ownHeads`. */
    fun markKey(deviceId: String): String = "status/$deviceId"
  }
}
