package dev.pampa.mycloudgallery.integrations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pampa.mycloudgallery.feature.integrations.edit.EditIntents

/**
 * Correzioni 5B (R4, MC-1, MC-2): la scelta dell'app di "Condividi" la lancia un'activity viva, alla radice della galleria, con o senza il backup
 * (prima la lanciava solo l'host della 5B, che senza backup non c'e'). Una volta sola per richiesta; l'esito torna ad [AppShares], che consegna la
 * copia al custode o la toglie. Si lancia solo con l'activity avviata (`collectAsStateWithLifecycle`): mai un avvio dallo sfondo.
 */
@Composable
internal fun ShareHostEffect(shares: AppShares?) {
  if (shares == null) return
  val context = LocalContext.current
  val launch by shares.launches.collectAsStateWithLifecycle()
  LaunchedEffect(launch) {
    val request = launch ?: return@LaunchedEffect
    // Fase 10 (P13, D18, punto aperto di P4): con due finestre composte ognuna ha questo effetto; la prima che arriva lancia e lo segna, l'altra trova la richiesta gia'
    // presa e non lancia un secondo foglio (gli effetti girano uno dopo l'altro sul thread principale).
    if (shares.launches.value !== request) return@LaunchedEffect
    shares.launched(request, EditIntents.launch(context, request.intent))
  }
}
