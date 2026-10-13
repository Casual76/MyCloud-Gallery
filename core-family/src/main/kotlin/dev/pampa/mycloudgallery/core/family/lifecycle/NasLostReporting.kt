package dev.pampa.mycloudgallery.core.family.lifecycle

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.AdminActionResult
import dev.pampa.mycloudgallery.core.family.contract.LostReporting
import dev.pampa.mycloudgallery.core.family.registry.RegistryWriter
import dev.pampa.mycloudgallery.core.family.trust.TrustEngine
import dev.pampa.mycloudgallery.core.family.trust.UNEXPECTED
import dev.pampa.mycloudgallery.core.family.trust.onShare
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.LostReport
import dev.pampa.mycloudgallery.core.trust.registry.LostReportScan
import dev.pampa.mycloudgallery.core.trust.registry.LostReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * La decisione 3(a) dal lato del dispositivo perso: cerca una segnalazione per **se'** nell'area privata del
 * proprio account (`family/reports/<io>/…`). Solo li': chi ci scrive ha la password di quell'account (prova di
 * possesso, revisione B6). La copia nell'area comune non conta per l'azzeramento automatico (la converte
 * l'admin). Una segnalazione non valida (firma, firmatario non approvato, altro account, altro posto) e'
 * spazzatura contata in [lastScan], mai un motivo per azzerare.
 */
class LostReportWatcher(
  private val engine: TrustEngine,
  private val sessions: NasSessionManager,
) {
  private val _lastScan = MutableStateFlow<LostReportScan?>(null)

  /** L'ultima lettura (Diagnostica: segnalazioni scartate nell'area privata). */
  val lastScan: StateFlow<LostReportScan?> = _lastScan.asStateFlow()

  /** Vero solo per una segnalazione **valida** per questo dispositivo nella propria area privata. */
  suspend fun foundForMe(purpose: Purpose): Boolean {
    val state = engine.state ?: return false
    val me = engine.myDeviceId() ?: return false
    if (state.registry.device(me) == null) return false // in attesa: nessuno puo' ancora segnalarlo
    val profile = engine.profile() ?: return false
    val privateShare = profile.privateShare ?: return false
    // Revisione 2, F8: un'area "privata" che e' l'area comune non prova niente (ci scrive chiunque abbia un account):
    // senza un'area privata vera, niente azzeramento automatico da segnalazione.
    if (privateShare.equals(profile.commonShare, ignoreCase = true)) return false
    val now = engine.nasNow()
    val result = try {
      sessions.onShare(privateShare, purpose) { fs -> LostReports.find(fs, state, me, now) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      NasResult.Err(UNEXPECTED)
    }
    val scan = (result as? NasResult.Ok)?.value ?: return false
    _lastScan.value = scan
    return scan.found != null
  }
}

/**
 * La segnalazione di smarrimento (§2.3, decisione 3(a), C15).
 *
 * - [reportOwnDeviceLost]: da un dispositivo **approvato** dello stesso account del NAS del dispositivo perso,
 *   firma un `LostReport` e lo pubblica nell'area comune (per l'admin) e nell'area privata dell'account (per
 *   l'azzeramento automatico del dispositivo perso). Poi la guida, in quest'ordine: revoca del peer WireGuard,
 *   cambio della password del NAS, Samsung Find (le schermate sono di P7).
 * - [convertReports] (admin): le segnalazioni **valide** dell'area comune diventano lo stato `lost` del
 *   registro, una versione per dispositivo. E' un gesto dell'admin (E-S11), mai automatico.
 */
class NasLostReporting(
  private val engine: TrustEngine,
  private val sessions: NasSessionManager,
  private val keys: KeyProvider,
  private val writer: RegistryWriter,
) : LostReporting {

  override suspend fun reportOwnDeviceLost(targetDeviceId: String): AdminActionResult {
    if (!engine.ensureLoaded()) return AdminActionResult.NotAdmin
    val state = engine.state ?: return AdminActionResult.NotAdmin
    val me = engine.myDeviceId() ?: return AdminActionResult.NotAdmin
    val mine = state.registry.device(me)
    val target = state.registry.device(targetDeviceId)
    if (mine?.status != DeviceStatus.APPROVED || target == null || targetDeviceId == me) return AdminActionResult.NotAdmin
    if (!TextRules.sameAccount(mine.nasUser, target.nasUser)) return AdminActionResult.NotAdmin
    if (target.status == DeviceStatus.LOST) return AdminActionResult.Done
    val profile = engine.profile() ?: return AdminActionResult.NotAdmin

    val signed = try {
      LostReport(state.registry.familyId, me, targetDeviceId, mine.nasUser, engine.nasNow()).sign(keys.deviceSigner())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return AdminActionResult.Failed(UNEXPECTED)
    }
    val shares = listOfNotNull(profile.commonShare, profile.privateShare).distinctBy { it.lowercase() }
    for (share in shares) {
      when (val published = sessions.onShare(share, Purpose.INTERACTIVE) { fs -> LostReports.publish(fs, targetDeviceId, signed) }) {
        is NasResult.Err -> return AdminActionResult.Failed(published.error)
        is NasResult.Ok -> if (published.value == null) return AdminActionResult.Blocked
      }
    }
    if (profile.privateShare == null) GalleryLog.w(TAG) { "segnalazione senza area privata: vale solo per l'admin" }
    return AdminActionResult.Done
  }

  override suspend fun convertReports(): AdminActionResult {
    val admin = writer.adminContext() ?: return AdminActionResult.NotAdmin
    val state = admin.state
    val candidates = state.registry.devices.filter { it.status != DeviceStatus.LOST && it.id != admin.me }
    val now = engine.nasNow()
    val share = engine.profile()?.commonShare ?: state.registry.config.commonShare
    val valid = ArrayList<String>()
    for (device in candidates) {
      when (val scan = sessions.onShare(share, Purpose.INTERACTIVE) { fs -> LostReports.find(fs, state, device.id, now) }) {
        is NasResult.Err -> return AdminActionResult.Failed(scan.error)
        is NasResult.Ok -> if (scan.value.found != null) valid += device.id
      }
    }
    var result: AdminActionResult = AdminActionResult.Done
    for (id in valid) {
      result = writer.changeStatus(id, DeviceStatus.LOST)
      if (result != AdminActionResult.Done) return result
    }
    return result
  }

  private companion object {
    const val TAG = "LostReporting"
  }
}
