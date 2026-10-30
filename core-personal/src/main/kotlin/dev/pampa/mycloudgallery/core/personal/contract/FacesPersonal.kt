package dev.pampa.mycloudgallery.core.personal.contract

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting

// Fase 8 (docs/fase8-progetto.md §1.2 "core-personal", D3, D13, D29, D30, E-F11, E-F12; formato §5.5-bis A4). Firme di P0:
//  - la voce `faces` di `SETTING` (nessun `PersonalKind` nuovo: Phase7ArchitectureTest) e il suo codec [FacesSettingCodec]; la lettura e
//    la scrittura nell'interruttore sono di P7;
//  - il gancio [PrePublishHook] che `NasPersonalSync` chiama prima di ogni pubblicazione (P8 lo mette nel motore, :app lo lega a
//    `FacesSwitch.reconcile`): un dispositivo pubblica `faces` diversa dalla sua generazione **solo dopo** averne cancellato i dati (D30);
//  - [PersonalHeadsView], la voce che ogni dispositivo dell'account ha nella sua ultima testa letta: la conferma della cancellazione (D30).
// `PersonalKind.PERSON` resta riservato e **non si pubblica mai** (D13): i nomi delle persone stanno nella classe `people` sotto `faces/`.

/**
 * Il codec della voce `faces` (D3): un [PersonalValue.Text] `1|on|<G>|<v>` (attiva, generazione `G` in base32, versione `v` del testo
 * accettato) o `1|off|<G>` (spenta dopo "Disattiva e cancella" della generazione `G`). Voce assente o lapide = spenta ([parse] = null).
 * Ogni altra forma e' [FacesSetting.Unknown] (un'app piu' nuova, E-F12): niente calcolo, niente pubblicazione, **nessuna cancellazione**.
 * Un'app senza la Fase 8 conserva la voce senza capirla (E-F11): e' un testo qualunque di un tipo che conosce.
 */
object FacesSettingCodec {

  /** La chiave della voce: `SETTING`, soggetto `settings`, campo `faces`. */
  val KEY: PersonalKey = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_FACES)

  /** La versione della forma del valore (il primo pezzo). Un'altra versione = [FacesSetting.Unknown]. */
  const val FORM_VERSION: String = "1"

  private const val ON = "on"
  private const val OFF = "off"
  private val TEXT_VERSION = Regex("^[1-9][0-9]{0,8}$")

  fun parse(value: PersonalValue?): FacesSetting? {
    val text = when (value) {
      null, PersonalValue.Removed -> return null
      is PersonalValue.Text -> value.text
      else -> return FacesSetting.Unknown
    }
    val parts = text.split('|')
    if (parts.firstOrNull() != FORM_VERSION) return FacesSetting.Unknown
    return when {
      parts.size == 4 && parts[1] == ON -> {
        val generation = FaceGeneration.parse(parts[2]) ?: return FacesSetting.Unknown
        val version = parts[3].takeIf { TEXT_VERSION.matches(it) }?.toInt() ?: return FacesSetting.Unknown
        FacesSetting.On(generation, version)
      }
      parts.size == 3 && parts[1] == OFF -> FaceGeneration.parse(parts[2])?.let { FacesSetting.Off(it) } ?: FacesSetting.Unknown
      else -> FacesSetting.Unknown
    }
  }

  fun on(generation: FaceGeneration, textVersion: Int): PersonalValue {
    require(textVersion > 0) { "Versione del testo non valida." }
    return PersonalValue.Text("$FORM_VERSION|$ON|${generation.base32}|$textVersion")
  }

  fun off(generation: FaceGeneration): PersonalValue = PersonalValue.Text("$FORM_VERSION|$OFF|${generation.base32}")

  /**
   * La voce `faces` dentro uno stato dei dati personali (la lettura del campo): null se manca o e' una lapide. E' cio' che il gancio
   * [PrePublishHook] passa a `FacesSwitch.reconcile` e che `SyncedFacesSettings` legge dallo stato effettivo.
   */
  fun read(state: Map<PersonalKey, PersonalEntry>): FacesSetting? = parse(state[KEY]?.value)

  /** Il valore di [setting]; [FacesSetting.Unknown] non si scrive mai (non si riscrive cio' che non si capisce). */
  fun valueOf(setting: FacesSetting): PersonalValue = when (setting) {
    is FacesSetting.On -> on(setting.generation, setting.textVersion)
    is FacesSetting.Off -> off(setting.generation)
    FacesSetting.Unknown -> throw IllegalArgumentException("Una voce sconosciuta non si scrive.")
  }
}

/** La risposta del gancio prima della pubblicazione (D30). */
sealed interface PrePublish {
  /** Si puo' pubblicare lo stato. */
  data object Proceed : PrePublish

  /** Questo giro **non pubblica** (si legge soltanto): [code] stabile per la Diagnostica, mai un dato (es. `FACES_ERASE_PENDING`). */
  data class Hold(val code: String) : PrePublish {
    init {
      require(CODE.matches(code)) { "Codice non valido." }
    }
  }

  /**
   * I7 (correzioni della Fase 8): si pubblica lo stato **senza** le voci [keys] (la testa non le contiene: chi la legge non sa niente di loro,
   * come da una testa che non le ha mai avute), con [code] stabile per la Diagnostica. Serve alla voce `faces` quando le persone non si sono
   * potute montare: i dati personali (album personali, preferiti, nascosti) continuano a uscire, ma la testa non dice niente sui volti, quindi
   * non vale mai come conferma di una cancellazione e non dice che questo dispositivo tratti una generazione.
   */
  data class ProceedWithout(val keys: Set<PersonalKey>, val code: String) : PrePublish {
    init {
      require(keys.isNotEmpty()) { "Nessuna voce da togliere." }
      require(CODE.matches(code)) { "Codice non valido." }
    }

    override fun toString(): String = "PrePublish.ProceedWithout(${keys.size}, $code)"
  }

  private companion object {
    val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
  }
}

/**
 * Il gancio che `NasPersonalSync` chiama **prima di ogni pubblicazione** con lo stato che sta per pubblicare (D30, §2.8 "Gli altri
 * dispositivi" punto 2). :app lo lega a `FacesSwitch.reconcile`: se lo stato dice `faces` diversa da `on|<G>` della generazione che questo
 * dispositivo ha in locale, prima si cancellano i dati locali di `G` (chiave per prima) e solo dopo si risponde [PrePublish.Proceed]; se la
 * cancellazione non riesce, [PrePublish.Hold] e quel giro non pubblica. Cosi' una testa personale con `off|G` (o una generazione piu'
 * nuova) **esiste solo** quando i dati di `G` sono cancellati: e' la conferma che legge "Disattiva e cancella" (passo 10).
 * Idempotente, veloce, mai un'eccezione per un caso previsto. Di serie `NoPrePublishHook` (si pubblica sempre).
 */
fun interface PrePublishHook {
  suspend fun beforePublish(state: Map<PersonalKey, PersonalEntry>): PrePublish
}

/**
 * La voce che un dispositivo ha nella sua ultima testa personale letta: [entry] (null = la testa non la contiene) e l'ora del NAS della
 * testa ([headAtNas], "ultimo contatto" nella pagina della cancellazione).
 */
data class HeadValue(val entry: PersonalEntry?, val headAtNas: Long) {
  override fun toString(): String = "HeadValue(${entry != null})"
}

/**
 * Cosa dice ogni dispositivo **dell'account** nella sua ultima testa personale accettata (D30, §2.8 passo 10): per [key], la voce di
 * ognuno (anche di questo). P8 la scrive dentro la sincronizzazione dei dati personali (le teste le legge gia'); "Disattiva e cancella"
 * la usa per dire chi ha cancellato: un dispositivo ha confermato quando la sua testa ha `faces` diversa da `on|<G>`. I dispositivi
 * revocati o persi non si aspettano (L95). Di serie `NoPersonalHeadsView` (nessuna testa nota).
 */
interface PersonalHeadsView {
  suspend fun latestValueOf(key: PersonalKey): Map<String, HeadValue>
}
