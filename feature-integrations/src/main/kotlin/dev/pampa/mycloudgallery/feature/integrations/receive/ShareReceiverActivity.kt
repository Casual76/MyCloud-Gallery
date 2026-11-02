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
import dev.pampa.mycloudgallery.feature.integrations.drag.DropHandoff
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
    val albums = ReceivePorts.albums
    // Fase 10 (P6, D21): se il foglio lo apre un rilascio dentro l'app, da dove comincia (un album, una cartella); un evento interno, mai l'intento.
    val hint = DropHandoff.take(SystemClock.elapsedRealtime())
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
            albums = albums,
            hint = hint,
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
            onPickAlbum = model::openAlbumPicker,
            onSelectAlbum = model::selectAlbum,
            onSaveToAlbum = model::saveToAlbum,
            onBackFromAlbums = model::closeAlbumPicker,
            onCreateAlbum = model::createAlbum,
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
   * Campo, gruppo A (D1, `IMG_mcg_0106`; CA-2): una condivisione nuova (Quick Share, `am start`) mentre questa schermata e' ancora in cima al suo task.
   * Con `singleTop` arriva qui. Prima si rilanciava in una schermata nuova (`startActivity` con `NEW_TASK | MULTIPLE_TASK`) e questa si chiudeva: se Android
   * bloccava l'avvio in silenzio (limiti di avvio da sfondo di Android 14 e 15, nessuna eccezione) la condivisione nuova non partiva e la vecchia spariva
   * comunque. Adesso la nuova **prende il posto della vecchia in questa schermata** ([ReceiveViewModel.replaceRequest]): il permesso di lettura che
   * porta `onNewIntent` vale per questa activity, quindi non servono ne' un task ne' un avvio nuovi, e la nuova si copia per prima. La vecchia, se non era
   * in coda, si toglie solo dopo che la nuova e' al sicuro; una gia' in coda e' del diario e resta; un salvataggio in corso non si interrompe.
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val request = ReceiveRequest.from(intent) ?: return
    setIntent(intent)
    val model = viewModel ?: return
    val resolver = applicationContext.contentResolver
    val ownPackage = packageName
    // Un altro rilascio sullo stesso foglio: la sua destinazione vale per la copia che parte adesso.
    model.hintForNext(DropHandoff.take(SystemClock.elapsedRealtime()))
    model.replaceRequest({ IncomingStreams.of(resolver, ownPackage, request) }, request.overflow)
  }

  /** Lo stato della schermata, per i test (null prima che l'activity sia partita). */
  internal fun receiveStateForTest(): ReceiveUi? = viewModel?.ui?.value

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
