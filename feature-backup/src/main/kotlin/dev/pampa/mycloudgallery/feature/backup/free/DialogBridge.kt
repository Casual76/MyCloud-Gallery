package dev.pampa.mycloudgallery.feature.backup.free

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

// Fase 5B (P12, docs/fase5-progetto.md D19, D21, E-E10): il PONTE fra un ViewModel e il dialogo del sistema. Il dialogo (il cestino di
// Android, il permesso di riscrivere un file) si lancia da un'`Activity` con un launcher che vive nella composizione; il lavoro che lo
// aspetta vive nel ViewModel, che sopravvive a una rotazione. Il ViewModel mette la richiesta nel ponte e aspetta; la composizione la
// prende, lancia il dialogo e riporta la risposta. Una richiesta si lancia **una volta sola**: dopo una rotazione la nuova composizione
// continua a leggere dallo stesso ponte, non rilancia quella di prima. Se il processo muore mentre il dialogo e' aperto non si riceve
// nessuna risposta: lo sa la lettura del telefono (`FreeSpace.recordOutcome` con `TrashOutcome.Unknown`, P9), mai questo ponte.

/** Il ponte di un ViewModel verso i dialoghi del sistema. Un dialogo per volta. */
internal class DialogBridge {

  private val requests = Channel<IntentSender>(Channel.UNLIMITED)
  private var waiting: CompletableDeferred<Boolean>? = null

  /** Le richieste che la composizione deve lanciare, una alla volta. */
  val incoming: ReceiveChannel<IntentSender> get() = requests

  /** C'e' un dialogo aperto che aspetta una risposta. */
  val isWaiting: Boolean get() = waiting != null

  /** Mette [sender] in coda per la composizione e aspetta la risposta dell'utente. */
  suspend fun ask(sender: IntentSender): DialogAnswer {
    val answer = CompletableDeferred<Boolean>()
    waiting = answer
    requests.send(sender)
    return try {
      if (answer.await()) DialogAnswer.ALLOWED else DialogAnswer.REFUSED
    } finally {
      waiting = null
    }
  }

  /** La risposta del sistema: [allowed] = l'utente ha detto di si'. */
  fun answered(allowed: Boolean) {
    waiting?.complete(allowed)
  }
}

/**
 * Collega [bridge] ai dialoghi del sistema: lancia ogni richiesta che il ViewModel mette in coda e gli riporta la risposta. Va chiamata
 * dalla schermata (o dal foglio) che ospita il ViewModel, una volta.
 */
@Composable
internal fun BindDialogBridge(bridge: DialogBridge) {
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
    bridge.answered(result.resultCode == Activity.RESULT_OK)
  }
  LaunchedEffect(bridge) {
    for (sender in bridge.incoming) launcher.launch(IntentSenderRequest.Builder(sender).build())
  }
}
