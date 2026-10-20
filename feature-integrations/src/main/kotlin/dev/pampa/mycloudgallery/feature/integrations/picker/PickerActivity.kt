package dev.pampa.mycloudgallery.feature.integrations.picker

import android.os.Bundle
import android.widget.Toast
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
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.imaging.noop.NoPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailLoader
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.R
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentsAuthority
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Il selettore per le altre app (Fase 5B, D23, C17; docs/fase5-progetto.md §2.7): risponde a `GET_CONTENT` e `PICK` di immagini e video con la
 * griglia della Libreria, i nascosti esclusi (E-H1), il blocco dell'app prima di mostrare (E-X7) e un tocco per consegnare.
 *
 * **Cosa fa per un'altra app, e cosa no**
 *  - legge dell'intento solo azione, tipi e tre booleani ([PickerRequest]); un'azione che non serve o un intento ostile chiudono subito con
 *    `RESULT_CANCELED`, senza mostrare niente;
 *  - **non mostra niente** finche' il cancello d'ingresso non dice "pronta e sbloccata"; con l'app bloccata mostra solo lo sblocco con la
 *    credenziale di sistema ([EntryUnlock]), e il cancello si rilegge a ogni ritorno in primo piano;
 *  - **non consegna niente da solo**: serve il tocco dell'utente (su un elemento, o su "Scegli");
 *  - consegna URI del MediaStore per le copie del telefono e URI del nostro provider (id opachi) per i media solo-NAS, **sempre e solo** con
 *    `FLAG_GRANT_READ_URI_PERMISSION` e `ClipData` (mai scrittura, mai permessi persistenti);
 *  - un annullamento (indietro, chiudi) non lascia id ne' permessi (E-X4).
 *
 * E' una `FragmentActivity` come la `MainActivity`: lo sblocco con la credenziale di sistema ne ha bisogno. Le porte le installa `:app`
 * (`IntegrationPorts`, `PickerPorts`); `taskAffinity` vuota, fuori dai recenti e non `singleTask` stanno nel manifest (P0-B).
 */
class PickerActivity : FragmentActivity() {

  private var viewModel: PickerViewModel? = null

  /** IN-10: la schermata di sblocco parte da sola una volta per activity; dopo, con il tasto (un "Annulla" non la riapre in ciclo). */
  private var autoPrompted = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // IN-4: lo sblocco chiesto dal provider dei documenti (`AuthenticationRequiredException`): nessuna griglia, solo la credenziale.
    if (intent?.action == ACTION_UNLOCK) {
      unlockOnly(savedInstanceState)
      return
    }
    val request = PickerRequest.from(intent)
    if (request == null) {
      // Un'azione che il selettore non serve, o nessun tipo di media: annullato, niente si apre.
      IntegrationCounters.add(IntegrationCounter.PICKER_REFUSED_REQUEST)
      setResult(RESULT_CANCELED)
      finish()
      return
    }
    IntegrationCounters.add(IntegrationCounter.PICKER_OPENED)
    enableEdgeToEdge()

    val model = ViewModelProvider(this, PickerViewModel.factory(request, DocumentsAuthority.of(this)))[PickerViewModel::class.java]
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
        val library = model.library
        val env = remember(library) { PickerEnv(library.thumbnails, library.placeholders) { id -> library.items.refOrNull(id) } }
        val actions = remember(model) {
          PickerActions(
            onTap = model::onTap,
            onConfirm = model::confirm,
            onCancel = model::cancel,
            onUnlock = ::startUnlock,
            onDismissNotice = model::dismissNotice,
            onCancelDelivery = model::cancelDelivery,
          )
        }
        PickerScreen(ui = ui, request = request, env = env, actions = actions)
      }
    }

    // R8 della revisione di sicurezza 5B: la finestra non prende i tocchi che passano attraverso una sovrapposizione di un'altra app (anche
    // quella dello sblocco), e le sovrapposizioni si nascondono: un gioco "tocca qui" sopra la griglia non sceglie ne' consegna una foto.
    OverlayShield.protect(window)

    lifecycleScope.launch {
      model.outcome.filterNotNull().collect { finishWith(it) }
    }
    // IN-3, IN-10: la credenziale di sistema si chiede da sola quando la schermata e' bloccata, una volta per activity: anche se la schermata
    // diventa "bloccata" dopo (l'app stava leggendo il suo stato), e senza riaprirla in ciclo dopo un "Annulla".
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        model.ui.map { it.screen }.distinctUntilChanged().collect { screen ->
          if (screen == PickerScreenState.LOCKED && !autoPrompted) {
            autoPrompted = true
            startUnlock()
          }
        }
      }
    }
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
      val unlock = PickerPorts.unlock
      try {
        unlocked = unlock.unlock(this@PickerActivity)
      } catch (e: CancellationException) {
        model.endUnlock(false)
        throw e
      } catch (e: Exception) {
        unlocked = false
      }
      model.endUnlock(unlocked, if (unlocked) null else unlock.lastFailure() ?: UnlockFailure.OTHER)
    }
  }

  // --- Solo lo sblocco (IN-4) ---------------------------------------------------------------------------------------------

  private val unlockUi = MutableStateFlow(PickerUi(screen = PickerScreenState.LOCKED))

  /**
   * IN-4 della verifica funzionale 5B: con il blocco "Subito" lo sblocco chiesto dal provider dei documenti passava dalla `MainActivity`, che al
   * ritorno a DocumentsUI tornava "fuori dall'app" e richiudeva il blocco senza nessuna tolleranza: "Sblocca" in ciclo. Qui lo sblocco lo fa
   * un'activity d'ingresso (`endEntryAuthentication`: due minuti di tolleranza) e restituisce `RESULT_OK` a chi l'ha lanciata (DocumentsUI rilegge).
   * Non mostra niente della Libreria e non consegna niente: e' la schermata di blocco e basta. Se lo sblocco non riesce o si annulla, `RESULT_CANCELED`.
   */
  private fun unlockOnly(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    OverlayShield.protect(window)
    setResult(RESULT_CANCELED)
    val env = PickerEnv(NoThumbnailLoader, NoPlaceholderCache) { null }
    val request = PickerRequest(PickerAction.GET_CONTENT, images = true, videos = true, allowMultiple = false, localOnly = false, mimeFilters = emptyList())
    setContent {
      MyCloudGalleryTheme {
        val ui by unlockUi.collectAsStateWithLifecycle()
        PickerScreen(
          ui = ui,
          request = request,
          env = env,
          actions = PickerActions(onCancel = ::finish, onUnlock = ::startUnlockOnly),
        )
      }
    }
    // Una sola volta per apertura (non a ogni ricreazione: la schermata delle credenziali puo' far girare l'activity).
    if (savedInstanceState == null) startUnlockOnly()
  }

  private fun startUnlockOnly() {
    if (unlockUi.value.unlocking) return
    unlockUi.update { it.copy(unlocking = true, unlockFailed = false, unlockFailure = null) }
    lifecycleScope.launch {
      var unlocked = false
      val unlock = PickerPorts.unlock
      try {
        unlocked = unlock.unlock(this@PickerActivity) && IntegrationPorts.entryGate.decide() == EntryDecision.ALLOWED
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        unlocked = false
      }
      if (unlocked) {
        setResult(RESULT_OK)
        finish()
      } else {
        unlockUi.update { it.copy(unlocking = false, unlockFailed = true, unlockFailure = unlock.lastFailure() ?: UnlockFailure.OTHER) }
      }
    }
  }

  companion object {
    /**
     * L'azione con cui il provider dei documenti chiede solo lo sblocco ([unlockPendingIntent] di `MediaDocumentsProvider`). Si indirizza sempre al
     * componente per nome (intento esplicito, `PendingIntent` immutabile): non e' una porta in piu' per le altre app, che al massimo si vedono
     * chiedere la credenziale, senza che niente della Libreria si apra o esca.
     */
    const val ACTION_UNLOCK: String = "dev.pampa.mycloudgallery.integrations.action.UNLOCK_FOR_DOCUMENTS"
  }

  private fun finishWith(outcome: PickerOutcome) {
    when (outcome) {
      is PickerOutcome.Deliver -> {
        setResult(RESULT_OK, PickerResults.intent(outcome.uris))
        if (outcome.skipped > 0) {
          val text = resources.getQuantityString(R.plurals.integrations_picker_skipped_toast, outcome.skipped, outcome.skipped)
          Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
        }
      }
      PickerOutcome.Cancel -> setResult(RESULT_CANCELED)
    }
    finish()
  }
}
