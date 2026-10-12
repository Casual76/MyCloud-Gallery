package dev.pampa.mycloudgallery.feature.diagnostics.actions

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Le azioni suggerite: quali intent, in che ordine, e il ripiego quando una schermata non c'e'. */
@RunWith(RobolectricTestRunner::class)
class ActionIntentsTest {

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val wireGuard = Intent("com.wireguard.android.OPEN").setPackage("com.wireguard.android")

  private fun actions(action: DiagAction) = ActionIntents.candidates(app, action, wireGuard).map { it.action }

  @Test
  fun leAzioniHannoLeEtichette() {
    DiagnosticsActions.INTENT_ACTIONS.forEach { assertThat(DiagnosticsActions.labelRes(it)).isNotEqualTo(0) }
    DiagnosticsActions.LOCAL_ACTIONS.forEach { assertThat(DiagnosticsActions.labelRes(it)).isNotEqualTo(0) }
    assertThat(DiagnosticsActions.labelRes(DiagAction("azione_sconosciuta"))).isEqualTo(0)
  }

  @Test
  fun unAzioneChePasseDaAppSiProponeSoloSeAppLaEsegue() {
    assertThat(DiagnosticsActions.isAvailable(DiagAction.OPEN_DEVICES, emptySet())).isFalse()
    assertThat(DiagnosticsActions.isAvailable(DiagAction.OPEN_DEVICES, setOf(DiagAction.OPEN_DEVICES))).isTrue()
    // Un'azione che nessuno conosce non ha mai il tasto, nemmeno se :app la dichiarasse.
    val unknown = DiagAction("azione_sconosciuta")
    assertThat(DiagnosticsActions.isAvailable(unknown, setOf(unknown))).isFalse()
    // Quelle di Android e il test sono nostre.
    assertThat(DiagnosticsActions.isAvailable(DiagAction.OPEN_BATTERY_SETTINGS, emptySet())).isTrue()
    assertThat(DiagnosticsActions.isAvailable(DiagAction.RUN_CONNECTION_TEST, emptySet())).isTrue()
  }

  @Test
  fun laBatteriaProvaIlDialogoDirettoPoiLElencoPoiLaSchedaDellApp() {
    assertThat(actions(DiagAction.OPEN_BATTERY_SETTINGS)).containsExactly(
      Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
      Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    ).inOrder()
    val direct = ActionIntents.candidates(app, DiagAction.OPEN_BATTERY_SETTINGS, null).first()
    assertThat(direct.data).isEqualTo(Uri.fromParts("package", app.packageName, null))
  }

  @Test
  fun leNotificheApronoLeImpostazioniDiQuestaApp() {
    val intent = ActionIntents.candidates(app, DiagAction.OPEN_NOTIFICATION_SETTINGS, null).first()

    assertThat(intent.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    assertThat(intent.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo(app.packageName)
  }

  @Test
  fun mai_in_sospensione_haIlLinkDiSamsungEIlRipiego() {
    val candidates = ActionIntents.candidates(app, DiagnosticsActions.OPEN_NEVER_SLEEPING_APPS, null)

    val samsung = candidates.first()
    assertThat(samsung.action).isEqualTo(ActionIntents.SAMSUNG_CHECKABLE_LIST_ACTION)
    assertThat(samsung.`package`).isEqualTo(ActionIntents.SAMSUNG_DEVICE_CARE_PACKAGE)
    assertThat(samsung.getIntExtra(ActionIntents.SAMSUNG_ACTIVITY_TYPE_EXTRA, -1)).isEqualTo(2) // «Mai in sospensione»
    assertThat(candidates.drop(1).map { it.action }).containsExactly(
      Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    ).inOrder()
  }

  @Test
  fun wireGuardSenzaIntentNonHaCandidati() {
    assertThat(ActionIntents.candidates(app, DiagAction.OPEN_WIREGUARD, null)).isEmpty()
    assertThat(ActionIntents.candidates(app, DiagAction.OPEN_WIREGUARD, wireGuard)).containsExactly(wireGuard)
  }

  @Test
  fun lePauseSeInutilizzataEIlBloccoSchermoHannoLaLoroSchermata() {
    assertThat(ActionIntents.candidates(app, DiagnosticsActions.OPEN_UNUSED_APP_SETTINGS, null)).isNotEmpty()
    assertThat(actions(DiagnosticsActions.OPEN_SECURITY_SETTINGS)).containsExactly(Settings.ACTION_SECURITY_SETTINGS)
  }

  @Test
  fun unAzioneDiAppONonNostraNonHaIntent() {
    assertThat(ActionIntents.candidates(app, DiagAction.OPEN_DEVICES, wireGuard)).isEmpty()
    assertThat(ActionIntents.candidates(app, DiagAction.REENTER_NAS_PASSWORD, wireGuard)).isEmpty()
  }

  @Test
  fun siPartedalPrimoIntentCheAndroidRisolve() {
    shadowOf(app).checkActivities(true)
    // Solo l'elenco della batteria esiste su questo telefono finto: il dialogo diretto e la scheda no.
    val resolved = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    shadowOf(app.packageManager).addResolveInfoForIntent(
      resolved,
      ResolveInfo().apply { activityInfo = ActivityInfo().apply { name = "Batteria"; packageName = "com.android.settings" } },
    )
    shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName("com.android.settings", "Batteria"))

    val started = ActionIntents.launch(app, DiagAction.OPEN_BATTERY_SETTINGS, null)

    assertThat(started).isTrue()
    assertThat(shadowOf(app).nextStartedActivity.action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
  }

  @Test
  fun seNessunaSchermataEsisteNonPartenienteELoDice() {
    shadowOf(app).checkActivities(true)

    val started = ActionIntents.launch(app, DiagnosticsActions.OPEN_SECURITY_SETTINGS, null)

    assertThat(started).isFalse()
    assertThat(shadowOf(app).nextStartedActivity).isNull()
  }
}
