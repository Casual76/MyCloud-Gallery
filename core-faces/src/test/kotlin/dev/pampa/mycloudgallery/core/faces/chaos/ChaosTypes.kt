package dev.pampa.mycloudgallery.core.faces.chaos

import java.io.IOException
import java.util.EnumMap
import java.util.TreeMap

// IL BANCO DELLE INTERRUZIONI DEL CALCOLO E DI "DISATTIVA E CANCELLA" (Fase 8, P12; docs/fase8-progetto.md §5.5, riga P12 del §4.4;
// come si legge: docs/prova-500.md Parte F). Qui i tipi comuni: dove cade un colpo, di che tipo e' e quando, e le statistiche del banco.
// Solo identita' sintetiche e nomi inventati: nessun dato di persone vere.

/** Dove cade un colpo: una chiamata al NAS, un salvataggio durevole o un cambio di generazione. */
internal enum class Surface(val label: String) {
  /** Ogni richiesta del dispositivo alla share privata (elenco, lettura, pubblicazione; sul simulatore SMB, ogni richiesta SMB). */
  NAS("chiamata al NAS"),

  /** Le porte della cancellazione sul NAS (solo sul NAS finto: sul simulatore SMB sono richieste SMB come le altre). */
  ERASE_OPS("cancellazione sul NAS"),

  /** La lettura e la pubblicazione della testa dei dati personali. */
  PERSONAL("dati personali sul NAS"),

  /** Il database dei volti: un corpo `faces` salvato per contenuto. */
  DB_BODY("database: corpo dei volti"),

  /** Il database dei volti: l'istantanea del raggruppamento. */
  DB_GROUPING("database: istantanea"),

  /** Il database dei volti: nomi e correzioni. */
  DB_PEOPLE("database: persone"),

  /** Il database dei volti: le unita' di calcolo finite. */
  DB_UNITS("database: unita' finite"),

  /** L'outbox della pubblicazione (stato del pubblicatore). */
  OUTBOX("outbox"),

  /** Lo stato della replica (teste e segmenti letti degli altri dispositivi). */
  REPLICA("stato della replica"),

  /** Il diario di "Disattiva e cancella" (`faces-erase`). */
  JOURNAL("diario della cancellazione"),

  /** Il registro dei dati personali: la voce `faces` e le teste ricordate. */
  REGISTRY("registro dei dati personali"),

  /** Lo stato locale `faces-local`. */
  LOCAL("stato locale"),

  /** Il cambio di generazione a meta' di un lavoro (non e' un guasto: e' un evento). */
  FLIP("cambio di generazione"),
  ;

  /** Le superfici dove cade solo la morte del processo o un errore di scrittura: salvataggi durevoli. */
  val isStore: Boolean get() = this in STORES

  companion object {
    val STORES: Set<Surface> = setOf(DB_BODY, DB_GROUPING, DB_PEOPLE, DB_UNITS, OUTBOX, REPLICA, JOURNAL, REGISTRY, LOCAL)
  }
}

/** Quando cade il colpo rispetto alla chiamata: prima che abbia effetto, o dopo (l'effetto c'e', la risposta si perde). */
internal enum class Moment(val label: String) { BEFORE("prima"), AFTER("dopo") }

/** Che cosa succede. */
internal enum class FaultKind(val label: String) {
  /** La connessione cade (rete). */
  DROP("connessione caduta"),

  /** La sessione scade. */
  EXPIRE_SESSION("sessione scaduta"),

  /** Il socket non risponde piu' (stallo): passa del tempo prima dell'errore. */
  STALL("stallo"),

  /** Lo spazio sul NAS finisce (solo dove una chiamata occupa spazio). */
  NO_SPACE("spazio finito"),

  /** La morte del processo: niente prosegue, si riparte da cio' che e' su disco. */
  KILL("morte del processo"),

  /** Un salvataggio locale che non riesce (disco pieno, database bloccato): un errore, non la morte. */
  IO_FAIL("salvataggio fallito"),

  /** Non un guasto: la generazione cambia a meta' del lavoro (cancellazione, esclusione, nuova attivazione). */
  FLIP("cambio di generazione"),
}

/** Un colpo: al dispositivo [device] (indice), sulla [surface], alla chiamata [nth] (da 1), nel momento [moment]. */
internal data class Blow(
  val device: Int,
  val surface: Surface,
  val nth: Int,
  val moment: Moment,
  val kind: FaultKind,
  /** Per [FaultKind.FLIP]: quale cambio (0 = chi lavora conferma "Disattiva e cancella", 1 = lo esclude, 2 = un altro dispositivo cancella). */
  val flip: Int = 0,
) {
  override fun toString(): String = "d$device ${surface.label} #$nth ${moment.label}: ${kind.label}${if (kind == FaultKind.FLIP) " ($flip)" else ""}"
}

/** Un colpo scattato: [applied] = l'effetto c'era gia' (colpo "dopo"). */
internal class FiredBlow(val blow: Blow, val detail: String = "") {
  override fun toString(): String = "$blow${if (detail.isEmpty()) "" else " [$detail]"}"
}

/** Un guasto passeggero del NAS o di un file: un `IOException`, come lo vede il codice sotto prova. */
internal class ChaosFault(val blow: Blow) : IOException("guasto del banco: ${blow.kind.label}")

/** Un salvataggio nel database che non riesce (disco pieno, database bloccato): come SQLite, un errore di esecuzione e non un `IOException`. */
internal class ChaosDbFault(val blow: Blow) : RuntimeException("guasto del banco: ${blow.kind.label}")

/** Le statistiche di un banco: quanti colpi programmati, chiamate viste e colpi scattati, per superficie, tipo e momento. */
internal class ChaosStats {
  private val plannedBy = EnumMap<Surface, Int>(Surface::class.java)
  private val calledBy = EnumMap<Surface, Int>(Surface::class.java)
  private val firedBy = EnumMap<Surface, Int>(Surface::class.java)
  private val deathsBy = EnumMap<Surface, Int>(Surface::class.java)
  private val kinds = EnumMap<FaultKind, Int>(FaultKind::class.java)
  private val moments = EnumMap<Moment, Int>(Moment::class.java)

  var rounds = 0
  var steps = 0
  var midChecks = 0
  var quietChecks = 0
  var restarts = 0
  var idleRestarts = 0
  var eraseStarted = 0
  var eraseDone = 0
  var computedMedia = 0
  var publishedHeads = 0
  var filesHashed = 0L
  var bytesHashed = 0L
  var leaseEvents = 0
  var contendedLeases = 0
  var duplicateComputes = 0
  var snapshotsRead = 0
  val stepKinds = TreeMap<String, Int>()

  val totalFired: Int get() = firedBy.values.sum()
  val totalDeaths: Int get() = deathsBy.values.sum()

  fun planned(blow: Blow) {
    plannedBy.merge(blow.surface, 1, Int::plus)
  }

  fun called(surface: Surface, count: Int) {
    if (count > 0) calledBy.merge(surface, count, Int::plus)
  }

  fun fired(blow: Blow) {
    firedBy.merge(blow.surface, 1, Int::plus)
    kinds.merge(blow.kind, 1, Int::plus)
    moments.merge(blow.moment, 1, Int::plus)
    if (blow.kind == FaultKind.KILL) deathsBy.merge(blow.surface, 1, Int::plus)
  }

  fun firedOf(kind: FaultKind): Int = kinds[kind] ?: 0

  fun firedAt(moment: Moment): Int = moments[moment] ?: 0

  fun step(kind: String) {
    steps++
    stepKinds.merge(kind, 1, Int::plus)
  }

  /** La tabella di copertura: per superficie chiamate, colpi programmati, scattati e di quelli quanti morti. */
  fun coverageTable(): String = buildString {
    for (surface in Surface.entries) {
      val planned = plannedBy[surface] ?: 0
      val called = calledBy[surface] ?: 0
      if (planned == 0 && called == 0) continue
      appendLine("  ${surface.label}: chiamate $called, colpi programmati $planned, scattati ${firedBy[surface] ?: 0} (morti ${deathsBy[surface] ?: 0})")
    }
  }

  /** Le superfici dove nessun colpo e' scattato mai (un banco che non colpisce un punto lo dice). Il cambio di generazione e' un tipo di colpo, non un punto: non conta. */
  fun neverHit(): List<Surface> = Surface.entries.filter { it != Surface.FLIP && (firedBy[it] ?: 0) == 0 }
}
