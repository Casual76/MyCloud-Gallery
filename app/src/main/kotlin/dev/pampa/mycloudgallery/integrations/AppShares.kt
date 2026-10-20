package dev.pampa.mycloudgallery.integrations

import android.content.Context
import android.content.Intent
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.share.ShareHolder
import dev.pampa.mycloudgallery.feature.integrations.share.ShareIntents
import dev.pampa.mycloudgallery.feature.integrations.share.ShareLease
import dev.pampa.mycloudgallery.feature.integrations.share.SharePreparation
import dev.pampa.mycloudgallery.feature.integrations.share.ShareStop
import dev.pampa.mycloudgallery.feature.integrations.share.shareStopText
import dev.pampa.mycloudgallery.feature.library.library.LibraryShare
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMediaActions
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuAction
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupMenuCapabilities
import dev.pampa.mycloudgallery.feature.viewer.menu.BackupShareUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Correzioni 5B (R4, MC-1, MC-2, docs/review/5b-fase5b.md, docs/review/funzionale-5b.md): **ogni** "Condividi" dell'app passa da qui, con o senza il
// backup. Prima solo il viewer con il backup legato passava da `LocationFreeShare`: la selezione della Libreria e il viewer senza backup mandavano gli
// originali del MediaStore, con il GPS, anche con "Rimuovi la posizione" acceso. E la copia preparata si cancellava, con il permesso revocato, quando
// il viewer spegneva il suo avviso dopo 3,2 secondi, prima che WhatsApp o Telegram la leggessero.
//  - Due canali, uno per chi mostra l'avanzamento e il rifiuto: il viewer e la Libreria. Stessa preparazione (`LocationFreeShare`), stessa
//    impostazione, stesso foglio "Condivisione ferma" con "Condividi con la posizione" solo come scelta esplicita.
//  - La scelta dell'app la lancia un'activity viva (`ShareHostEffect`, alla radice della galleria, anche senza backup).
//  - Spegnere l'avviso e' solo lo stato ([Channel.hideNotice]); la copia consegnata passa al custode (`ShareHolder`), che la rilascia quando l'altra
//    app ha finito: al ritorno dell'utente nell'app (con un minuto di margine), comunque dopo mezz'ora, e all'avvio successivo.

/** Chi ha chiesto la condivisione: chi ne mostra l'avanzamento e il rifiuto. */
enum class ShareOrigin { VIEWER, LIBRARY }

/** La scelta dell'app da lanciare. La copia passa al custode solo se la scelta parte davvero. */
class ShareLaunch internal constructor(val intent: Intent, val origin: ShareOrigin, internal val lease: ShareLease, val serial: Long) {
  override fun toString(): String = "ShareLaunch(${origin.name}, #$serial)"
}

/**
 * Le condivisioni dell'app. Una per processo (la tiene `IntegrationsGraph`).
 *
 * @param prepare la preparazione (`LocationFreeShare.prepare`): copia, togli la posizione, ricontrolla.
 * @param removeLocation "Rimuovi la posizione" (Impostazioni → Condivisioni).
 */
class AppShares(
  private val context: Context,
  private val prepare: suspend (List<MediaId>, Boolean, (Int, Int) -> Unit) -> SharePreparation,
  val removeLocation: StateFlow<Boolean>,
  private val holder: ShareHolder,
  private val scope: CoroutineScope,
) {

  private val launchFlow = MutableStateFlow<ShareLaunch?>(null)

  /** La scelta dell'app da lanciare: la lancia l'activity (`ShareHostEffect`), una volta, e risponde con [launched]. */
  val launches: StateFlow<ShareLaunch?> = launchFlow.asStateFlow()

  private var serial = 0L

  private fun next(): Long = synchronized(this) { ++serial }

  val viewer: Channel = Channel(ShareOrigin.VIEWER)

  val library: Channel = Channel(ShareOrigin.LIBRARY)

  /** Lo stato della condivisione della Libreria nei termini di feature-library. */
  val libraryProgress: StateFlow<LibraryShare> = library.state.map { state ->
    when (state) {
      is BackupShareUi.Preparing -> LibraryShare.Preparing(state.done, state.total)
      is BackupShareUi.Refused -> LibraryShare.Refused(state.message, state.canShareWithLocation)
      BackupShareUi.Idle, is BackupShareUi.Ready -> LibraryShare.Idle
    }
  }.stateIn(scope, SharingStarted.Eagerly, LibraryShare.Idle)

  /**
   * L'activity ha lanciato (o non ha potuto lanciare) [launch]. Partita: la copia passa al custode, che la tiene finche' l'altra app non ha finito.
   * Non partita: la copia si toglie subito.
   */
  fun launched(launch: ShareLaunch, ok: Boolean) {
    launchFlow.compareAndSet(launch, null)
    if (ok) {
      holder.hold(launch.lease)
    } else {
      launch.lease.release()
      channel(launch.origin).idle()
    }
  }

  /** Un'activity dell'app va in pausa: l'utente e' uscito (la scelta dell'app, l'altra app). */
  fun appLeft() = holder.appLeft()

  /** Un'activity dell'app torna davanti: le copie consegnate prima si rilasciano dopo il margine. */
  fun appReturned() = holder.appReturned()

  /** L'azzeramento: niente in preparazione, niente consegnato. */
  fun releaseAll() {
    viewer.cancel()
    library.cancel()
    holder.releaseAll()
  }

  private fun channel(origin: ShareOrigin): Channel = if (origin == ShareOrigin.VIEWER) viewer else library

  /** Una condivisione per chi la mostra: lo stato, la preparazione in corso, l'ultima scelta (per "Condividi con la posizione"). */
  inner class Channel internal constructor(private val origin: ShareOrigin) {

    private val stateFlow = MutableStateFlow<BackupShareUi>(BackupShareUi.Idle)

    val state: StateFlow<BackupShareUi> = stateFlow.asStateFlow()

    private var job: Job? = null
    private var last: List<MediaId> = emptyList()

    /** "Condividi": secondo l'impostazione di adesso. */
    fun start(ids: List<MediaId>) = start(ids, removeLocation.value)

    /** "Condividi con la posizione": la scelta esplicita dell'utente dopo un rifiuto. */
    fun shareWithLocation() {
      if (last.isNotEmpty()) start(last, removeLocation = false)
    }

    /**
     * "Annulla" durante la preparazione, la chiusura del foglio di un rifiuto: la preparazione si ferma (le copie fatte si tolgono) e una copia
     * pronta ma non ancora consegnata si toglie. Una copia gia' consegnata all'altra app **non** si tocca: e' del custode.
     */
    fun cancel() {
      job?.cancel()
      dropPendingLaunch()
      stateFlow.value = BackupShareUi.Idle
    }

    /** L'avviso "Pronta" che si spegne: solo lo stato (MC-1). */
    fun hideNotice() {
      if (stateFlow.value is BackupShareUi.Ready) stateFlow.value = BackupShareUi.Idle
    }

    internal fun idle() {
      stateFlow.value = BackupShareUi.Idle
    }

    private fun start(ids: List<MediaId>, removeLocation: Boolean) {
      job?.cancel()
      dropPendingLaunch()
      last = ids
      stateFlow.value = BackupShareUi.Preparing(0, ids.size, removeLocation)
      job = scope.launch {
        val prepared = prepare(ids, removeLocation) { done, total -> stateFlow.value = BackupShareUi.Preparing(done, total, removeLocation) }
        when (prepared) {
          is SharePreparation.Ready -> {
            // Annullata mentre finiva: la copia non parte.
            if (!currentCoroutineContext().isActive) {
              prepared.lease.release()
              return@launch
            }
            stateFlow.value = BackupShareUi.Ready(verified = prepared.stripped, direct = prepared.files.size - prepared.stripped)
            launchFlow.value = ShareLaunch(Intent.createChooser(ShareIntents.send(prepared.files), null), origin, prepared.lease, next())
          }
          is SharePreparation.Stopped -> stateFlow.value = BackupShareUi.Refused(
            message = shareStopText(context, prepared.stop),
            // L41: "Condividi con la posizione" solo se e' la posizione che non si toglie, e solo come scelta esplicita dell'utente.
            canShareWithLocation = removeLocation && prepared.stop in WITH_LOCATION_CHOICE,
          )
        }
      }
    }

    /** Una scelta dell'app di questo canale non ancora lanciata: non partira' piu', e la sua copia si toglie. */
    private fun dropPendingLaunch() {
      val pending = launchFlow.value ?: return
      if (pending.origin == origin && launchFlow.compareAndSet(pending, null)) pending.lease.release()
    }
  }

  /**
   * Le voci del viewer quando il backup non c'e' (nessun NAS, variante di misura): solo "Condividi", che rispetta "Rimuovi la posizione" come con
   * il backup (R4). Nessun'altra voce compare.
   */
  fun viewerActions(): BackupMediaActions = object : BackupMediaActions {
    override val capabilities: BackupMenuCapabilities = BackupMenuCapabilities(share = true)

    override fun perform(action: BackupMenuAction, id: MediaId) = Unit

    override val removeLocation: StateFlow<Boolean> get() = this@AppShares.removeLocation

    override val shareState: StateFlow<BackupShareUi> get() = viewer.state

    override fun share(ids: List<MediaId>) = viewer.start(ids)

    override fun shareWithLocation() = viewer.shareWithLocation()

    override fun dismissShare() = viewer.cancel()

    override fun hideShareNotice() = viewer.hideNotice()
  }

  private companion object {
    /** Le fermate per cui ha senso offrire "Condividi con la posizione" (la posizione non si toglie da questo file). */
    val WITH_LOCATION_CHOICE = setOf(ShareStop.CANNOT_REMOVE_LOCATION, ShareStop.LOCATION_REMAINS)
  }
}
