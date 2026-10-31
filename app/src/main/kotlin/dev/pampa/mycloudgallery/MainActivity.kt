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
import dev.pampa.mycloudgallery.notify.OpenMemoriesRequests
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
 * **Una sola azione e' nota** (Fase 9, P10, D17, C151): [NotifyRules.OPEN_ACTION], quella della notifica dei ricordi (con P3, D9, anche `OPEN_WIDGET`: il punto e' [entryOf]).
 * Mentre ripulisce, l'activity la riconosce e la trasforma in un evento interno ([OpenMemoriesRequests]: nessun id, nessun dato) che si serve **dopo il blocco app**; l'intent resta pulito
 * come prima, con extra, dati, categorie e flag buttati. Chi manda quell'azione da un'altra app puo' ottenere al piu' la pagina Ricordi, dietro il blocco.
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

  /** Fase 10 (P4, D16): le finestre vive del processo (al piu' due). */
  @Inject
  lateinit var windows: WindowRegistry

  /** Fase 10 (P4, D16): la pagina da cui parte una finestra nuova, un evento interno. */
  @Inject
  lateinit var windowStarts: WindowStarts

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
          Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
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
    if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.repeatCount == 0 && event.modifiers == 0) {
      onBackPressedDispatcher.onBackPressed()
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onDestroy() {
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
     * **PUNTO PER P3 (Fase 10, D9, C433)**: l'azione `OPEN_WIDGET` si aggiunge qui, **prima** che l'intent venga buttato: riconoscerla per uguaglianza esatta con
     * `WidgetTapUri.ACTION`, leggere dall'URI (`mcg-widget:<id>/<cella>`) i soli due numeri con `WidgetTaps.parse` (rifiuta tutto il resto) e metterli in un campo di [Entry]
     * (per esempio `widgetTap`), poi includerlo in [Entry.needsPrimaryWindow]. L'evento interno lo serve **dopo il blocco app** e **solo la finestra principale**
     * (`LocalWindowRole.current.servesEntryRequests`, come `MemoriesNotificationEffect`); `onCreate` e `onNewIntent` lo lasciano come `openMemories` (solo a un avvio vero in `onCreate`).
     */
    fun entryOf(context: Context, incoming: Intent?): Entry =
      Entry(launcherIntent(context), opensMemories = incoming?.action == NotifyRules.OPEN_ACTION)
  }

  /** L'intent pulito e se l'azione era quella dei Ricordi. */
  internal class Entry(val clean: Intent, val opensMemories: Boolean) {
    /**
     * L'ingresso porta una richiesta (un evento interno da servire dopo il blocco), e la serve la finestra principale: oggi i Ricordi; con P3 anche il tocco sul widget
     * (aggiungere qui `|| widgetTap != null`).
     */
    val needsPrimaryWindow: Boolean get() = opensMemories
  }
}
