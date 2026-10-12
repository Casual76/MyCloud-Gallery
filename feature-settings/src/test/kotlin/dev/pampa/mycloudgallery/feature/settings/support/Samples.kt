package dev.pampa.mycloudgallery.feature.settings.support

import dev.pampa.mycloudgallery.core.family.contract.DeviceRoleView
import dev.pampa.mycloudgallery.core.family.contract.DeviceView
import dev.pampa.mycloudgallery.core.family.contract.PendingRequestView
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.crypto.EcPublicKey
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.feature.settings.port.SettingsClock
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * I dati finti dei test e degli screenshot: una famiglia plausibile (un admin, un tablet con due profili, un
 * telefono perso, uno dismesso). Nomi e account inventati; nessun IP o nome vero della famiglia.
 */
internal object Samples {

  /** "Adesso", ora del NAS. Il [clock] restituisce sempre questo istante. */
  const val NOW: Long = 1_800_000_000_000L

  val clock = SettingsClock { NOW }

  val nasAddress = NasAddress("192.168.178.58", "wdmycloud")

  fun account(guid: String? = "guid-del-nas") = NasAccount(nasAddress, "test2", "nas-principale", guid)

  const val ADMIN_PHONE = "id-telefono-admin"
  const val TABLET_MAMMA = "id-tablet-mamma"
  const val PHONE_ANNA = "id-telefono-anna"
  const val OLD_PHONE = "id-vecchio-telefono"
  const val LOST_PHONE = "id-telefono-perso"
  const val REVOKED_TABLET = "id-tablet-revocato"
  const val REINSTALLED = "id-telefono-reinstallato"

  fun adminPhone(thisDevice: Boolean = true) = DeviceView(
    id = ADMIN_PHONE,
    name = "Telefono di Alessio",
    model = "SM-S931B",
    nasUser = "alessio",
    admin = true,
    status = DeviceStatus.APPROVED,
    roles = setOf(DeviceRoleView.INDEXER, DeviceRoleView.PREPARER),
    lastSeenAt = NOW - 2.minutes.inWholeMilliseconds,
    appVersion = "0.2.0",
    isThisDevice = thisDevice,
  )

  fun tabletMamma(thisDevice: Boolean = false) = DeviceView(
    id = TABLET_MAMMA,
    name = "Tablet — profilo Mamma",
    model = "SM-X710",
    nasUser = "mamma",
    admin = false,
    status = DeviceStatus.APPROVED,
    roles = emptySet(),
    lastSeenAt = NOW - 3.hours.inWholeMilliseconds,
    appVersion = "0.2.0",
    isThisDevice = thisDevice,
  )

  fun phoneAnna(thisDevice: Boolean = false) = DeviceView(
    id = PHONE_ANNA,
    name = "Telefono di Anna",
    model = "SM-A556B",
    nasUser = "anna",
    admin = false,
    status = DeviceStatus.APPROVED,
    roles = setOf(DeviceRoleView.INDEXER),
    lastSeenAt = NOW - 2.days.inWholeMilliseconds,
    appVersion = "0.1.9",
    isThisDevice = thisDevice,
  )

  val oldPhone = DeviceView(
    id = OLD_PHONE,
    name = "Vecchio Galaxy S21",
    model = "SM-G991B",
    nasUser = "alessio",
    admin = false,
    status = DeviceStatus.RETIRED,
    roles = emptySet(),
    lastSeenAt = NOW - 40.days.inWholeMilliseconds,
    appVersion = "0.1.0",
    isThisDevice = false,
  )

  val lostPhone = DeviceView(
    id = LOST_PHONE,
    name = "Telefono di Marco",
    model = "SM-A346B",
    nasUser = "marco",
    admin = false,
    status = DeviceStatus.LOST,
    roles = emptySet(),
    lastSeenAt = NOW - 6.days.inWholeMilliseconds,
    appVersion = "0.2.0",
    isThisDevice = false,
  )

  val revokedTablet = DeviceView(
    id = REVOKED_TABLET,
    name = "Tablet di prova",
    model = "SM-X110",
    nasUser = "prova",
    admin = false,
    status = DeviceStatus.REVOKED,
    roles = emptySet(),
    lastSeenAt = null,
    appVersion = null,
    isThisDevice = false,
  )

  /** Lo stesso nome dell'admin, ma un id nuovo: sembra la reinstallazione di [adminPhone]. */
  val reinstalled = DeviceView(
    id = REINSTALLED,
    name = "Telefono di Alessio",
    model = "SM-S931B",
    nasUser = "alessio",
    admin = false,
    status = DeviceStatus.APPROVED,
    roles = emptySet(),
    lastSeenAt = NOW - 1.hours.inWholeMilliseconds,
    appVersion = "0.2.0",
    isThisDevice = false,
    lookAlikeOf = ADMIN_PHONE,
  )

  /** Una famiglia completa, vista dall'admin. */
  fun familyAsAdmin(): List<DeviceView> =
    listOf(adminPhone(), tabletMamma(), phoneAnna(), oldPhone, lostPhone, revokedTablet)

  /** La stessa famiglia vista dal telefono di Anna (non admin). */
  fun familyAsMember(): List<DeviceView> =
    listOf(adminPhone(thisDevice = false), tabletMamma(), phoneAnna(thisDevice = true), oldPhone, lostPhone)

  const val REQUEST_ID = "id-richiesta-nonna"
  const val EXPIRED_REQUEST_ID = "id-richiesta-scaduta"

  val request = PendingRequestView(
    id = REQUEST_ID,
    name = "Tablet — profilo Nonna",
    model = "SM-X110",
    declaredNasUser = "nonna",
    requestedAt = NOW - 3.minutes.inWholeMilliseconds,
    firstSeenAt = NOW - 3.minutes.inWholeMilliseconds,
    windowOpen = true,
  )

  val expiredRequest = PendingRequestView(
    id = EXPIRED_REQUEST_ID,
    name = "Telefono di Giulia",
    model = "SM-A156B",
    declaredNasUser = "giulia",
    requestedAt = NOW - 25.minutes.inWholeMilliseconds,
    firstSeenAt = NOW - 25.minutes.inWholeMilliseconds,
    windowOpen = false,
  )

  val lookAlikeRequest = PendingRequestView(
    id = "id-richiesta-sosia",
    name = "Telefono di Alessio",
    model = "SM-S931B",
    declaredNasUser = "alessio",
    requestedAt = NOW - 1.minutes.inWholeMilliseconds,
    firstSeenAt = NOW - 1.minutes.inWholeMilliseconds,
    windowOpen = true,
    lookAlikeOf = ADMIN_PHONE,
  )

  /** Una chiave pubblica P-256 vera (serve a `EcPublicKey.fromSpki`, che controlla il punto sulla curva). */
  fun publicKey(): EcPublicKey {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    return EcPublicKey.fromJca(generator.generateKeyPair().public)
  }
}
