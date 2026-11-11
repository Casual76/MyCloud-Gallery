package dev.pampa.mycloudgallery

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.designsystem.adaptive.LocalNewWindow
import dev.pampa.mycloudgallery.core.designsystem.state.LocalPhoneTrash
import dev.pampa.mycloudgallery.core.designsystem.state.PhoneTrash
import dev.pampa.mycloudgallery.core.designsystem.theme.MyCloudGalleryTheme
import dev.pampa.mycloudgallery.core.memories.contract.NotifyRules
import dev.pampa.mycloudgallery.core.security.lock.AppLockController
import dev.pampa.mycloudgallery.core.security.lock.BiometricPromptAuthenticator
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapUri
import dev.pampa.mycloudgallery.core.widget.tap.WidgetTap
import dev.pampa.mycloudgallery.notify.OpenMemoriesRequests
import dev.pampa.mycloudgallery.widget.OpenWidgetRequests
import dev.pampa.mycloudgallery.window.AppNewWindowPort
import dev.pampa.mycloudgallery.window.LocalWindowRole
import dev.pampa.mycloudgallery.window.LocalWindowStart
import dev.pampa.mycloudgallery.window.WindowAdmission
import dev.pampa.mycloudgallery.window.WindowLauncher
import dev.pampa.mycloudgallery.window.WindowPolicy
import dev.pampa.mycloudgallery.window.WindowRegistry
import dev.pampa.mycloudgallery.window.WindowRole
import dev.pampa.mycloudgallery.window.WindowStartRequest
import dev.pampa.mycloudgallery.window.WindowStarts
import javax.inject.Inject

/**
 * L'activity dell'app e l'unico punto d'ingresso dell'utente (launcher, `singleInstancePerTask` nel manifest). Una **finestra** dell'app e' un task di questa activity:
 * al piu' **due** (Fase 10, P4, D16, E-P18), la terza e' rifiutata alla nascita ([WindowRegistry.admit], E-T6).
 *
 * E' una `FragmentActivity` perche' il blocco app usa `BiometricPrompt`, che la richiede (estende `ComponentActivity`, quindi Compose funziona come prima). **Ogni finestra ha
 * il suo `AppLockGate` alla radice** ([AppRoot]) sullo stesso [AppLockController], unico per processo: il blocco vale per il processo, non per la finestra (D17, C434).
 *
 * **L'intent d'ingresso non porta niente** (revisione 2, F5): l'activity e' esportata (launcher), quindi qualsiasi app
 * puo' avviarla con extra scelti da lei, e Navigation Compose li leggerebbe come deep link interni
 * (`android-support-nav:controller:deepLinkIds`/`deepLinkArgs`): per esempio l'attesa dell'approvazione con un codice
 * falso. L'app non ha deep link: prima di `setContent`, e a ogni `onNewIntent`, l'intent si sostituisce con uno pulito. Vale anche per la seconda finestra: parte con l'intent pulito
 * del launcher e la pagina da cui parte e' un **evento interno** ([WindowStarts]), mai un dato nell'intent.
 *
 * **Due azioni sono note** (Fase 9, P10, D17, C151; Fase 10, P3, D9, C433): [NotifyRules.OPEN_ACTION], quella della notifica dei ricordi, e [WidgetTapUri.ACTION], quella del widget.
 * Mentre ripulisce, l'activity le riconosce e le trasforma in un evento interno ([OpenMemoriesRequests]: nessun id, nessun dato; [OpenWidgetRequests]: **solo i due numeri** del tocco, widget e cella)
 * che si serve **dopo il blocco app**; l'intent resta pulito come prima, con extra, dati, categorie e flag buttati. Chi manda l'azione dei Ricordi da un'altra app puo' ottenere al piu' la pagina
 * Ricordi, dietro il blocco; chi manda quella del widget al piu' una foto che il widget mostra gia' sulla Home (D9: i numeri si controllano sul registro locale), oppure la Libreria.
 * **Con due finestre la serve solo la principale** ([WindowRole.servesEntryRequests]): se Android ha consegnato l'intent alla seconda, questa porta davanti la principale
 * ([onNewIntent]) e non ne fa altro (D16: notifiche e widget riaprono la finestra principale).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  /** Unico per processo: lo stesso che RootViewModel alimenta con le impostazioni del blocco. */
  @Inject
  lateinit var lockController: AppLockController

  /** Fase 9 (P10, D17): la richiesta di aprire i Ricordi, che arriva dall'azione fissa della notifica e si serve dopo il blocco app. */
  @Inject
  lateinit var openMemories: OpenMemoriesRequests

  /** Fase 10 (P3, D9): il tocco sul widget (i due numeri), che arriva dall'azione fissa del widget e si serve dopo il blocco app. */
  @Inject
  lateinit var openWidget: OpenWidgetRequests

  /** Fase 10 (P4, D16): le finestre vive del processo (al piu' due). */
  @Inject
  lateinit var windows: WindowRegistry

  /** Fase 10 (P4, D16): la pagina da cui parte una finestra nuova, un evento interno. */
  @Inject
  lateinit var windowStarts: WindowStarts

  private val touchWorkLease = dev.pampa.mycloudgallery.work.UiWorkActivity.budget.lease()

  private val workScroll = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
      source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
      if (consumed != androidx.compose.ui.geometry.Offset.Zero) dev.pampa.mycloudgallery.work.UiWorkActivity.budget.interacted()
      return androidx.compose.ui.geometry.Offset.Zero
    }
  }

  override fun onPause() {
    touchWorkLease.close()
    super.onPause()
  }

  override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
    when (event.actionMasked) {
      android.view.MotionEvent.ACTION_DOWN -> touchWorkLease.set(true)
      android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> touchWorkLease.set(false)
    }
    dev.pampa.mycloudgallery.work.UiWorkActivity.budget.interacted()
    return super.dispatchTouchEvent(event)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    val entry = entryOf(this, intent)
    intent = entry.clean
    super.onCreate(savedInstanceState)
    // D16, E-T6: la terza finestra non nasce, qualunque intent la porti (l'activity e' esportata: un'altra app puo' avviarne molte). Prima di tutto il resto:
    // niente contenuto, niente richiesta dei Ricordi, niente blocco da chiedere in una finestra che sta per sparire.
    val live = WindowLauncher.liveWindowTasks(this)
    val admission = windows.admit(taskId, WindowPolicy.maxWindows(Build.VERSION.SDK_INT), live)
    if (admission is WindowAdmission.Refused) {
      GalleryLog.d(TAG) { "terza finestra rifiutata" }
      windowStarts.cancel()
      WindowLauncher.tellTooMany(this)
      windows.othersThan(taskId, live).firstOrNull()?.let { WindowLauncher.bringToFront(this, it) }
      // Il task che l'intent ha creato per questa finestra sparisce anche dai recenti (con `finish()` restava una scheda vuota).
      finishAndRemoveTask()
      return
    }
    val firstRole = when (admission) {
      is WindowAdmission.Admitted -> admission.role
      is WindowAdmission.Known -> admission.role
      WindowAdmission.Refused -> WindowRole.PRIMARY
    }
    // Solo a un avvio vero: l'intent con cui il sistema ricrea l'activity (rotazione, processo morto e ripreso dai recenti) e' quello di prima, e non riapre i Ricordi.
    if (entry.opensMemories && savedInstanceState == null) openMemories.request()
    // Il tocco sul widget (D9): lo stesso, solo a un avvio vero. Porta i due numeri, mai un id di media.
    if (savedInstanceState == null) entry.widgetTap?.let(openWidget::request)
    // La pagina da cui parte una finestra nuova (D16): solo alla nascita vera, mai a una ricreazione (la pagina si ritrova dallo stato salvato).
    val start = WindowStartRequest(if (savedInstanceState == null) windowStarts.claim(SystemClock.elapsedRealtime()) else null)
    enableEdgeToEdge()
    GalleryLog.d(TAG) { "MainActivity pronta (finestra ${firstRole.name.lowercase()})" }
    val authenticator = BiometricPromptAuthenticator(this)
    val newWindow = AppNewWindowPort(this, windows, windowStarts)
    val myTask = taskId
    setContent {
      MyCloudGalleryTheme {
        val role by remember(myTask) { windows.roleFlow(myTask) }.collectAsState(initial = firstRole)
        // D-3 (prove sul campo): su One UI il cestino del telefono e' quello di Galleria; le schermate lo dicono con le parole giuste.
        CompositionLocalProvider(
          LocalPhoneTrash provides PhoneTrash.of(Build.MANUFACTURER),
          LocalWindowRole provides role,
          LocalWindowStart provides start,
          LocalNewWindow provides newWindow,
        ) {
          // I tag di test come id delle risorse (docs/bench/README.md §6-ter): i selettori del collaudo li trovano per tag
          // (`g1-chrome-*`) invece che per testo. Solo semantica: niente cambia nell'aspetto.
          Box(Modifier.fillMaxSize().nestedScroll(workScroll).semantics { testTagsAsResourceId = true }) {
            AppRoot(lockController = lockController, authenticator = authenticator)
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    val entry = entryOf(this, intent)
    super.onNewIntent(entry.clean)
    setIntent(entry.clean)
    if (entry.opensMemories) openMemories.request()
    entry.widgetTap?.let(openWidget::request)
    // D16: notifiche e widget riaprono la finestra principale. `singleInstancePerTask` consegna l'intent al task piu' recente, che puo' essere la seconda finestra: la richiesta e'
    // un evento del processo e la serve la principale (`LocalWindowRole`), quindi la si porta davanti. Un ingresso senza azione (l'icona) resta dove e' arrivato.
    if (entry.needsPrimaryWindow && windows.roleOf(taskId) == WindowRole.SECONDARY) {
      windows.primary?.let { WindowLauncher.bringToFront(this, it) }
    }
  }

  /**
   * **Esc = Indietro** (Fase 10, P4, D15, E-T10): con una tastiera (DeX, tablet) Esc fa quello che fa il gesto Indietro, in tutta l'app e anche con un foglio aperto: lo stesso
   * percorso del gesto, quindi chiude per primo il livello piu' alto (il foglio, il viewer, la selezione). Qui arriva solo cio' che la finestra non ha gia' consumato: un campo di
   * testo che usa Esc per se' lo tiene. Una sola pressione (non si ripete tenendo premuto) e senza modificatori.
   */
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    dev.pampa.mycloudgallery.work.UiWorkActivity.budget.interacted()
    if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.repeatCount == 0 && event.modifiers == 0) {
      onBackPressedDispatcher.onBackPressed()
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onDestroy() {
    touchWorkLease.close()
    // Una finestra che finisce libera il suo posto; una che si ricrea (densita', lingua, tema) o che il sistema distrugge in secondo piano resta una finestra.
    if (isFinishing) windows.release(taskId)
    super.onDestroy()
  }

  internal companion object {
    private const val TAG = "MainActivity"

    /** L'intent con cui l'app parte dal launcher, e nient'altro: niente extra, dati, flag o componenti di altri. */
    fun launcherIntent(context: Context): Intent =
      Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    /**
     * Cosa fa l'activity di un intent in arrivo: l'intent **ripulito** ([Entry.clean], sempre quello del launcher) e quale richiesta portava ([Entry.opensMemories]).
     * Si guarda **solo l'azione**, per uguaglianza esatta: extra, dati, categorie, flag e componenti non contano e non passano.
     *
     * **Il tocco sul widget** (Fase 10, P3, D9, C433): l'azione [WidgetTapUri.ACTION] si riconosce **prima** che l'intent venga buttato, per uguaglianza esatta, e dall'URI
     * (`mcg-widget:<id>/<cella>`) si leggono i soli due numeri con [WidgetTap.parse], che rifiuta tutto il resto (un intent inventato con quell'azione e un URI che non e'
     * esattamente quella forma non porta nessuna richiesta: l'app si apre come dall'icona). L'evento interno ([Entry.widgetTap]) lo serve **dopo il blocco app** e **solo la
     * finestra principale** (`LocalWindowRole.current.servesEntryRequests`, come `MemoriesNotificationEffect`); `onCreate` e `onNewIntent` lo trattano come `openMemories`
     * (solo a un avvio vero in `onCreate`). Controllare i numeri sul registro e aprire il media e' di `WidgetTaps.resolve` (P13: serve il grafo).
     */
    fun entryOf(context: Context, incoming: Intent?): Entry =
      Entry(launcherIntent(context), opensMemories = incoming?.action == NotifyRules.OPEN_ACTION, widgetTap = widgetTapOf(incoming))

    /** I due numeri del tocco sul widget, se l'intent e' esattamente quello del widget; altrimenti null. Non legge nient'altro dell'intent. */
    private fun widgetTapOf(incoming: Intent?): WidgetTapRequest? =
      if (incoming?.action == WidgetTapUri.ACTION) WidgetTap.parse(incoming.dataString) else null
  }

  /** L'intent pulito, se l'azione era quella dei Ricordi e il tocco sul widget (i soli due numeri) se era quella del widget con un URI valido. */
  internal class Entry(val clean: Intent, val opensMemories: Boolean, val widgetTap: WidgetTapRequest? = null) {
    /** L'ingresso porta una richiesta (un evento interno da servire dopo il blocco), e la serve la finestra principale: i Ricordi, o il tocco sul widget. */
    val needsPrimaryWindow: Boolean get() = opensMemories || widgetTap != null
  }
}
