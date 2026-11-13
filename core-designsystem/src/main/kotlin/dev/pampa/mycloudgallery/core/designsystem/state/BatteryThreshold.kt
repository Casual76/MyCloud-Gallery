package dev.pampa.mycloudgallery.core.designsystem.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.R

/**
 * **Il livello di batteria sotto cui il backup e l'analisi aspettano** (Pacchetto B, seconda beta): il numero che l'utente ha scelto, per dirlo dove un lavoro e' fermo
 * per la batteria. Con la soglia al 60% e il telefono al 55%, «Batteria scarica» e' falso: la frase vera e' «Batteria sotto il 60%».
 *
 * La fornisce la radice dell'app (`AppRoot` in `:app`, dalle impostazioni delle regole); nei test e nelle anteprime vale null e le schermate dicono la frase che e' vera
 * a qualunque livello («Batteria sotto la soglia»). Come [LocalWritesHeldByRelease]: un dato di contesto che molte schermate leggono per scegliere la loro frase, senza
 * che il numero viaggi in ogni stato di backup e di analisi.
 */
val LocalBatteryThreshold = compositionLocalOf<Int?> { null }

/** La frase di «fermo per la batteria»: col numero scelto se la radice lo da', altrimenti quella senza numero (sempre vera). Una formula sola per tutti i posti. */
@Composable
@ReadOnlyComposable
fun batteryWaitText(): String {
  val percent = LocalBatteryThreshold.current
  return if (percent == null) stringResource(R.string.gallery_wait_battery) else stringResource(R.string.gallery_wait_battery_at, percent)
}
