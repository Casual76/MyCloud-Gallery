package dev.pampa.mycloudgallery.feature.integrations.receive

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * La ricezione delle condivisioni (Fase 5B, D24, C17, C18; docs/fase5-progetto.md §2.7): "Salva sul NAS" per `SEND` e `SEND_MULTIPLE` di immagini e
 * video, dal foglio di condivisione di Samsung Gallery, I miei file, Chrome...
 *
 * **Cosa fa per un'altra app, e cosa no**
 *  - dell'intento legge solo azione, tipo e gli indirizzi dei flussi ([ReceiveRequest]); un'azione che non serve o un intento senza flussi chiudono
 *    subito con `RESULT_CANCELED`, senza mostrare niente;
 *  - **non apre niente** finche' il cancello d'ingresso non dice "pronta e sbloccata": con l'app bloccata mostra solo lo sblocco con la credenziale di
 *    sistema (E-X7), e il cancello si rilegge a ogni ritorno in primo piano;
 *  - i flussi si **copiano subito** nel telefono ([IncomingStager]): il permesso di lettura dell'altra app dura quanto questa activity. `file://` e i
 *    provider dell'app si rifiutano senza aprirli, il tipo si legge dai byte, i tetti valgono prima di scrivere (E-X2);
 *  - **non carica niente da sola**: il caricamento sul NAS parte solo dal tocco su "Salva sul NAS" (E-S11). Indietro o "Annulla" tolgono le copie;
 *  - la finestra scarta i tocchi che passano attraverso una sovrapposizione di un'altra app mentre c'e' il bottone (tapjacking, R7 della 4B).
 *
 * E' una `FragmentActivity` come la `MainActivity`: lo sblocco con la credenziale di sistema ne ha bisogno. Le porte le installa `:app` ([ReceivePorts],
 * `IntegrationPorts.entryGate`); `taskAffinity` vuota, fuori dai recenti e non `singleTask` stanno nel manifest (P0-B).
 */
class ShareReceiverActivity : FragmentActivity() {

  private var viewModel: ReceiveViewModel? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val request = ReceiveRequest.from(intent)
    if (request == null) {
      // Un'azione che la ricezione non serve, o nessun flusso da leggere: annullato, niente si apre.
      FlowCounters.receive.add(ReceiveCounter.REFUSED_REQUEST)
      setResult(RESULT_CANCELED)
      finish()
      return
    }
    FlowCounters.receive.add(ReceiveCounter.OPENED)
    enableEdgeToEdge()

    val resolver = applicationContext.contentResolver
    val ownPackage = packageName
    val uploads = ReceivePorts.uploads
    val destinations = ReceivePorts.destinations
    val model = ViewModelProvider(
      this,
      viewModelFactory {
        initializer {
          ReceiveViewModel(
            streams = { IncomingStreams.of(resolver, ownPackage, request) },
            overflow = request.overflow,
            gate = IntegrationPorts.entryGate,
            uploads = uploads,
            destinations = destinations,
            monotonicMs = { SystemClock.elapsedRealtime() },
          )
        }
      },
    )[ReceiveViewModel::class.java]
    viewModel = model

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = model.cancel()
      },
    )

    setContent {
      MyCloudGalleryTheme {
        val ui by model.ui.collectAsStateWithLifecycle()
        val actions = remember(model) {
          ReceiveActions(
            onSelect = model::select,
            onOpen = model::open,
            onUp = model::up,
            onSave = model::save,
            onCancel = model::cancel,
            onClose = model::finish,
            onRetry = model::retry,
            onUnlock = ::startUnlock,
          )
        }
        ReceiveScreen(ui = ui, actions = actions)
      }
    }

    protectFromOverlays()

    lifecycleScope.launch {
      model.outcome.filterNotNull().collect {
        setResult(if (model.ui.value.screen == ReceiveScreenState.DONE) RESULT_OK else RESULT_CANCELED)
        finish()
      }
    }
  }

  /**
   * La finestra non prende tocchi attraverso una sovrapposizione: un'app che disegna sopra non fa premere "Salva sul NAS" al posto dell'utente.
   * `HIDE_OVERLAY_WINDOWS` non e' nel manifest di questo modulo (nessun permesso nuovo qui, §4.3): lo porta `core-security` nel manifest di `:app`.
   * Senza, `setHideOverlayWindows` lancia e resta il filtro dei tocchi oscurati.
   */
  @SuppressLint("MissingPermission")
  private fun protectFromOverlays() {
    window.decorView.filterTouchesWhenObscured = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      try {
        window.setHideOverlayWindows(true)
      } catch (_: SecurityException) {
        // Resta il filtro dei tocchi oscurati.
      }
    }
  }

  override fun onResume() {
    super.onResume()
    val model = viewModel ?: return
    // Il blocco puo' essersi chiuso mentre si era altrove: il cancello si rilegge a ogni ritorno (E-X7).
    model.refreshGate()
    if (model.ui.value.screen == ReceiveScreenState.LOCKED) startUnlock()
  }

  /** Lo sblocco con la credenziale di sistema. Una volta per volta; se l'utente annulla si riprova con il tasto. */
  private fun startUnlock() {
    val model = viewModel ?: return
    if (!model.beginUnlock()) return
    lifecycleScope.launch {
      var unlocked = false
      try {
        unlocked = ReceivePorts.unlock.unlock(this@ShareReceiverActivity)
      } catch (e: CancellationException) {
        model.endUnlock(false)
        throw e
      } catch (e: Exception) {
        unlocked = false
      }
      model.endUnlock(unlocked)
    }
  }
}
