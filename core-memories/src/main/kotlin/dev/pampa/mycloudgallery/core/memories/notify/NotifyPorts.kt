package dev.pampa.mycloudgallery.core.memories.notify

import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.NotifyContent
import dev.pampa.mycloudgallery.core.memories.contract.NotifyLedger
import dev.pampa.mycloudgallery.core.memories.contract.NotifySkip
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesPersonalSettings
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// LE PORTE DELLE NOTIFICHE DEI RICORDI (Fase 9, P10, docs/fase9-progetto.md §2.6, D18, D19, D20). `core-memories` decide (policy, giorno, revoca)
// e non conosce Android: il sistema -- il permesso, il canale, la notifica, il lavoro unico di WorkManager -- sta dietro queste porte, che lega
// :app (`MemoryNotifier`, `WorkManagerNotifyScheduler`; il montaggio e' di P13). Ogni porta ha un null-object che non fa niente e un fake in
// `testing/`. **Nessuna porta porta un titolo, un luogo, una data o un nome**: solo id di stringa, numeri e codici (D33).

/**
 * Cosa dice il sistema delle notifiche dei ricordi (D18, E-NT1, E-NT2). **Mai una richiesta**: questo stato si legge e basta. Chi mostra la
 * pagina (P9) lo traduce cosi': [GRANTED] = "Accese"; [PERMISSION_MISSING] = "Consenti" (da un tocco, se Android lo permette ancora, altrimenti
 * le impostazioni di sistema dell'app: lo sa `PermissionAsks`); [APP_OFF] = "Apri le impostazioni di Android" (le notifiche dell'app sono spente
 * dal sistema); [CHANNEL_OFF] = "Spente dalle impostazioni di Android" con il collegamento al canale. Il codice e' per la Diagnostica.
 */
enum class NotifyAccessState(override val code: String) : StableCode {
  GRANTED("granted"),
  PERMISSION_MISSING("permission_missing"),
  APP_OFF("app_off"),
  CHANNEL_OFF("channel_off"),
  ;

  /** Vero se si puo' notificare adesso. */
  val canNotify: Boolean get() = this == GRANTED
}

/** Il permesso e il canale, come li vede QUESTO dispositivo e profilo (E-NT10). Si rilegge con [refresh] (al ritorno da un dialogo o da un'impostazione). */
interface MemoryNotifyAccess {
  val state: StateFlow<NotifyAccessState>

  /** Rilegge dal sistema. Non chiede niente a nessuno. */
  fun refresh()
}

/** Senza porta: niente notifiche e niente richieste (P13 lega quella vera). */
object NoNotifyAccess : MemoryNotifyAccess {
  override val state: StateFlow<NotifyAccessState> = MutableStateFlow(NotifyAccessState.PERMISSION_MISSING)

  override fun refresh() = Unit
}

/** Cio' che si pubblica: [content] e' gia' deciso dalla policy (D16), [day] e' il giorno locale (`aaaammgg`) della notifica. */
data class NotifyNotice(val memory: MemoryId, val day: Int, val mode: LockScreenMode, val content: NotifyContent) {
  override fun toString(): String = "NotifyNotice(${mode.code}, $content)"
}

/** La notifica rimasta in tendina (o che lo e' stata): serve a toglierla quando il ricordo non si puo' piu' mostrare (D19). Solo id, mai testo. */
data class PostedNotice(val memory: MemoryId, val day: Int, val mode: LockScreenMode, val cover: MediaId?) {
  override fun toString(): String = "PostedNotice(${mode.code}, copertina=${cover != null})"
}

/** La notifica vera (canale `memories`, id 1009): la legge e la scrive `MemoryNotifier` di :app. */
interface NotifyPublisher {
  /** Pubblica la notifica di [notice] e se ne ricorda. Falso se il sistema non l'ha accettata (permesso tolto un attimo fa). */
  suspend fun publish(notice: NotifyNotice): Boolean

  /** Toglie la notifica dei ricordi, se c'e'. */
  fun cancel()

  /** La notifica pubblicata e non ancora tolta da noi, o null. */
  suspend fun posted(): PostedNotice?
}

object NoNotifyPublisher : NotifyPublisher {
  override suspend fun publish(notice: NotifyNotice): Boolean = false

  override fun cancel() = Unit

  override suspend fun posted(): PostedNotice? = null
}

/**
 * Il lavoro unico `memories-notify` (D20): [scheduleAt] lo mette (REPLACE) con il ritardo da [now] a [next], tutti e due in **ora locale** (il
 * ritardo vero lo calcola chi sa il fuso: `MemoryNotifyPolicy.delayUntil`); [cancel] lo toglie. Nessun allarme esatto, nessun permesso.
 */
interface NotifyScheduler {
  fun scheduleAt(now: LocalDateTime, next: LocalDateTime)

  fun cancel()
}

object NoNotifyScheduler : NotifyScheduler {
  override fun scheduleAt(now: LocalDateTime, next: LocalDateTime) = Unit

  override fun cancel() = Unit
}

/**
 * Cosa si scrive nella Diagnostica (`NOTIFY_SKIPPED`, `NOTIFY_LATE`, `NOTIFY_REVOKED`): **solo codici e numeri**. P13 lo lega a `feature-diagnostics`.
 */
interface NotifyEvents {
  fun skipped(reason: NotifySkip)

  /** Una notifica consegnata; [late] oltre H+1 h (decide il piano B di D20). */
  fun delivered(late: Boolean)

  /** Una notifica tolta perche' il ricordo non si puo' piu' mostrare. */
  fun revoked()
}

object NoNotifyEvents : NotifyEvents {
  override fun skipped(reason: NotifySkip) = Unit

  override fun delivered(late: Boolean) = Unit

  override fun revoked() = Unit
}

/** "L'utente ha gia' aperto i Ricordi oggi" (D15): niente notifica se e' successo. */
fun interface MemoriesOpened {
  suspend fun openedOn(day: LocalDate): Boolean
}

/**
 * Aperti oggi = qualche ricordo e' stato **visto** oggi (`seen` col giorno locale, D10: "visto" lo scrive solo la pagina del ricordo o la
 * presentazione). Sullo stato "effettivo" del dispositivo: vale anche per una visione fatta su un altro dispositivo dello stesso account e arrivata
 * con la sincronizzazione.
 */
class SeenTodayOpened(private val personal: MemoriesPersonalSettings) : MemoriesOpened {
  override suspend fun openedOn(day: LocalDate): Boolean {
    val today = NotifyLedger.dayOf(day)
    return personal.current().seen.values.any { it == today }
  }
}
