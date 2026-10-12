package dev.pampa.mycloudgallery.core.rules.platform.health

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PermissionInfo
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/**
 * [AndroidBackgroundHealthReader] su Robolectric (SDK 35): ogni campo di [BackgroundHealth] letto dal servizio
 * di sistema vero (con le sue ombre), piu' i rami per versione di Android, che qui si provano passando
 * `sdkInt` (le android-all di altre versioni non sono nella cache del PC).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidBackgroundHealthReaderTest {

  private val application: Application = ApplicationProvider.getApplicationContext()
  private val context: Context = application

  private object Unconfined : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
  }

  private fun reader(
    context: Context = this.context,
    sdkInt: Int = SDK_UNDER_TEST,
    timeoutMillis: Long = 200,
  ) = AndroidBackgroundHealthReader(context, Unconfined, sdkInt, timeoutMillis)

  private fun <T : Any> service(type: Class<T>): T = checkNotNull(context.getSystemService(type))

  // --- Batteria, bucket, limitazione -------------------------------------------------------------------

  @Test
  fun batteriaIllimitata_sileggeDalPowerManager() = runTest {
    val power = service(PowerManager::class.java)

    shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, false)
    assertThat(reader().read().ignoringBatteryOpt).isFalse()

    shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)
    assertThat(reader().read().ignoringBatteryOpt).isTrue()
  }

  @Test
  fun ilBucketDiStandbySiTraduce() = runTest {
    val usage = service(UsageStatsManager::class.java)
    val expected = mapOf(
      UsageStatsManager.STANDBY_BUCKET_ACTIVE to StandbyBucket.ACTIVE,
      UsageStatsManager.STANDBY_BUCKET_WORKING_SET to StandbyBucket.WORKING_SET,
      UsageStatsManager.STANDBY_BUCKET_FREQUENT to StandbyBucket.FREQUENT,
      UsageStatsManager.STANDBY_BUCKET_RARE to StandbyBucket.RARE,
      UsageStatsManager.STANDBY_BUCKET_RESTRICTED to StandbyBucket.RESTRICTED,
    )
    expected.forEach { (raw, bucket) ->
      shadowOf(usage).setCurrentAppStandbyBucket(raw)
      assertThat(reader().read().standbyBucket).isEqualTo(bucket)
    }
  }

  @Test
  fun laLimitazioneInBackgroundSiLegge() = runTest {
    val am = service(ActivityManager::class.java)

    shadowOf(am).setBackgroundRestricted(false)
    assertThat(reader().read().backgroundRestricted).isFalse()

    shadowOf(am).setBackgroundRestricted(true)
    assertThat(reader().read().backgroundRestricted).isTrue()
  }

  @Test
  fun sottoAndroid9BucketELimitazioneNonSiIndovinano() = runTest {
    val health = reader(sdkInt = 27).read()

    assertThat(health.standbyBucket).isEqualTo(StandbyBucket.UNKNOWN)
    assertThat(health.backgroundRestricted).isNull()
  }

  // --- "Pausa se inutilizzata" -----------------------------------------------------------------------------

  @Test
  fun pausaSeInutilizzata_accesaDiDefault_eSpentaSeEsente() = runTest {
    // Per Android l'app e' soggetta alla pausa finche' non e' nella lista delle esenti. Lo stato si legge
    // solo a utente sbloccato (androidx lo rifiuta prima dello sblocco: vedi il test seguente).
    shadowOf(service(UserManager::class.java)).setUserUnlocked(true)
    // androidx rifiuta le app con targetSdk < 30; il manifest di prova del modulo non ne dichiara uno.
    application.applicationInfo.targetSdkVersion = TARGET_SDK_OF_THE_APP
    shadowOf(context.packageManager).setAutoRevokeWhitelisted(false)
    assertThat(reader().read().unusedAppRestrictions).isEqualTo(UnusedAppRestrictions.ENABLED)

    shadowOf(context.packageManager).setAutoRevokeWhitelisted(true)
    assertThat(reader().read().unusedAppRestrictions).isEqualTo(UnusedAppRestrictions.DISABLED)
  }

  @Test
  fun primaDelloSblocco_laPausaNonSiPuoLeggere() = runTest {
    // BFU: androidx rifiuta la lettura ("l'utente e' bloccato"). Si resta su UNKNOWN, senza indovinare.
    shadowOf(service(UserManager::class.java)).setUserUnlocked(false)

    assertThat(reader().read().unusedAppRestrictions).isEqualTo(UnusedAppRestrictions.UNKNOWN)
  }

  // --- Permessi ---------------------------------------------------------------------------------------------

  @Test
  fun tuttiIPermessiSonoSempreNellaMappa() = runTest {
    assertThat(reader().read().permissions.keys).containsExactlyElementsIn(HealthPermission.entries)
  }

  @Test
  fun iPermessiDiSistemaSiLeggonoDaCheckSelfPermission() = runTest {
    shadowOf(application).grantPermissions(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    shadowOf(application).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.CAMERA)

    val permissions = reader().read().permissions

    assertThat(permissions[HealthPermission.MEDIA_IMAGES]).isEqualTo(PermissionGrant.GRANTED)
    assertThat(permissions[HealthPermission.MEDIA_LOCATION]).isEqualTo(PermissionGrant.GRANTED)
    assertThat(permissions[HealthPermission.MEDIA_VIDEO]).isEqualTo(PermissionGrant.DENIED)
    assertThat(permissions[HealthPermission.CAMERA]).isEqualTo(PermissionGrant.DENIED)
  }

  @Test
  fun foto_selezionate_eUnPermessoASe() = runTest {
    // Android 14: "Seleziona foto" concede VISUAL_USER_SELECTED e lascia negato READ_MEDIA_IMAGES (E-P1).
    shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    shadowOf(application).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    val permissions = reader().read().permissions

    assertThat(permissions[HealthPermission.MEDIA_PARTIAL]).isEqualTo(PermissionGrant.GRANTED)
    assertThat(permissions[HealthPermission.MEDIA_IMAGES]).isEqualTo(PermissionGrant.DENIED)
  }

  @Test
  fun leNotificheSegueLInterruttoreDelSistema() = runTest {
    val notifications = service(NotificationManager::class.java)

    shadowOf(notifications).setNotificationsEnabled(true)
    assertThat(reader().read().permissions[HealthPermission.NOTIFICATIONS]).isEqualTo(PermissionGrant.GRANTED)

    shadowOf(notifications).setNotificationsEnabled(false)
    assertThat(reader().read().permissions[HealthPermission.NOTIFICATIONS]).isEqualTo(PermissionGrant.DENIED)
  }

  @Test
  fun permessiCheNonEsistonoSullaVersioneNonSonoRifiuti() = runTest {
    // Android 12: niente READ_MEDIA_* (13) ne' VISUAL_USER_SELECTED (14). Android 10 ha ACCESS_MEDIA_LOCATION.
    val android12 = reader(sdkInt = 32).read().permissions
    assertThat(android12[HealthPermission.MEDIA_IMAGES]).isEqualTo(PermissionGrant.NOT_APPLICABLE)
    assertThat(android12[HealthPermission.MEDIA_VIDEO]).isEqualTo(PermissionGrant.NOT_APPLICABLE)
    assertThat(android12[HealthPermission.MEDIA_PARTIAL]).isEqualTo(PermissionGrant.NOT_APPLICABLE)
    assertThat(android12[HealthPermission.MEDIA_LOCATION]).isNotEqualTo(PermissionGrant.NOT_APPLICABLE)

    // Android 13: READ_MEDIA_* si', VISUAL_USER_SELECTED ancora no.
    val android13 = reader(sdkInt = 33).read().permissions
    assertThat(android13[HealthPermission.MEDIA_IMAGES]).isNotEqualTo(PermissionGrant.NOT_APPLICABLE)
    assertThat(android13[HealthPermission.MEDIA_PARTIAL]).isEqualTo(PermissionGrant.NOT_APPLICABLE)
  }

  @Test
  fun wireGuardNonInstallato_nonCEDaConcedere() = runTest {
    // Il permesso lo dichiara l'app WireGuard: senza, non esiste (Robolectric non ne ha).
    assertThat(reader().read().permissions[HealthPermission.WIREGUARD_CONTROL]).isEqualTo(PermissionGrant.NOT_APPLICABLE)
  }

  @Test
  fun wireGuardInstallato_ilPermessoECondesso_onegato() = runTest {
    shadowOf(context.packageManager).addPermissionInfo(
      PermissionInfo().apply { name = AndroidBackgroundHealthReader.WIREGUARD_CONTROL_TUNNELS },
    )

    shadowOf(application).denyPermissions(AndroidBackgroundHealthReader.WIREGUARD_CONTROL_TUNNELS)
    assertThat(reader().read().permissions[HealthPermission.WIREGUARD_CONTROL]).isEqualTo(PermissionGrant.DENIED)

    shadowOf(application).grantPermissions(AndroidBackgroundHealthReader.WIREGUARD_CONTROL_TUNNELS)
    assertThat(reader().read().permissions[HealthPermission.WIREGUARD_CONTROL]).isEqualTo(PermissionGrant.GRANTED)
  }

  // --- Sblocco, riavvii, uscite, profilo -----------------------------------------------------------------

  @Test
  fun primaDelloSbloccoSiVede() = runTest {
    val users = service(UserManager::class.java)

    shadowOf(users).setUserUnlocked(false)
    assertThat(reader().read().userUnlocked).isFalse()

    shadowOf(users).setUserUnlocked(true)
    assertThat(reader().read().userUnlocked).isTrue()
  }

  @Test
  fun ilContatoreDeiRiavviCresceEUnRiavvioSiRiconosce() = runTest {
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 33)
    val before = reader().read().bootCount
    Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 34)
    val after = reader().read().bootCount

    assertThat(before).isEqualTo(33)
    assertThat(after).isEqualTo(34)
  }

  @Test
  fun senzaContatoreDeiRiavvi_ilValoreENullNonZero() = runTest {
    // Robolectric non ha un contatore finche' non glielo si da: il telefono vero puo' non averlo (OEM).
    assertThat(reader().read().bootCount).isNull()
  }

  @Test
  fun leUscite_sonoDallaPiuRecente_econUnMotivoSolo() = runTest {
    val am = service(ActivityManager::class.java)
    listOf(
      1_000L to ApplicationExitInfo.REASON_USER_REQUESTED, // arresto forzato
      3_000L to ApplicationExitInfo.REASON_ANR,
      2_000L to ApplicationExitInfo.REASON_LOW_MEMORY,
    ).forEach { (at, reason) ->
      shadowOf(am).addApplicationExitInfo(
        ApplicationExitInfoBuilder.newBuilder()
          .setProcessName(context.packageName)
          .setReason(reason)
          .setTimestamp(at)
          .build(),
      )
    }

    val exits = reader().read().lastExitReasons

    assertThat(exits).containsExactly(
      ExitRecord(ExitReasonKind.ANR, 3_000L),
      ExitRecord(ExitReasonKind.LOW_MEMORY, 2_000L),
      ExitRecord(ExitReasonKind.USER_REQUESTED, 1_000L),
    ).inOrder()
  }

  @Test
  fun leUsciteSonoLimitateEOltreAndroid10SiLeggono() = runTest {
    val am = service(ActivityManager::class.java)
    (1..20).forEach { index ->
      shadowOf(am).addApplicationExitInfo(
        ApplicationExitInfoBuilder.newBuilder()
          .setProcessName(context.packageName)
          .setReason(ApplicationExitInfo.REASON_CRASH)
          .setTimestamp(index * 1_000L)
          .build(),
      )
    }

    assertThat(reader().read().lastExitReasons).hasSize(AndroidBackgroundHealthReader.MAX_EXIT_RECORDS)
    // Android 10 non ha ApplicationExitInfo: nessuna uscita, non un errore.
    assertThat(reader(sdkInt = 29).read().lastExitReasons).isEmpty()
  }

  @Test
  fun profiloPrincipaleOSecondario() = runTest {
    val users = service(UserManager::class.java)

    shadowOf(users).setIsSystemUser(true)
    assertThat(reader().read().isSystemUser).isTrue()

    shadowOf(users).setIsSystemUser(false)
    assertThat(reader().read().isSystemUser).isFalse()
  }

  // --- Quando Android non risponde ------------------------------------------------------------------------

  /** Un contesto i cui servizi sono tutti guasti, come con un permesso revocato o un servizio morto. */
  private class BrokenServicesContext(base: Context) : ContextWrapper(base) {
    // getSystemService(Class) e' final in Context e passa da questo: basta rompere la versione per nome.
    override fun getSystemService(name: String): Any = throw SecurityException("servizio non disponibile")

    override fun getApplicationContext(): Context = this
  }

  @Test
  fun seAndroidNonRisponde_ilValoreEUnknownENonUnaSupposizione() = runTest {
    val health = reader(context = BrokenServicesContext(context)).read()

    assertThat(health.ignoringBatteryOpt).isNull()
    assertThat(health.standbyBucket).isEqualTo(StandbyBucket.UNKNOWN)
    assertThat(health.backgroundRestricted).isNull()
    assertThat(health.lastExitReasons).isEmpty()
    // I due campi non nulli ripiegano sul valore del null-object, che non genera avvisi.
    assertThat(health.userUnlocked).isEqualTo(BackgroundHealth.UNKNOWN.userUnlocked)
    assertThat(health.isSystemUser).isEqualTo(BackgroundHealth.UNKNOWN.isSystemUser)
    // I permessi che non si possono leggere sono negati, non concessi.
    assertThat(health.permissions[HealthPermission.NOTIFICATIONS]).isEqualTo(PermissionGrant.DENIED)
  }

  @Test
  fun unaLetturaGuastaNonPortaViaLeAltre() = runTest {
    // Solo UsageStatsManager e' guasto: il resto si legge normalmente.
    val partiallyBroken = object : ContextWrapper(context) {
      override fun getSystemService(name: String): Any? =
        if (name == Context.USAGE_STATS_SERVICE) throw SecurityException("guasto") else super.getSystemService(name)

      override fun getApplicationContext(): Context = this
    }
    shadowOf(service(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)

    val health = reader(context = partiallyBroken).read()

    assertThat(health.standbyBucket).isEqualTo(StandbyBucket.UNKNOWN)
    assertThat(health.ignoringBatteryOpt).isTrue()
  }

  private companion object {
    /** Robolectric gira sull'SDK 35 (gradle/test-resources/robolectric.properties). */
    const val SDK_UNDER_TEST = 35

    /** Il targetSdk dell'app vera (versions-app.gradle). */
    const val TARGET_SDK_OF_THE_APP = 36
  }
}
