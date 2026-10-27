package dev.pampa.mycloudgallery.graph

import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.UserManager
import dev.pampa.mycloudgallery.core.family.onboarding.AndroidScreenLockSource
import dev.pampa.mycloudgallery.core.family.onboarding.DeviceDescription
import dev.pampa.mycloudgallery.core.family.onboarding.DeviceInfoSource
import dev.pampa.mycloudgallery.core.family.onboarding.ScreenLockSource
import dev.pampa.mycloudgallery.core.nas.session.ElapsedRealtimeClock
import dev.pampa.mycloudgallery.core.nas.session.MonotonicClock
import dev.pampa.mycloudgallery.core.nas.smb.SmbTransport
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import dev.pampa.mycloudgallery.core.network.contract.LocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.contract.NasDiscovery
import dev.pampa.mycloudgallery.core.network.contract.ReachabilityProbe
import dev.pampa.mycloudgallery.core.network.discovery.NsdNasDiscovery
import dev.pampa.mycloudgallery.core.network.env.AndroidNetworkObserver
import dev.pampa.mycloudgallery.core.network.env.NetworkLookup
import dev.pampa.mycloudgallery.core.network.env.NetworkObservationSource
import dev.pampa.mycloudgallery.core.network.env.NetworkPathSource
import dev.pampa.mycloudgallery.core.network.gate.AndroidBindingFactory
import dev.pampa.mycloudgallery.core.network.gate.BindingFactory
import dev.pampa.mycloudgallery.core.network.permission.AndroidLocalNetworkPermission
import dev.pampa.mycloudgallery.core.network.probe.TcpReachabilityProbe
import dev.pampa.mycloudgallery.core.network.vpn.AndroidWireGuardPlatform
import dev.pampa.mycloudgallery.core.network.vpn.WireGuardPlatform
import dev.pampa.mycloudgallery.core.network.wol.AndroidMagicPacketSender
import dev.pampa.mycloudgallery.core.network.wol.MagicPacketSender
import dev.pampa.mycloudgallery.core.rules.DeviceStateSource
import dev.pampa.mycloudgallery.core.rules.platform.AndroidDeviceStateSource
import dev.pampa.mycloudgallery.core.rules.platform.health.AndroidBackgroundHealthReader
import dev.pampa.mycloudgallery.core.rules.platform.health.BackgroundHealthReader
import dev.pampa.mycloudgallery.core.security.credentials.CredentialVault
import dev.pampa.mycloudgallery.core.security.credentials.LoadResult
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.security.credentials.SaveResult
import dev.pampa.mycloudgallery.core.trust.crypto.AndroidKeystoreFamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.AndroidKeystoreKeyProvider
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKeyVault
import dev.pampa.mycloudgallery.core.trust.crypto.KeyProvider
import java.io.File

/**
 * Le credenziali del NAS viste dall'app: le quattro operazioni di `CredentialVault` (core-security) che i moduli
 * della Fase 2 usano attraverso le loro porte (`NasCredentialSource`, `CredentialSaver`, `CredentialWiper`).
 * Nel telefono vero e' il Keystore ([VaultCredentialStore]); nei test d'integrazione una mappa in memoria.
 */
interface AppCredentialStore {
  suspend fun save(slot: String, credentials: NasCredentials): SaveResult

  suspend fun load(slot: String): LoadResult

  suspend fun remove(slot: String)

  /** Vero se la chiave e i dati sono spariti (fase 3 dell'azzeramento). */
  suspend fun wipeAll(): Boolean
}

class VaultCredentialStore(private val vault: CredentialVault) : AppCredentialStore {
  override suspend fun save(slot: String, credentials: NasCredentials): SaveResult = vault.save(slot, credentials)

  override suspend fun load(slot: String): LoadResult = vault.load(slot)

  override suspend fun remove(slot: String) = vault.remove(slot)

  override suspend fun wipeAll(): Boolean = vault.wipeAll().let { it.keyDestroyed && it.dataCleared }
}

/**
 * Le parti del grafo che toccano il telefono: Keystore, rete di Android, WireGuard, SMB vero, sensori. Tutto il
 * resto di [AppGraph] e' logica dei moduli `core-*` e si costruisce uguale in produzione e nei test.
 *
 * I test d'integrazione (Robolectric) ne danno una versione con il simulatore SMB di `core-nas`
 * (`InMemorySmbBackend`), una rete finta (`FakeNetworkObservationSource`) e chiavi in software: cosi' il grafo che si
 * prova e' **lo stesso** di produzione, e cambia solo cio' che senza un telefono non esiste.
 */
interface AppPlatform {
  /** Le reti di Android (callback di `ConnectivityManager`), letta da `LiveNetworkEnvironment`. */
  val networkObservation: NetworkObservationSource

  /** I socket legati a una rete precisa (D1). */
  val bindings: BindingFactory

  val localNetworkPermission: LocalNetworkPermission
  val smbTransport: SmbTransport
  val reachabilityProbe: ReachabilityProbe
  val wireGuard: WireGuardPlatform
  val deviceKeys: KeyProvider
  val familyKeys: FamilyKeyVault
  val credentials: AppCredentialStore

  /**
   * Fase 8 (P13, D14): le chiavi dei volti, una del Keystore per generazione. Nel telefono vero `FacesKeyStore` (StrongBox se c'e'); di serie nessuna
   * (i montaggi senza persone e i test che non le provano).
   */
  val facesKeys: dev.pampa.mycloudgallery.core.security.faces.FacesKeys
    get() = dev.pampa.mycloudgallery.core.security.noop.NoFacesKeys

  val healthReader: BackgroundHealthReader
  val deviceState: DeviceStateSource
  val screenLock: ScreenLockSource
  val deviceInfo: DeviceInfoSource
  val monotonicClock: MonotonicClock

  /** L'ora del dispositivo (ms): solo per misurare e datare in locale, mai per decidere sulla fiducia. */
  val deviceNow: () -> Long

  /** La `Network` di Android di un id visto dall'osservatore (R12: lo scaricamento dei modelli esce dalla rete approvata). Di serie nessuna. */
  val networkLookup: NetworkLookup
    get() = object : NetworkLookup {
      override fun network(id: String): android.net.Network? = null
    }

  fun discovery(paths: NetworkPathSource): NasDiscovery

  fun magicPacketSender(paths: NetworkPathSource): MagicPacketSender

  /** Il profilo Android e' quello in primo piano? (tablet con piu' utenti, E-P19) Null se non si sa. */
  fun isUserForeground(): Boolean?
}

/** Il telefono vero. Un'istanza per processo (lo tiene [AppGraph], singleton di Hilt). */
class AndroidAppPlatform(context: Context) : AppPlatform {

  private val app: Context = context.applicationContext ?: context
  private val observer = AndroidNetworkObserver(app)
  private val networks: NetworkLookup = observer

  override val networkObservation: NetworkObservationSource = observer
  override val networkLookup: NetworkLookup = networks
  override val bindings: BindingFactory = AndroidBindingFactory(networks)
  override val localNetworkPermission: LocalNetworkPermission = AndroidLocalNetworkPermission(app)
  override val smbTransport: SmbTransport = SmbjTransport()
  override val reachabilityProbe: ReachabilityProbe = TcpReachabilityProbe()
  override val wireGuard: WireGuardPlatform = AndroidWireGuardPlatform(app)
  override val deviceKeys: KeyProvider = AndroidKeystoreKeyProvider()
  override val familyKeys: FamilyKeyVault = AndroidKeystoreFamilyKeyVault(
    file = File(app.noBackupFilesDir, FAMILY_KEY_FILE),
    isDeviceSecure = { app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true },
  )
  override val credentials: AppCredentialStore = VaultCredentialStore(CredentialVault.getInstance(app))

  /** Le chiavi dei volti (Fase 8): il ripiego su una chiave software solo nelle build di debug sugli emulatori senza Keystore hardware. */
  override val facesKeys: dev.pampa.mycloudgallery.core.security.faces.FacesKeys by lazy {
    dev.pampa.mycloudgallery.core.security.faces.FacesKeyStore.forContext(app, allowSoftwareKey = dev.pampa.mycloudgallery.BuildConfig.DEBUG)
  }
  override val healthReader: BackgroundHealthReader = AndroidBackgroundHealthReader(app)
  override val deviceState: DeviceStateSource = AndroidDeviceStateSource(app)
  override val screenLock: ScreenLockSource = AndroidScreenLockSource(app)
  override val deviceInfo: DeviceInfoSource = AndroidDeviceInfo(app)
  override val monotonicClock: MonotonicClock = ElapsedRealtimeClock
  override val deviceNow: () -> Long = System::currentTimeMillis

  override fun discovery(paths: NetworkPathSource): NasDiscovery = NsdNasDiscovery(app, paths, networks)

  override fun magicPacketSender(paths: NetworkPathSource): MagicPacketSender = AndroidMagicPacketSender(networks, paths)

  override fun isUserForeground(): Boolean? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // T-20: per il proprio utente non serve nessun permesso; se un produttore lo nega, non si sa.
      try {
        app.getSystemService(UserManager::class.java)?.isUserForeground
      } catch (e: SecurityException) {
        null
      }
    } else {
      null
    }

  private companion object {
    /** La cassaforte della chiave di famiglia (cifrata con il Keystore), solo sul telefono dell'admin. */
    const val FAMILY_KEY_FILE = "family-key.vault"
  }
}

/**
 * Come si presenta il dispositivo nel registro: il modello e un nome proposto ("Telefono di mario", "Tablet - profilo
 * mario" sui profili secondari, §2.7). Il nome lo puo' cambiare l'utente dove gli si chiede.
 */
class AndroidDeviceInfo(private val context: Context) : DeviceInfoSource {

  override fun describe(nasUser: String): DeviceDescription {
    val tablet = context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_DP ||
      (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
    val systemUser = context.getSystemService(UserManager::class.java)?.isSystemUser != false
    val kind = if (tablet) "Tablet" else "Telefono"
    val name = if (tablet && !systemUser) "$kind — profilo $nasUser" else "$kind di $nasUser"
    // Testi da mostrare (TextRules.displayText): mai vuoti, mai con spazi ai bordi.
    val model = Build.MODEL.orEmpty().take(MAX_MODEL_CHARS).trim().ifEmpty { "Android" }
    return DeviceDescription(model = model, defaultName = name.take(MAX_NAME_CHARS).trim())
  }

  private companion object {
    const val TABLET_MIN_DP = 600
    const val MAX_MODEL_CHARS = 64
    const val MAX_NAME_CHARS = 64
  }
}
