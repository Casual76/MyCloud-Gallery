package dev.pampa.mycloudgallery.feature.integrations.receive

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.picker.OverlayShield
import dev.pampa.mycloudgallery.feature.integrations.picker.UnlockFailure
import dev.pampa.mycloudgallery.feature.integrations.share.FlowCounters
import dev.pampa.mycloudgallery.feature.integrations.share.ReceiveCounter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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

  /** IN-10: la schermata di sblocco parte da sola una volta per activity; dopo, con il tasto (un "Annulla" non la riapre in ciclo). */
  private var autoPrompted = false

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
    // IN-3, IN-10: la credenziale di sistema si chiede da sola quando la schermata e' bloccata, una volta per activity: anche se la schermata
    // diventa "bloccata" dopo (l'app stava leggendo il suo stato), e senza riaprirla in ciclo dopo un "Annulla".
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        model.ui.map { it.screen }.distinctUntilChanged().collect { screen ->
          if (screen == ReceiveScreenState.LOCKED && !autoPrompted) {
            autoPrompted = true
            startUnlock()
          }
        }
      }
    }
  }

  /** La finestra non prende tocchi attraverso una sovrapposizione: un'app che disegna sopra non fa premere "Salva sul NAS" al posto dell'utente. */
  private fun protectFromOverlays() = OverlayShield.protect(window)

  /**
   * Campo, gruppo A (D1, `IMG_mcg_0106`): una condivisione nuova lanciata con `NEW_TASK` (Quick Share, `am start`) mentre questa schermata e' ancora in
   * cima al suo task. Prima Android riportava davanti la schermata vecchia ("1 file in coda" della condivisione di prima) e quella nuova non arrivava
   * mai all'app: niente copiato, nessuna riga, nessun avviso. Con `singleTop` arriva qui e si apre in una schermata sua, in un task suo, con il permesso
   * di lettura che ha portato; questa si chiude come con "Annulla" (le copie non salvate si tolgono, quelle gia' in coda sono del diario e restano; un
   * salvataggio in corso non si interrompe).
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (ReceiveRequest.from(intent) == null) return
    val forward = Intent(intent)
      .setClass(this, ShareReceiverActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    try {
      startActivity(forward)
    } catch (_: RuntimeException) {
      // Nessuna schermata nuova (permesso, activity non trovata): questa resta com'e', e la condivisione nuova non si copia.
      return
    }
    viewModel?.cancel() ?: finish()
  }

  override fun onResume() {
    super.onResume()
    // Il blocco puo' essersi chiuso mentre si era altrove: il cancello si rilegge a ogni ritorno (E-X7).
    viewModel?.refreshGate()
  }

  /** Lo sblocco con la credenziale di sistema. Una volta per volta; se l'utente annulla si riprova con il tasto. */
  private fun startUnlock() {
    val model = viewModel ?: return
    if (!model.beginUnlock()) return
    lifecycleScope.launch {
      var unlocked = false
      val unlock = ReceivePorts.unlock
      try {
        unlocked = unlock.unlock(this@ShareReceiverActivity)
      } catch (e: CancellationException) {
        model.endUnlock(false)
        throw e
      } catch (e: Exception) {
        unlocked = false
      }
      model.endUnlock(unlocked, if (unlocked) null else unlock.lastFailure() ?: UnlockFailure.OTHER)
    }
  }
}
