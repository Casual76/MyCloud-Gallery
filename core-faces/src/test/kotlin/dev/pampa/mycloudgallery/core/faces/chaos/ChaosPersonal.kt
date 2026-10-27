package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.FacesClock
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.switch.FacesPrePublishHook
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I DATI PERSONALI NEL BANCO: la voce `faces` con le regole del motore vero (`NasPersonalSync` + `SyncedFacesSettings`: l'ultima scrittura vince, la
// scelta "da datare" prende l'ora del NAS al giro, il gancio di D30 prima di pubblicare la propria testa, le teste lette per intero si ricordano), come
// `WorldPersonal` e `EraseDevice.personalRound` di P8, ma con i colpi: la lettura e la pubblicazione della testa sono chiamate al NAS (rete, sessione,
// stallo: [Surface.PERSONAL]) e ogni salvataggio del registro e' un punto dove puo' cadere la morte del processo ([Surface.REGISTRY]). Le teste
// personali vere (formato, firme) sono di `core-personal`, provate a parte: qui sta in memoria il loro effetto.

/** Le teste personali sul NAS: per dispositivo la voce `faces` (null = la testa non la contiene) e l'ora del NAS in cui e' uscita. */
internal class ChaosHeads {
  private val map = LinkedHashMap<String, HeadValue>()

  fun snapshot(): Map<String, HeadValue> = synchronized(map) { map.toMap() }

  fun put(device: String, head: HeadValue) = synchronized(map) { map[device] = head }

  fun of(device: String): HeadValue? = synchronized(map) { map[device] }
}

internal class ChaosPersonal(
  private val inj: ChaosInjector,
  private val device: Int,
  val deviceId: String,
  private val heads: ChaosHeads,
  private val clock: FacesClock,
  /** L'errore `ANNOUNCE_BEFORE_ERASING`: il gancio di D30 risponde sempre "avanti" senza cancellare niente. */
  private val skipHook: Boolean = false,
) : FacesPersonalSettings {

  // Tutto cio' che segue e' "su disco".
  var dated: PersonalEntry? = null
  var undated: PersonalValue? = null
  var lastPublished: PersonalEntry? = null
  var everPublished = false
  val view = LinkedHashMap<String, HeadValue>()

  private val flow = MutableStateFlow(current())
  override val setting: StateFlow<FacesSetting?> = flow
  override val syncPending = MutableStateFlow(false)

  /** Il gancio risponde "fermo" alle prossime N pubblicazioni (la cancellazione locale che non riesce, come un file bloccato). */
  var holdNext = 0

  override fun current(): FacesSetting? = FacesSettingCodec.parse(undated ?: dated?.value)

  override suspend fun set(setting: FacesSetting): Boolean {
    if (current() == FacesSetting.Unknown) return false
    inj.around(device, Surface.REGISTRY) {
      undated = FacesSettingCodec.valueOf(setting)
      emit()
    }
    return true
  }

  fun emit() {
    flow.value = current()
    syncPending.value = undated != null
  }

  /** Una voce che arriva da fuori (un'app piu' nuova, un dispositivo compromesso): entra nello stato come una voce letta. */
  fun merge(entry: PersonalEntry?) {
    if (entry == null) return
    val mine = dated
    if (mine == null || entry.atNas > mine.atNas || entry.atNas == mine.atNas && entry.author < mine.author) dated = entry
  }

  val headsView: PersonalHeadsView = object : PersonalHeadsView {
    override suspend fun latestValueOf(key: PersonalKey): Map<String, HeadValue> {
      inj.checkAlive(device)
      return if (key == FacesSettingCodec.KEY) synchronized(view) { view.toMap() } else emptyMap()
    }
  }

  /**
   * Un giro dei dati personali: si leggono le teste degli altri (NAS), la scelta fatta qui prende l'ora del NAS, l'ultima scrittura vince, il gancio di D30
   * prima di pubblicare la propria testa, poi lo stato e' fresco. Falso se il NAS non risponde (lettura o pubblicazione): il giro dopo ripete tutto,
   * e pubblicare due volte la stessa testa non cambia niente.
   */
  suspend fun round(online: Boolean, switch: FacesSwitch, onCompleted: () -> Unit): Boolean {
    inj.checkAlive(device)
    if (!online) return false
    val now = clock.nasNowMillis() ?: return false
    val others = try {
      inj.around(device, Surface.PERSONAL) { heads.snapshot() }
    } catch (e: ChaosFault) {
      return false
    }
    // Il salvataggio del registro: la scelta datata, le teste degli altri ricordate, la voce piu' recente. Un salvataggio che non riesce lascia il giro a meta':
    // il giro dopo ripete tutto (come `NasPersonalSync`: il ricordo si scrive prima del segno, mai un segno senza il ricordo).
    try {
      inj.around(device, Surface.REGISTRY) {
        undated?.let { value ->
          // La scelta fatta qui e' piu' nuova di tutto cio' che questo dispositivo sapeva (lo store la data con l'ora del NAS, mai indietro).
          val at = maxOf(now, (dated?.atNas ?: 0L) + 1)
          merge(PersonalEntry(FacesSettingCodec.KEY, at, deviceId, value))
          undated = null
        }
        for ((other, head) in others) {
          if (other == deviceId) continue
          synchronized(view) { view[other] = head }
          merge(head.entry)
        }
        emit()
      }
    } catch (e: ChaosFault) {
      return false
    }
    val state = dated?.let { mapOf(FacesSettingCodec.KEY to it) }.orEmpty()
    val answer = if (holdNext > 0) {
      holdNext--
      PrePublish.Hold("FACES_ERASE_PENDING")
    } else {
      if (skipHook) PrePublish.Proceed else FacesPrePublishHook(switch).beforePublish(state)
    }
    if (answer == PrePublish.Proceed && (!everPublished || lastPublished != dated)) {
      val head = HeadValue(dated, now)
      try {
        inj.around(device, Surface.PERSONAL) { heads.put(deviceId, head) }
      } catch (e: ChaosFault) {
        // Prima: la testa non e' uscita. Dopo: e' uscita, ma qui non si sa (e il giro dopo la ripubblica uguale).
        return false
      }
      try {
        inj.around(device, Surface.REGISTRY) {
          synchronized(view) { view[deviceId] = head }
          lastPublished = dated
          everPublished = true
        }
      } catch (e: ChaosFault) {
        // La testa e' uscita ma qui non si e' segnato: il giro dopo la ripubblica uguale (idempotente).
        return false
      }
    }
    onCompleted()
    return true
  }

  override fun toString(): String = "ChaosPersonal"
}

/** Un errore che il banco non si aspetta dal registro (per i controlli sugli stati impossibili). */
internal class ChaosPersonalError(message: String) : IOException(message)
