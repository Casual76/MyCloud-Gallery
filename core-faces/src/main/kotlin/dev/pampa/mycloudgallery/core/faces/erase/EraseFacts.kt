package dev.pampa.mycloudgallery.core.faces.erase

import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseCheck
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.trust.codec.TextRules
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import java.security.MessageDigest

// LE REGOLE PURE DI "DISATTIVA E CANCELLA" (Fase 8, P8; docs/fase8-progetto.md §2.8 passi 4 e 10, D30, D31): chi ha confermato, cosa dice
// l'anteprima, quali nomi togliere dalla cronologia, come leggere gli esiti della cancellazione sul NAS di core-nasops (che core-faces non vede:
// `:app` gli passa i codici). Nessun effetto, nessun orologio.

object EraseRules {

  /**
   * D30: la testa personale di un dispositivo **prova** che ha cancellato le generazioni [erased] solo se la sua voce `faces` lo dice in modo
   * esplicito **e** non e' piu' vecchia della voce `off` di questa cancellazione ([offAtNas], l'ora del NAS con cui e' uscita): spenta (di qualunque
   * generazione: l'account e' spento) o accesa con una generazione che non e' fra quelle cancellate. Il gancio di D30 cancella i dati locali
   * davanti a una voce cosi', quindi una testa cosi' esce solo dopo la cancellazione. Una testa senza la voce, con una forma sconosciuta, ancora
   * `on` di una generazione cancellata, o piu' vecchia della voce `off` (un dispositivo che non l'ha ancora letta, e che nel frattempo potrebbe
   * aver ricevuto i dati) non prova niente. Senza [offAtNas] (la voce non e' ancora uscita) nessuno ha confermato.
   */
  fun confirms(value: HeadValue?, erased: Collection<FaceGeneration>, offAtNas: Long?): Boolean {
    val entry = value?.entry ?: return false
    if (offAtNas == null || entry.atNas < offAtNas) return false
    return when (val setting = FacesSettingCodec.parse(entry.value)) {
      is FacesSetting.Off -> true
      is FacesSetting.On -> setting.generation !in erased
      FacesSetting.Unknown, null -> false
    }
  }

  /**
   * La propria testa dice che la voce `off` e' uscita (passo 7): la voce della testa e' quella che questo dispositivo ha adesso ([current]: niente
   * di piu' nuovo in attesa) ed e' spenta, o accesa con una generazione che non e' fra quelle cancellate (l'account e' gia' andato avanti).
   * Restituisce l'ora del NAS di quella voce (da li' si contano le conferme degli altri), o null.
   */
  fun announcedAt(own: HeadValue?, erased: Collection<FaceGeneration>, current: FacesSetting?): Long? {
    val entry = own?.entry ?: return null
    val published = FacesSettingCodec.parse(entry.value) ?: return null
    if (published != current) return null
    return when (published) {
      is FacesSetting.Off -> entry.atNas
      is FacesSetting.On -> entry.atNas.takeIf { published.generation !in erased }
      FacesSetting.Unknown -> null
    }
  }

  /**
   * I dispositivi dell'account di [me] nel registro [registry], con cosa dicono le loro teste ([heads]): approvati e dismessi si aspettano
   * (confermato o in attesa, con l'ultimo contatto), revocati e persi no (L95). Questo dispositivo e' fra loro. Nessun dispositivo di un altro
   * account (Q3).
   */
  fun acks(registry: RegistryState, me: String, heads: Map<String, HeadValue>, erased: Collection<FaceGeneration>, offAtNas: Long?): List<DeviceAck> {
    val account = registry.registry.device(me)?.nasUser ?: return emptyList()
    return registry.registry.devices
      .filter { TextRules.sameAccount(it.nasUser, account) }
      .sortedWith(compareBy({ it.id != me }, { it.name }, { it.id }))
      .map { device ->
        val head = heads[device.id]
        val state = when {
          device.status.isBarred -> AckState.NOT_EXPECTED
          confirms(head, erased, offAtNas) -> AckState.CONFIRMED
          else -> AckState.WAITING
        }
        DeviceAck(device.name, device.id, state, head?.headAtNas, thisDevice = device.id == me, dismissed = device.status == DeviceStatus.RETIRED)
      }
  }

  /**
   * E-F9 (due attivazioni quasi insieme): una generazione che la testa personale **recente** (meno di [windowMillis] d'ora del NAS) di un
   * dispositivo dell'account dice ancora accesa non si toglie dal NAS come "non corrente": la voce che vince non e' ancora arrivata a tutti, e la
   * generazione potrebbe essere proprio quella che vince. Una testa vecchia (un dispositivo spento da giorni) non trattiene niente: la pulizia
   * arriva comunque. Senza l'ora del NAS ogni testa conta come recente (nel dubbio non si toglie).
   */
  fun recentlyClaimed(heads: Map<String, HeadValue>, generation: FaceGeneration, nasNow: Long?, windowMillis: Long = CLAIM_WINDOW_MILLIS): Boolean =
    heads.values.any { head ->
      val setting = FacesSettingCodec.parse(head.entry?.value)
      setting is FacesSetting.On && setting.generation == generation && (nasNow == null || nasNow - head.headAtNas < windowMillis)
    }

  /** Quanto una testa personale che dice "accesa" trattiene la sua generazione dalla pulizia (E-F9): un'ora. */
  const val CLAIM_WINDOW_MILLIS: Long = 60L * 60 * 1000

  /** I dispositivi revocati o persi **dell'account** di [me] (D31 (b): solo loro, mai quelli di un altro account). */
  fun barredOfAccount(registry: RegistryState, me: String): List<String> {
    val account = registry.registry.device(me)?.nasUser ?: return emptyList()
    return registry.registry.devices.filter { it.status.isBarred && TextRules.sameAccount(it.nasUser, account) }.map { it.id }.sorted()
  }

  /** Vero se [deviceId] e' un dispositivo dell'account di [me] revocato o perso. */
  fun revokedOrLost(registry: RegistryState, me: String, deviceId: String): Boolean = deviceId in barredOfAccount(registry, me)

  /** Tutti i nomi che le voci `people` nominano (anche di persone unite o dimenticate dopo): la cronologia li perde tutti (passo 4). */
  fun names(records: Collection<PeopleRecord>): Set<String> = records.mapNotNull { (it.edit as? PeopleEdit.Name)?.name }.toSet()

  /**
   * Tutte le persone che le voci `people` nominano come soggetto di un nome, di un "nascondi", di un "dimentica", di un'unione o di una divisione (R2,
   * correzioni D): la cronologia le ricorda per id e le toglie anche quando il nome di prima non si ritrova piu'. Solo id casuali, mai nomi.
   */
  fun persons(records: Collection<PeopleRecord>): Set<PersonId> {
    val out = LinkedHashSet<PersonId>()
    for (record in records) {
      when (val edit = record.edit) {
        is PeopleEdit.Name -> out += edit.person
        is PeopleEdit.Hidden -> out += edit.person
        is PeopleEdit.Forget -> out += edit.person
        is PeopleEdit.Alias -> {
          out += edit.person
          out += edit.into
        }
        is PeopleEdit.Born -> {
          out += edit.person
          out += edit.splitFrom
        }
        else -> Unit
      }
    }
    return out
  }

  /** Le persone con un nome che l'utente vede (non unite in un'altra, non dimenticate): "Perderai i nomi di N persone". */
  fun namedPeople(records: Collection<PeopleRecord>): Int {
    val state = PeopleState.of(records)
    return state.names.keys.count { it !in state.aliases.keys }
  }

  /**
   * L'impronta dell'anteprima (la conferma la porta): generazione, numeri e dispositivi attesi. **Non** l'ultimo contatto ne' lo stato dei
   * dispositivi (un dispositivo che si fa vivo non deve far scadere la conferma).
   */
  fun digest(generation: FaceGeneration, namedPeople: Int, albums: Int, historyWithNames: Boolean, localOnly: Boolean, devices: List<DeviceAck>): Long {
    val text = buildString {
      append("mcg-faces-erase-preview\u0000").append(generation.base32).append('\u0000')
      append(namedPeople).append('\u0000').append(albums).append('\u0000').append(historyWithNames).append('\u0000').append(localOnly)
      for (d in devices.sortedBy { it.deviceId }) append('\u0000').append(d.deviceId).append(':').append(d.state == AckState.NOT_EXPECTED)
    }
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return bytes.take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
  }
}

/**
 * Gli esiti della cancellazione stretta di core-nasops (`FacesNasEraser`) come li vede core-faces, per il legame di `:app` (P13): `:app` passa il
 * **codice** dell'arresto (`FacesEraseStop.code`) e i numeri, e riceve il [NasEraseRound] da dare a `FacesEraseOps`. Cosi' la tabella sta in un
 * posto solo, provato, e nessuno la riscrive a mano.
 */
object FacesEraseCodes {

  /** Un giro: `done` = finito; `budget` = altro al giro dopo; i blocchi di D35 = attesa con il motivo; `not_allowed`/`not_private_area` = mai. */
  fun round(stopCode: String, removedFiles: Int, remainingFiles: Int?): NasEraseRound = when (stopCode) {
    "done" -> NasEraseRound.Done(removedFiles)
    "budget" -> NasEraseRound.More(removedFiles, remainingFiles)
    "not_allowed", "not_private_area" -> NasEraseRound.NotAllowed
    "unsure" -> NasEraseRound.Unsure
    else -> NasEraseRound.Waiting(waitOf(stopCode))
  }

  /** Il motivo dell'attesa per un arresto di core-nasops (un codice sconosciuto o la recinzione sono "il NAS"). */
  fun waitOf(stopCode: String): EraseWait = when (stopCode) {
    "frozen" -> EraseWait.FROZEN
    "emergency_stop" -> EraseWait.EMERGENCY_STOP
    "clock_jump" -> EraseWait.CLOCK_JUMP
    "read_only" -> EraseWait.READ_ONLY
    "not_trusted" -> EraseWait.NOT_TRUSTED
    else -> EraseWait.NAS
  }

  /** La verifica: null se si e' fermata (NAS, area). */
  fun check(stopped: Boolean, generationPresent: Boolean, recycleBinCopy: Boolean): NasEraseCheck? =
    if (stopped) null else NasEraseCheck(generationPresent, recycleBinCopy)
}
