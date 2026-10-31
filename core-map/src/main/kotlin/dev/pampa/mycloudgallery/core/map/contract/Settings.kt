package dev.pampa.mycloudgallery.core.map.contract

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// LE IMPOSTAZIONI DELLA MAPPA (docs/fase9-progetto.md §1.2 "Impostazioni", D24, D29; **risposta Q1 (c) del 27/10**). Contratto di P0;
// l'implementazione vera (DataStore in `noBackupFilesDir`, locale, **mai sincronizzata**) e' di P4 (feature-map/.../settings/); il riferimento e'
// `InMemoryMapSettings` (testing/) con la suite `MapSettingsContract`.
//
// **Q1 (c): la mappa online e' accesa di default su ogni dispositivo, senza pagina di consenso.** Resta un interruttore per dispositivo in
// Impostazioni -> Mappa per spegnerla, con una nota breve su cosa esce (la zona guardata, anche quella attorno a una foto aperta sulla mappa, e
// l'indirizzo della connessione; mai le foto, le coordinate esatte ne' quante sono). Il cancello di rete resta per intero (Policy.kt). La conferma costruita da una pagina (`MapOnlineConsent` del progetto) diventa
// [MapOnlineChoice]: non serve piu' per la prima accensione (e' di serie), ma **riaccendere** dopo averla spenta resta un tocco dell'utente sulla
// pagina della mappa, cosi' nessun codice puo' riaccendere da solo cio' che l'utente ha spento (Phase9ArchitectureTest: costruita SOLO in
// `feature-map/.../privacy/MapPrivacy*.kt`). Spegnere non chiede niente.

/** La mappa online su questo dispositivo. */
sealed interface MapOnline {
  /** Accesa; [byDefault] = l'utente non l'ha mai toccata (Q1 (c)). */
  data class On(val byDefault: Boolean) : MapOnline

  /** Spenta dall'utente su questo dispositivo: nessuna richiesta (E-MAP1); restano carta di base, regioni scaricate e cache. */
  data object Off : MapOnline

  /** L'interruttore di build `FeatureFlags.mapOnline` e' spento: la mappa online in questa build non c'e'. */
  data object Unavailable : MapOnline
}

/**
 * La nota breve sulla pagina della mappa (Q1 (c)): la sua versione entra nella scelta, cosi' si sa quale nota l'utente aveva davanti. Versione 2 (correzioni R1, 31/10): la nota dice anche che
 * OpenFreeMap riceve la zona attorno a una foto quando la si apre sulla mappa ("Mostra sulla mappa") o ci si avvicina alle sue foto.
 */
object MapPrivacyNote {
  const val VERSION: Int = 2
}

/**
 * "Riaccendi la mappa dettagliata": la costruisce **solo** la pagina della mappa (`feature-map/.../privacy/MapPrivacy*.kt`) dopo un tocco
 * sull'interruttore, con la versione della nota mostrata ([noteVersion]) e l'ora monotona ([atMs]).
 */
class MapOnlineChoice(val noteVersion: Int, val atMs: Long) {
  override fun toString(): String = "MapOnlineChoice[nota $noteVersion]"
}

/** Una posizione della camera (D29): **solo locale**, mai sincronizzata, tolta dall'azzeramento. Mai nei log. */
data class CameraSpot(val center: GeoE6, val zoom: Double) {
  init {
    require(zoom.isFinite() && zoom in 0.0..MAX_ZOOM) { "Zoom fuori intervallo." }
  }

  override fun toString(): String = "CameraSpot"

  companion object {
    const val MAX_ZOOM: Double = 22.0
  }
}

/** Le regole della camera (D29): la prima volta "tutte le foto" con lo zoom **non oltre 5**; poi l'ultima posizione locale; mai la casa. */
object MapCameraRules {
  const val FIRST_MAX_ZOOM: Double = 5.0
}

/**
 * Cosa non va nel file delle impostazioni della mappa (Fase 9, correzioni R2): la scelta "mappa dettagliata spenta" **fallisce chiusa**, quindi un file che non si
 * legge o che si e' rovinato vale "spenta", e qui si dice perche' (Diagnostica e pagina delle impostazioni della mappa).
 */
enum class MapSettingsProblem {
  /** Tutto bene (o l'interruttore di build e' spento: non c'e' niente da proteggere). */
  NONE,

  /** La lettura e' fallita (errore di I/O): spenta per il processo, la lettura si riprova; `enable` (un tocco dell'utente) la riaccende. */
  UNREADABLE,

  /** Il file era rovinato ed e' stato rifatto: spenta finche' l'utente non la riaccende (la scelta salvata si e' persa). */
  DAMAGED,
}

/** Il "nessun problema" di chi non ha un file da leggere (fake, null-object). */
object NoMapSettingsProblem {
  val flow: StateFlow<MapSettingsProblem> = MutableStateFlow(MapSettingsProblem.NONE).asStateFlow()
}

/** Le impostazioni della mappa di QUESTO dispositivo (e profilo del tablet, D24). */
interface MapSettings {
  /**
   * La mappa online. **Fallisce chiusa** (R2): finche' la prima lettura del file non e' riuscita, e se il file non si legge o si e' rovinato, vale [MapOnline.Off]
   * (o [MapOnline.Unavailable] con l'interruttore di build spento): il cancello e `setConnected` leggono questo valore.
   */
  val online: StateFlow<MapOnline>

  /** Cosa non va nel file ([MapSettingsProblem.NONE] di serie, per chi non ha un file). */
  val problem: StateFlow<MapSettingsProblem> get() = NoMapSettingsProblem.flow

  /** Riaccende la mappa online dopo che l'utente l'ha spenta (con l'interruttore di build spento resta [MapOnline.Unavailable]); toglie anche il problema del file. */
  suspend fun enable(choice: MapOnlineChoice)

  /** Spegne la mappa online qui: subito nessuna richiesta, la cache resta. */
  suspend fun disable()

  /** "Solo sul Wi-Fi": di serie acceso (D24); mai in roaming in ogni caso. */
  val wifiOnly: StateFlow<Boolean>

  suspend fun setWifiOnly(on: Boolean)

  val lastCamera: StateFlow<CameraSpot?>

  suspend fun saveCamera(spot: CameraSpot?)

  /**
   * L'azzeramento (D37, R5): toglie **attraverso le sue API** tutto cio' che e' salvato (scelta, "Solo sul Wi-Fi", camera) e da qui in poi **non scrive piu' niente**
   * (ogni scrittura dopo e' ignorata): cosi' la cartella si toglie da sotto senza che una vecchia camera ritorni sul disco. La mappa online vale "spenta".
   */
  suspend fun clear()
}