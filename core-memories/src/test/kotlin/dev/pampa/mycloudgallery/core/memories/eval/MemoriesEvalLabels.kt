package dev.pampa.mycloudgallery.core.memories.eval

// I GIUDIZI SUI RICORDI VERI (docs/fase9-progetto.md §5.3, riga P11 del §4.4): per ogni ricordo della sessione un tocco -- "Bello", "Cosi' cosi'",
// "Da non mostrare" (con un motivo facoltativo: triste, sbagliato, noioso) -- e, nella griglia, le foto che **non** dovevano esserci (doppione,
// mossa o buia, screenshot o documento, non c'entra, triste). Un file di testo, versionato, che sta SOLO sul telefono in
// `noBackupFilesDir/eval/memories/` (fuori dai backup) e **mai nel repository** (`.gitignore`, `NoPrivateMemoriesEvalInGitTest`, `check.ps1`).
// Titoli, giorni e id sono privati: mai in un `toString`, in un log o in un resoconto. Stesso file in core-memories (test) e in :app (debug):
// cambia solo la riga `package`, e `MemoriesEvalSourceParityTest` controlla che le due copie restino uguali.
//
// Con la risposta Q6 (a) del 27/10 **non c'e' nessun filtro dei ricordi tristi**: "triste" e' solo una voce da contare. Per i ricordi "da non
// mostrare" vale la metrica, non un filtro.

/** Il giudizio sul ricordo intero (un tocco). */
enum class EvalVerdict(val code: Char) {
  GOOD('B'),
  SO_SO('C'),
  DONT_SHOW('N'),
  ;

  companion object {
    fun ofCode(code: Char): EvalVerdict? = entries.firstOrNull { it.code == code }
  }
}

/** Il motivo facoltativo di un "Da non mostrare". */
enum class EvalWhy(val code: Char) {
  SAD('t'),
  WRONG('s'),
  BORING('n'),
  ;

  companion object {
    fun ofCode(code: Char): EvalWhy? = entries.firstOrNull { it.code == code }
  }
}

/** Perche' una foto non doveva esserci (un solo motivo per foto: il piu' evidente). */
enum class EvalFault(val code: Char) {
  DUPLICATE('d'),
  BLURRY_OR_DARK('m'),
  SCREENSHOT_OR_DOC('s'),
  UNRELATED('x'),
  SAD('t'),
  ;

  companion object {
    fun ofCode(code: Char): EvalFault? = entries.firstOrNull { it.code == code }
  }
}

/** Un giudizio: il tocco e, per "Da non mostrare", il motivo (facoltativo). Il motivo con un altro tocco non ha senso. */
class EvalJudgement(val verdict: EvalVerdict, val why: EvalWhy? = null) {
  init {
    require(why == null || verdict == EvalVerdict.DONT_SHOW) { "Il motivo vale solo per \"Da non mostrare\"." }
  }

  override fun toString(): String = "EvalJudgement(${verdict.code})"

  override fun equals(other: Any?): Boolean = other is EvalJudgement && other.verdict == verdict && other.why == why

  override fun hashCode(): Int = 31 * verdict.hashCode() + (why?.hashCode() ?: 0)
}

/**
 * Le etichette di una sessione. Immutabile; ogni modifica ne da' una nuova. [run] sono i ricordi scelti per la sessione (quelli che l'utente vede, in
 * ordine), [judgements] il giudizio per id di ricordo, [faults] per ricordo le foto da togliere. Un giudizio o una foto segnata sono sempre di un ricordo
 * della sessione; una foto segnata e' sempre un elemento di quel ricordo.
 */
class MemoriesLabelSet(
  val run: List<EvalMemory> = emptyList(),
  val judgements: Map<String, EvalJudgement> = emptyMap(),
  val faults: Map<String, Map<String, EvalFault>> = emptyMap(),
) {
  init {
    require(run.size <= MAX_RUN) { "Al piu' $MAX_RUN ricordi." }
    require(run.map { it.id }.distinct().size == run.size) { "Ricordi doppi." }
    val byId = run.associateBy { it.id }
    require(judgements.keys.all { it in byId }) { "Giudizio su un ricordo che non c'e'." }
    for ((id, media) in faults) {
      val memory = byId[id]
      require(memory != null) { "Foto segnate in un ricordo che non c'e'." }
      require(media.keys.all { it in memory.members }) { "Una foto segnata non e' del ricordo." }
    }
  }

  fun memory(id: String): EvalMemory? = run.firstOrNull { it.id == id }

  fun judgement(id: String): EvalJudgement? = judgements[id]

  /** Le foto segnate di un ricordo (vuoto se nessuna). */
  fun faultsOf(id: String): Map<String, EvalFault> = faults[id].orEmpty()

  /** I ricordi con un giudizio, nell'ordine della sessione. */
  val judged: List<EvalMemory> get() = run.filter { it.id in judgements }

  /** I ricordi ancora senza giudizio. */
  val pending: List<EvalMemory> get() = run.filter { it.id !in judgements }

  /** Una sessione nuova: i ricordi di [memories] (al piu' [MAX_RUN], senza ripetizioni) e nessun giudizio. */
  fun withRun(memories: List<EvalMemory>): MemoriesLabelSet = MemoriesLabelSet(memories.distinctBy { it.id }.take(MAX_RUN))

  /** Il tocco sul ricordo [id]; con "Da non mostrare" si puo' dire il motivo. Cambiare tocco toglie il motivo di prima. */
  fun withJudgement(id: String, verdict: EvalVerdict, why: EvalWhy? = null): MemoriesLabelSet {
    require(memory(id) != null) { "Ricordo sconosciuto." }
    return MemoriesLabelSet(run, judgements + (id to EvalJudgement(verdict, why)), faults)
  }

  /** Toglie il giudizio (e basta: le foto segnate restano). */
  fun withoutJudgement(id: String): MemoriesLabelSet = MemoriesLabelSet(run, judgements - id, faults)

  /** Segna [media] come foto da togliere per [fault], o toglie il segno ([fault] nullo). */
  fun withFault(id: String, media: String, fault: EvalFault?): MemoriesLabelSet {
    val memory = memory(id)
    require(memory != null) { "Ricordo sconosciuto." }
    require(media in memory.members) { "La foto non e' del ricordo." }
    val now = if (fault == null) faultsOf(id) - media else faultsOf(id) + (media to fault)
    return MemoriesLabelSet(run, judgements, if (now.isEmpty()) faults - id else faults + (id to now))
  }

  /** Toglie tutte le foto segnate di un ricordo ("sono tutte giuste"). */
  fun withoutFaults(id: String): MemoriesLabelSet = MemoriesLabelSet(run, judgements, faults - id)

  /** Solo conteggi. */
  override fun toString(): String = "MemoriesLabelSet(${run.size} ricordi, ${judgements.size} giudicati, ${faults.values.sumOf { it.size }} foto segnate)"

  companion object {
    const val MAX_RUN: Int = 120
  }
}
