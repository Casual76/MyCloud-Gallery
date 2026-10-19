package dev.pampa.mycloudgallery.feature.integrations.picker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.feature.integrations.R
import dev.pampa.mycloudgallery.feature.integrations.provider.DocumentsAuthority
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounter
import dev.pampa.mycloudgallery.feature.integrations.provider.IntegrationCounters
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.filterNotNull
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
          )
        }
        PickerScreen(ui = ui, request = request, env = env, actions = actions)
      }
    }

    lifecycleScope.launch {
      model.outcome.filterNotNull().collect { finishWith(it) }
    }
  }

  override fun onResume() {
    super.onResume()
    val model = viewModel ?: return
    // Il blocco puo' essersi chiuso mentre si era altrove: il cancello si rilegge a ogni ritorno (E-X7).
    model.refreshGate()
    if (model.ui.value.screen == PickerScreenState.LOCKED) startUnlock()
  }

  /** Lo sblocco con la credenziale di sistema. Una volta per volta; se l'utente annulla si riprova con il tasto. */
  private fun startUnlock() {
    val model = viewModel ?: return
    if (!model.beginUnlock()) return
    lifecycleScope.launch {
      var unlocked = false
      try {
        unlocked = PickerPorts.unlock.unlock(this@PickerActivity)
      } catch (e: CancellationException) {
        model.endUnlock(false)
        throw e
      } catch (e: Exception) {
        unlocked = false
      }
      model.endUnlock(unlocked)
    }
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
