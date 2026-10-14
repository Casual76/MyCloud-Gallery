package dev.pampa.mycloudgallery.core.common.diagnostics

/**
 * Il codice stabile di una voce della Diagnostica (es. `NET_FOREIGN_VPN`, `NAS_AUTH_BLOCKED`).
 *
 * **Un valore e non un enum, di proposito.** Ogni modulo (rete, NAS, famiglia, salute del background)
 * definisce i propri codici accanto al proprio contributo, senza toccare questo file: con un enum unico qui
 * dentro otto pacchetti in parallelo si contenderebbero la stessa riga. Il catalogo completo lo raccoglie
 * `feature-diagnostics` (P8), con un test che verifica che ogni edge case di Fase 2 che produce un avviso
 * abbia il suo codice: per questo ogni codice dichiara gli [edgeCases] che copre (es. `E-S16`, `E-R11`).
 *
 * Il codice finisce nell'esportazione cosi' com'e': e' scritto nel sorgente, mai costruito da un dato.
 */
class DiagCode(val id: String, val edgeCases: Set<String> = emptySet()) {

  init {
    require(ID.matches(id)) { "codice di diagnostica non valido: deve essere MAIUSCOLO_CON_TRATTINI_BASSI" }
    require(edgeCases.all { EDGE_CASE.matches(it) }) { "riferimento a un edge case non valido" }
  }

  override fun equals(other: Any?): Boolean = other is DiagCode && other.id == id

  override fun hashCode(): Int = id.hashCode()

  override fun toString(): String = id

  companion object {
    internal val ID = Regex("[A-Z][A-Z0-9_]{2,63}")

    /** `E-R8`, `E-S16`, `E-AL3`, `C13`, `L11`, `T-17`: gli identificativi del piano e di sicurezza.md. */
    private val EDGE_CASE = Regex("[A-Z]{1,2}(-[A-Z]{1,3})?-?[0-9]{1,3}")
  }
}

/** Le sezioni della Diagnostica, nell'ordine in cui la schermata le mostra (§2.6). */
enum class DiagSection {
  /** Luogo, rete sottostante, VPN, rete locale, IPv6; sessione, dialetto, cifratura, interruttore, GUID. */
  NETWORK_AND_NAS,

  /** Il test di connessione per fasi, con i tempi. */
  CONNECTION_TEST,

  /** Perche' le attivita' girano o aspettano (motore delle regole, WorkManager). */
  ACTIVITIES,

  /** Gli ultimi esiti ed errori ([DiagnosticsLog]). */
  RECENT_EVENTS,

  /** Spazio occupato: database, cache, `noBackupFilesDir`. */
  STORAGE,

  /**
   * La libreria del telefono (Fase 3, §2.1): accesso ai media (completo, parziale, nessuno) e alla posizione dei
   * media, volumi, stato della sincronizzazione, elementi ignorati e foto in attesa (E-P21), indice e luoghi.
   * Solo numeri ed enumerati: mai nomi di file, percorsi, coordinate o luoghi (`PhoneLibraryDiagnostics`, P2).
   */
  PHONE_LIBRARY,

  /**
   * Il NAS nella Libreria (Fase 4, `docs/fase4-progetto.md` §2.2, §2.3): scansione, congelamento (D10), indice
   * condiviso (teste, segmenti rifiutati, outbox, pubblicazione solo locale D31), replica e qualita', preparazione,
   * salti dell'ora del NAS (D32). Solo codici e numeri: mai percorsi, nomi di file, share, account o id di media.
   */
  NAS_LIBRARY,

  /** I consumi del mese (TrafficLedger). */
  MONTHLY_TRAFFIC,

  /** Avvisi Android: batteria, bucket, ibernazione, permessi, restrizioni, riavvii, uscite del processo. */
  ANDROID_WARNINGS,

  /** Registro, stato del dispositivo, firme scartate, allarmi. */
  TRUST,

  /** Profili Android del tablet (E-P19). */
  PROFILES,
}

/** Quanto conta una voce. L'ordine e' crescente, cosi' si confronta con `>=`. */
enum class DiagSeverity { OK, INFO, WARNING, ERROR }

/**
 * Cosa si puo' fare da una voce ("Apri WireGuard", "Inserisci di nuovo la password"). Un valore aperto per
 * lo stesso motivo di [DiagCode]: le azioni le propongono i moduli, le esegue `feature-diagnostics` (o :app)
 * riconoscendo l'[id]. Un'azione sconosciuta si mostra senza tasto.
 */
@JvmInline
value class DiagAction(val id: String) {
  companion object {
    val OPEN_WIREGUARD = DiagAction("open_wireguard")
    val OPEN_BATTERY_SETTINGS = DiagAction("open_battery_settings")
    val OPEN_APP_DETAILS = DiagAction("open_app_details")
    val OPEN_NOTIFICATION_SETTINGS = DiagAction("open_notification_settings")
    val RUN_CONNECTION_TEST = DiagAction("run_connection_test")
    val REENTER_NAS_PASSWORD = DiagAction("reenter_nas_password")
    val OPEN_DEVICES = DiagAction("open_devices")
    val RESUME_ONBOARDING = DiagAction("resume_onboarding")
  }
}

/**
 * Un testo per lo SCHERMO della Diagnostica: una risorsa stringa con i suoi argomenti. Puo' contenere dati
 * personali (sullo schermo del proprio telefono vanno bene: "GUID memorizzato: …"), per questo **non entra
 * mai nell'esportazione**, che usa solo [DiagItem.code], la sezione, la gravita' e [DiagItem.export].
 */
class DiagText(val resId: Int, val args: List<Any> = emptyList()) {
  override fun toString(): String = "DiagText($resId, ${args.size} argomenti)"
}

/**
 * Una voce della Diagnostica.
 *
 * @property titleRes la frase breve, risorsa del modulo che contribuisce.
 * @property detail il dettaglio a schermo (mai esportato).
 * @property export i valori esportabili, con chiavi scritte nel codice (`dialect`, `encrypted`, `account`…).
 */
class DiagItem(
  val code: DiagCode,
  val section: DiagSection,
  val severity: DiagSeverity,
  val titleRes: Int,
  val detail: DiagText? = null,
  val action: DiagAction? = null,
  val export: Map<String, ExportValue> = emptyMap(),
) {
  init {
    require(export.keys.all { EXPORT_KEY.matches(it) }) { "chiave di esportazione non valida" }
  }

  override fun toString(): String = "DiagItem($code, $section, $severity)"

  companion object {
    /** Chiavi brevi, in camelCase o con punti: sono parte del formato dell'esportazione. */
    val EXPORT_KEY = Regex("[a-z][A-Za-z0-9_.]{0,47}")
  }
}

/**
 * Un modulo che contribuisce alla Diagnostica (rete, NAS, famiglia, salute del background). `:app` li
 * raccoglie in un multibinding `Set<DiagnosticsContributor>` e `feature-diagnostics` li interroga.
 *
 * [collect] non deve toccare i dischi del NAS (E-R15): legge stati gia' in memoria o, al piu', fa una sonda
 * TCP. Il test di connessione vero si lancia a parte, su richiesta dell'utente.
 */
interface DiagnosticsContributor {
  val section: DiagSection

  suspend fun collect(): List<DiagItem>
}
