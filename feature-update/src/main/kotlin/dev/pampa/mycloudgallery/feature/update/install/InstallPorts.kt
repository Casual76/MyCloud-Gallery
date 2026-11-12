package dev.pampa.mycloudgallery.feature.update.install

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.update.AndroidAppUpdateInstaller
import dev.pampa.mycloudgallery.feature.update.policy.PackageFacts
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

// LE PORTE DELL'INSTALLAZIONE (pacchetto U, 12/11/2026). Cio' che serve a Android sta dietro un'interfaccia, cosi' le regole e la sequenza si provano
// sul computer con dei finti (GuardedUpdateInstallerTest); qui sotto le implementazioni vere, sottili.

/** Lo scaricamento e la sessione di installazione dell'engine (`AndroidAppUpdateInstaller.download` e `installFile`). */
interface ApkTransfer {
  suspend fun download(update: AvailableAppUpdate, onProgress: (AppUpdateInstallState.Downloading) -> Unit): File

  fun installFile(file: File, expectedVersionName: String, sha256: String): Flow<AppUpdateInstallState>
}

/** I fatti di un pacchetto: quello installato e un APK su disco (pacchetto, versionCode, impronte dei certificati di firma). */
interface PackageFactsSource {
  fun installed(): PackageFacts

  /** Null se Android non legge [file] come APK. */
  fun archive(file: File): PackageFacts?
}

/** Il permesso «installa app sconosciute» per questa app. */
interface InstallPermission {
  fun granted(): Boolean

  /** Apre la pagina di Android del permesso, per questa app. Vero se si e' aperta. */
  fun open(): Boolean
}

/** Lo SHA-256 esadecimale (minuscolo) di un file. */
fun interface FileChecksum {
  fun sha256(file: File): String
}

/**
 * Il cancello del passaggio ad Android (giro 2, US2-1 e UU2-1): aperto solo mentre l'activity dell'app e' RESUMED. Lo aprono e lo chiudono gli eventi
 * del ciclo di vita dell'activity (`UpdateLifecycleEffect` alla radice: ON_RESUME apre, ON_PAUSE e l'uscita dalla composizione chiudono), non
 * l'importanza del processo: un servizio in primo piano, il backup, non lo apre. [GuardedUpdateInstaller.commit] lo guarda una volta sola, dopo le
 * verifiche e subito prima di `installFile`.
 */
class ForegroundGate {
  @Volatile private var resumed = false

  fun inFront(): Boolean = resumed

  fun open() {
    resumed = true
  }

  fun close() {
    resumed = false
  }
}

/** Lo scaricamento e l'installazione dell'engine 3.0.4 (non modificato): `download` usa l'`EngineHttp` avvolto (`UpdateHttp`). */
class EngineApkTransfer(private val engine: AndroidAppUpdateInstaller) : ApkTransfer {
  override suspend fun download(update: AvailableAppUpdate, onProgress: (AppUpdateInstallState.Downloading) -> Unit): File =
    engine.download(update, onProgress)

  override fun installFile(file: File, expectedVersionName: String, sha256: String): Flow<AppUpdateInstallState> =
    engine.installFile(file = file, expectedVersionName = expectedVersionName, sha256 = sha256)
}

/** I fatti dal PackageManager, con i certificati di firma (`GET_SIGNING_CERTIFICATES`): mai il certificato intero, solo la sua impronta SHA-256. */
class AndroidPackageFacts(context: Context) : PackageFactsSource {
  private val app = context.applicationContext

  override fun installed(): PackageFacts {
    val info = app.packageManager.packageInfo(app.packageName)
    return facts(info)
  }

  override fun archive(file: File): PackageFacts? {
    val info = app.packageManager.archiveInfo(file.absolutePath) ?: return null
    return facts(info)
  }

  private fun facts(info: PackageInfo): PackageFacts = PackageFacts(
    packageName = info.packageName,
    versionCode = info.longVersionCode,
    versionName = info.versionName,
    signers = currentSigners(info).map(::fingerprint).toSet(),
  )

  /**
   * I certificati con cui il pacchetto e' firmato ADESSO: sempre `apkContentsSigners`, con uno o piu' firmatari (US-4 della critica di sicurezza: prima,
   * con un firmatario solo, si prendeva l'ultimo della storia delle rotazioni). Il confronto resta l'uguaglianza esatta degli insiemi: la chiave Pampa
   * e' una, senza rotazioni, e una rotazione futura vorra' una regola sua (docs/sicurezza.md L614).
   */
  private fun currentSigners(info: PackageInfo): List<Signature> = info.signingInfo?.apkContentsSigners?.toList().orEmpty()

  private fun fingerprint(signature: Signature): String =
    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

  @Suppress("DEPRECATION")
  private fun PackageManager.packageInfo(name: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else {
      getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
    }

  @Suppress("DEPRECATION")
  private fun PackageManager.archiveInfo(path: String): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else {
      getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
    }
}

/** Il permesso vero: `canRequestPackageInstalls` e la pagina `ACTION_MANAGE_UNKNOWN_APP_SOURCES` di questa app. */
class AndroidInstallPermission(context: Context) : InstallPermission {
  private val app = context.applicationContext

  override fun granted(): Boolean = app.packageManager.canRequestPackageInstalls()

  override fun open(): Boolean = runCatching {
    app.startActivity(
      Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }.isSuccess
}

/** La rete in uso e' a consumo (dati mobili, hotspot)? Senza una risposta si dice di si': meglio un avviso in piu' che 110 MB a sorpresa. */
class AndroidMeteredNetwork(context: Context) {
  private val app = context.applicationContext

  @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE e' gia' dell'app (core-network).
  fun isMetered(): Boolean = runCatching {
    (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
  }.getOrDefault(true)
}

/** Lo SHA-256 vero, a blocchi da 64 KiB. */
object Sha256Files : FileChecksum {
  override fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
