package dev.pampa.mycloudgallery.feature.settings.devices

import dev.pampa.mycloudgallery.core.family.contract.DeviceRoleView
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import dev.pampa.mycloudgallery.core.family.contract.TrustStatus
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.ui.Feedback

/**
 * Un dispositivo della famiglia come lo mostra la lista (L11). Il tempo e' gia' una durata ("visto 2 ore fa"):
 * il registro porta l'ora del NAS, e il ViewModel la confronta con l'ora del NAS di adesso, non con quella del
 * telefono (E-P9: l'orologio del telefono puo' essere sbagliato).
 *
 * @property lookAlikeOfName il nome di un altro dispositivo che "sembra uguale" (reinstallazione, E-O7).
 */
internal data class DeviceUi(
  val id: String,
  val name: String,
  val model: String,
  val nasUser: String,
  val admin: Boolean,
  val status: DeviceStatus,
  val roles: Set<DeviceRoleView>,
  val lastSeenAgoMs: Long?,
  val appVersion: String?,
  val isThisDevice: Boolean,
  val lookAlikeOfName: String?,
) {
  val isApproved: Boolean get() = status == DeviceStatus.APPROVED

  /** Un tablet (modelli Samsung `SM-X...`, o "Tab" nel nome): cambia solo l'icona. */
  val isTablet: Boolean
    get() = model.startsWith("SM-X", ignoreCase = true) || model.contains("tab", ignoreCase = true)
}

/** Una richiesta d'ingresso in attesa, per l'admin (§2.2). [ageMs] parte da quando l'app l'ha vista. */
internal data class RequestUi(
  val id: String,
  val name: String,
  val model: String,
  val declaredNasUser: String,
  val ageMs: Long,
  val windowOpen: Boolean,
  val lookAlikeOfName: String?,
)

/** La traduzione fra le viste del registro e quelle delle pagine. Pura: la provano i test. */
internal object DeviceMapper {

  fun devices(views: List<DeviceView>, nasNowMs: Long): List<DeviceUi> {
    val names = views.associate { it.id to it.name }
    return views.map { view ->
      DeviceUi(
        id = view.id,
        name = view.name,
        model = view.model,
        nasUser = view.nasUser,
        admin = view.admin,
        status = view.status,
        roles = view.roles,
        lastSeenAgoMs = view.lastSeenAt?.let { (nasNowMs - it).coerceAtLeast(0L) },
        appVersion = view.appVersion,
        isThisDevice = view.isThisDevice,
        lookAlikeOfName = view.lookAlikeOf?.let(names::get),
      )
    }
  }

  fun requests(views: List<PendingRequestView>, devices: List<DeviceView>, nasNowMs: Long): List<RequestUi> {
    val names = devices.associate { it.id to it.name }
    return views.map { view ->
      RequestUi(
        id = view.id,
        name = view.name,
        model = view.model,
        declaredNasUser = view.declaredNasUser,
        ageMs = (nasNowMs - view.firstSeenAt).coerceAtLeast(0L),
        windowOpen = view.windowOpen,
        lookAlikeOfName = view.lookAlikeOf?.let(names::get),
      )
    }
  }

  /**
   * L'elenco dei dispositivi diviso nei gruppi della pagina: questo dispositivo, gli altri che lavorano (admin
   * per primi, poi per nome) e quelli che non lavorano piu'. Lo usano il ViewModel e gli screenshot.
   */
  fun list(trust: TrustStatus, devices: List<DeviceView>, requests: List<PendingRequestView>, nasNowMs: Long): DevicesUiState {
    val mapped = devices(devices, nasNowMs)
    val thisDevice = mapped.firstOrNull { it.isThisDevice }
    val rest = mapped.filterNot { it.isThisDevice }
    return DevicesUiState(
      trust = trust,
      requests = requests(requests, devices, nasNowMs),
      thisDevice = thisDevice,
      others = rest.filter { it.isApproved }.sortedWith(compareByDescending<DeviceUi> { it.admin }.thenBy { it.name.lowercase() }),
      inactive = rest.filterNot { it.isApproved }.sortedBy { it.name.lowercase() },
    )
  }

  /** L'ora del NAS adesso: quella del telefono corretta dallo scarto letto alla NEGOTIATE (zero senza sessione). */
  fun nasNow(deviceNowMs: Long, connection: NasConnState): Long =
    deviceNowMs + ((connection as? NasConnState.Ready)?.info?.nasTimeOffsetMs ?: 0L)
}

/** Questo dispositivo tiene la chiave di famiglia? */
internal fun TrustStatus.isAdmin(): Boolean = this is TrustStatus.Approved && admin

/** Lo stato della lista dei dispositivi. */
internal data class DevicesUiState(
  val trust: TrustStatus,
  val requests: List<RequestUi>,
  val thisDevice: DeviceUi?,
  val others: List<DeviceUi>,
  val inactive: List<DeviceUi>,
  val refreshing: Boolean = false,
  val feedback: Feedback? = null,
) {
  val isAdmin: Boolean get() = trust.isAdmin()

  val totalCount: Int get() = (if (thisDevice != null) 1 else 0) + others.size + inactive.size

  /** I dispositivi che lavorano: questo (se approvato) e gli altri approvati. */
  val activeCount: Int get() = (if (thisDevice?.isApproved == true) 1 else 0) + others.size

  companion object {
    val EMPTY = DevicesUiState(
      trust = TrustStatus.NotConfigured,
      requests = emptyList(),
      thisDevice = null,
      others = emptyList(),
      inactive = emptyList(),
    )
  }
}
