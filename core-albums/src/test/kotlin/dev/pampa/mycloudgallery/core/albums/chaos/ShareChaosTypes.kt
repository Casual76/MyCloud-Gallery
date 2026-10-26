package dev.pampa.mycloudgallery.core.albums.chaos

import java.util.EnumMap

// IL BANCO DELLE 500 INTERRUZIONI DELLA CONDIVISIONE (Fase 7, P5; docs/fase7-progetto.md §5.5, docs/prova-500.md Parte E).
//
// Stessa impostazione dei banchi delle Fasi 4, 5 e 6 (core-nasops, core-backup, core-ai): un giro e' ripetibile (stesso seme, stessi passi, stessi
// colpi), i colpi cadono PRIMA o DOPO che una chiamata abbia avuto il suo effetto, la morte del processo butta via tutti gli oggetti e si riparte dai
// diari, gli invarianti si controllano dopo ogni morte, a fine passo e in una rilettura finale, e `ShareChaosMutationTest` rompe di proposito il
// codice per vedere che il banco diventi rosso con l'invariante giusto.
//
// Questo file: i tipi di base (momento, colpo, punto, invarianti, morte del processo). Il banco di P4 (`ShareBench`, in `share/`) e' la base: il
// mondo, i legami col motore vero delle operazioni, `violations()` e il riavvio. Qui sopra stanno i colpi, il verificatore piu' fitto, il giro e il
// rapporto.

/** Il momento del colpo rispetto alla chiamata: prima che abbia effetto, o dopo (l'effetto c'e', la risposta si perde). */
enum class Moment(val label: String) {
  BEFORE("prima"),
  AFTER("dopo"),
}

/** Che colpo cade. */
enum class ShareFaultKind(val label: String) {
  /** Wi-Fi perso, Samba riavviato: la chiamata fallisce come un `Timeout`. */
  DROP("connessione caduta"),

  /** `USER_SESSION_DELETED`: la richiesta risponde "sessione scaduta". */
  EXPIRE_SESSION("sessione scaduta"),

  /** Il socket non risponde piu' (E-R14). */
  STALL("socket fermo"),

  /** Lo spazio sul NAS e' finito (E-SH8): `DISK_FULL`, o il rifiuto "spazio" della porta. */
  NO_SPACE("spazio finito"),

  /** Il processo viene ucciso (`kill -9`, memoria, riavvio): niente prosegue, si riparte dai diari. */
  KILL("morte del processo"),
}

/**
 * I punti dove un colpo puo' cadere. Le porte dell'orchestratore, la porta di completamento del motore, i tre diari (condivisione, core-nasops,
 * caricamento) e le chiamate SMB del motore vero. [deathOnly]: sul diario cade solo la morte del processo (un database non "perde la rete").
 */
enum class Surface(val label: String, val deathOnly: Boolean = false) {
  MOVER("spostamento (AlbumMover)"),
  UPLOADER("caricamento dal telefono (AlbumUploader)"),
  TICKETS("biglietti dei trasferimenti"),
  PUBLISHER("pubblicatore degli album"),
  SAFE_COPIES("guardiano (copia privata)"),
  ORIGINS("cartelle d'origine"),
  FACTS("catalogo (fatti dei media)"),

  /** La porta di completamento di core-nasops (D29): il catalogo che collega la destinazione allo stesso media. */
  COMPLETION("porta di completamento"),

  /** Le scritture del diario della condivisione (`album_share_op`, `album_share_item`). */
  SHARE_DIARY("diario della condivisione", deathOnly = true),

  /** Le scritture del diario di core-nasops (`nas_op`, `nas_op_item`): solo con il motore sopra la sessione SMB. */
  OPS_STORE("diario di core-nasops", deathOnly = true),

  /** Le chiamate SMB del motore vero (apri, leggi, scrivi, rinomina, copia lato server...): solo con la sessione SMB. */
  NAS("NAS (chiamate SMB)"),
  ;

  /** Le porte che sono chiamate dall'orchestratore e che si possono colpire senza il motore delle operazioni. */
  val isPort: Boolean get() = this != SHARE_DIARY && this != OPS_STORE && this != NAS && this != COMPLETION
}

/** Un colpo programmato: alla [nth]-esima chiamata a [surface] (contata dal momento in cui si arma), [moment], di tipo [kind]. */
data class Blow(val surface: Surface, val nth: Int, val moment: Moment, val kind: ShareFaultKind) {
  init {
    require(nth >= 1) { "Le chiamate si contano da 1." }
    require(!surface.deathOnly || kind == ShareFaultKind.KILL) { "Sul diario cade solo la morte del processo." }
  }

  override fun toString(): String = "${kind.label} ${moment.label} @ ${surface.label} #$nth"
}

/** Un colpo scattato, per il racconto di un giro che fallisce e per le statistiche. */
data class FiredBlow(val blow: Blow, val applied: Boolean, val detail: String = "") {
  override fun toString(): String = "${blow.surface.label} #${blow.nth} ${blow.kind.label} ${blow.moment.label}${if (detail.isEmpty()) "" else " ($detail)"}"
}

/**
 * Il processo e' morto qui. E' un `Error` apposta: il codice sotto prova (e l'orchestratore, che ferma ogni eccezione delle porte) non deve poterlo
 * trattare come un guasto qualunque. Dopo la morte ogni chiamata fatta dagli oggetti del vecchio processo si ferma con la stessa morte.
 */
class ProcessDeath(val blow: Blow) : Error("morte del processo: ${blow.surface.label} #${blow.nth} ${blow.moment.label}", null, false, false)

/** Un guasto passeggero di una porta (rete, sessione, socket): un'eccezione normale, che il codice sotto prova deve sapere gestire. */
class ChaosFault(val blow: Blow) : RuntimeException("guasto del banco: ${blow.kind.label} (${blow.surface.label} #${blow.nth} ${blow.moment.label})", null, false, false)

/** Gli invarianti del §5.5 (numeri e nomi del documento), piu' due del banco. */
enum class ShareInvariant(val number: Int, val label: String) {
  NO_HALF_FILE(1, "nessun file a meta' visibile"),
  NO_DUPLICATE(2, "nessun doppione"),
  NO_LOSS(3, "nessuna perdita"),
  HASH(4, "hash al 100%"),
  NOTHING_MISSING(5, "nessun elemento mancante in app"),
  NO_ENTRY_WITHOUT_FILE(6, "nessuna voce senza file"),
  NO_ACTION_WITHOUT_CONFIRMATION(7, "nessuna azione senza conferma"),
  REACHES_THE_END(8, "il lavoro arriva in fondo"),
  HIDDEN(9, "nascosti"),

  /** Del banco: un'eccezione uscita dal codice sotto prova per un guasto che non e' del catalogo, o una transizione non ammessa del diario. */
  CONTRACT(10, "il codice non lancia"),

  /** Del banco: una morte senza colpo seguita da una ripresa a vuoto non deve cambiare niente. */
  IDLE(11, "la ripresa a vuoto non cambia niente"),
  ;

  companion object {
    fun of(number: Int): ShareInvariant = entries.firstOrNull { it.number == number } ?: CONTRACT
  }
}

/** Una violazione di un invariante, vista [phase] ("dopo la morte #3", "fine passo 2", "rilettura finale"). */
data class Violation(val invariant: ShareInvariant, val phase: String, val text: String, val defectOfBench: Boolean = false) {
  override fun toString(): String = "[${invariant.number}. ${invariant.label}] $phase: $text${if (defectOfBench) " (difetto del banco)" else ""}"

  companion object {
    /** Da una riga di `ShareBench.violations` ("3: perso (00a1)"). */
    fun fromBench(line: String, phase: String): Violation {
      val number = line.substringBefore(':').trim().toIntOrNull()
      val text = if (number != null) line.substringAfter(':').trim() else line
      return Violation(if (number != null) ShareInvariant.of(number) else ShareInvariant.CONTRACT, phase, text)
    }
  }
}

/** I numeri di un banco: giri, colpi per punto, momento e tipo, morti, passi, verifiche. */
class ShareChaosStats {
  var steps = 0
  var midChecks = 0
  var quiescentChecks = 0
  var restarts = 0
  var idleRestarts = 0
  var filesHashed = 0L
  var bytesHashed = 0L
  var rounds = 0
  var itemsDone = 0
  var itemsFailed = 0
  var itemsCancelled = 0
  var observed = 0L

  val stepKinds: MutableMap<String, Int> = LinkedHashMap()
  private val callsBySurface = EnumMap<Surface, Int>(Surface::class.java)
  private val firedBySurface = EnumMap<Surface, Int>(Surface::class.java)
  private val firedByKind = EnumMap<ShareFaultKind, Int>(ShareFaultKind::class.java)
  private val firedByMoment = EnumMap<Moment, Int>(Moment::class.java)
  private val deathsBySurface = EnumMap<Surface, Int>(Surface::class.java)
  private val plannedBlows = EnumMap<Surface, Int>(Surface::class.java)

  fun planned(blow: Blow) {
    plannedBlows.merge(blow.surface, 1, Int::plus)
  }

  fun called(surface: Surface, n: Int) {
    callsBySurface.merge(surface, n, Int::plus)
  }

  fun fired(blow: Blow) {
    firedBySurface.merge(blow.surface, 1, Int::plus)
    firedByKind.merge(blow.kind, 1, Int::plus)
    firedByMoment.merge(blow.moment, 1, Int::plus)
    if (blow.kind == ShareFaultKind.KILL) deathsBySurface.merge(blow.surface, 1, Int::plus)
  }

  fun stepKind(label: String) {
    stepKinds.merge(label, 1, Int::plus)
  }

  val totalFired: Int get() = firedBySurface.values.sum()
  val totalDeaths: Int get() = deathsBySurface.values.sum()

  fun firedOn(surface: Surface): Int = firedBySurface[surface] ?: 0

  fun firedBy(kind: ShareFaultKind): Int = firedByKind[kind] ?: 0

  fun firedAt(moment: Moment): Int = firedByMoment[moment] ?: 0

  fun callsOn(surface: Surface): Int = callsBySurface[surface] ?: 0

  fun deathsOn(surface: Surface): Int = deathsBySurface[surface] ?: 0

  fun plannedOn(surface: Surface): Int = plannedBlows[surface] ?: 0

  /** La tabella della copertura per punto: chiamate contate, colpi programmati, scattati, di cui morti. */
  fun coverageTable(): String = buildString {
    for (surface in Surface.entries) {
      val calls = callsOn(surface)
      val planned = plannedOn(surface)
      if (calls == 0 && planned == 0) continue
      appendLine("  ${surface.label}: ${if (surface == Surface.SHARE_DIARY || surface == Surface.OPS_STORE) "salvataggi" else "chiamate"} $calls, colpi programmati $planned, scattati ${firedOn(surface)}, di cui morti ${deathsOn(surface)}")
    }
  }
}
