package dev.pampa.mycloudgallery.debugtools.eval.faces

import dev.pampa.mycloudgallery.core.model.FaceLocator

// LE ETICHETTE DELLA VALUTAZIONE DELLE PERSONE (docs/fase8-progetto.md §5.3, Fase 8 P11). Codice puro: LO STESSO FILE sta in
// `core-faces/src/test/.../eval/` (provato con storie sintetiche con errori noti) e in `app/src/debug/.../debugtools/eval/faces/` (lo
// strumento sul telefono); cambia solo la riga `package`, e un test di :app (FacesEvalSourceParityTest) controlla che le due copie restino
// uguali.
//
// Le etichette dicono, per ogni gruppo (persona) che l'utente ha guardato: quali volti NON sono di quella persona (localizzatori di
// volti, D23: sopravvivono a un ricalcolo), se il gruppo e' "misto", e con quali altri gruppi e' "la stessa persona". Sono dati privati:
// stanno solo sul telefono, in `noBackupFilesDir/eval/faces/`, e non entrano mai nel repository, nei log o in un `toString()`. Ne escono
// solo numeri (FacesEvalMetrics).

/** Le due sessioni del §5.3: la calibrazione (sceglie le soglie) e la misura (dice se la purezza e' almeno il 97%). */
enum class EvalSessionKind(val number: Int, val code: String) {
  CALIBRATION(1, "calibrazione"),
  MEASURE(2, "misura"),
  ;

  companion object {
    fun fromNumber(number: Int): EvalSessionKind? = entries.firstOrNull { it.number == number }
  }
}

/**
 * La fotografia dei conteggi quando comincia una sessione: quanti volti CORE e WEAK ci sono (nei media mostrabili) e quanti stanno in un
 * gruppo. Servono alla copertura (§5.3: sotto il 50% dei volti CORE si passa al piano B del modello, §7). Solo numeri.
 */
data class EvalTotals(val coreFaces: Int, val coreAssigned: Int, val weakFaces: Int, val weakAssigned: Int, val groupsShown: Int) {
  init {
    require(coreFaces >= 0 && weakFaces >= 0 && groupsShown >= 0) { "Conteggio negativo." }
    require(coreAssigned in 0..coreFaces && weakAssigned in 0..weakFaces) { "Piu' volti assegnati che volti." }
  }

  /** I volti CORE in un gruppo su tutti i volti CORE; 1,0 se non ce ne sono. */
  val coreCoverage: Double get() = if (coreFaces == 0) 1.0 else coreAssigned.toDouble() / coreFaces

  companion object {
    val EMPTY = EvalTotals(0, 0, 0, 0, 0)
  }
}

/**
 * Il giudizio su un gruppo. [person] e' l'id della persona (forma canonica); [size] i volti del gruppo quando e' stato scelto (il suo peso);
 * [shown] quanti volti la griglia ha mostrato. [reviewed] = l'utente ha finito di guardarlo (senza, il gruppo non conta nei numeri).
 * [mixed] = "Gruppo misto" (non si sa quali volti sono sbagliati). [wrong] = i volti toccati come "non e' lui". [sameAs] = altri gruppi
 * che l'utente ha detto essere la stessa persona ("e' la stessa di ...").
 */
data class GroupLabel(
  val person: String,
  val size: Int,
  val named: Boolean,
  val shown: Int = 0,
  val reviewed: Boolean = false,
  val mixed: Boolean = false,
  val wrong: Set<FaceLocator> = emptySet(),
  val sameAs: Set<String> = emptySet(),
) {
  init {
    require(FacesLabelFormat.isPerson(person) && sameAs.all(FacesLabelFormat::isPerson) && person !in sameAs) { "Id di persona non valido." }
    require(size in 0..FacesLabelFormat.MAX_FACES && shown in 0..FacesLabelFormat.MAX_FACES) { "Conteggi non validi." }
    require(wrong.size <= FacesLabelFormat.MAX_WRONG && sameAs.size <= FacesLabelFormat.MAX_SAME) { "Troppe etichette in un gruppo." }
    require(!(mixed && wrong.isNotEmpty())) { "Un gruppo misto non ha volti segnati uno a uno." }
  }

  /** Un gruppo e' **puro** se l'utente l'ha guardato e non ha trovato volti sbagliati ne' l'ha detto misto. */
  val pure: Boolean get() = reviewed && !mixed && wrong.isEmpty()

  /** Solo conteggi: ne' l'id ne' i posti dei volti passano da un `toString()`. */
  override fun toString(): String = "GroupLabel(reviewed=$reviewed, mixed=$mixed, ${wrong.size} sbagliati, ${sameAs.size} collegati)"
}

/** Una sessione: i gruppi scelti (in ordine), il seme della scelta, i conteggi iniziali e se e' chiusa. */
class SessionLabels(
  val kind: EvalSessionKind,
  val seed: Long,
  val totals: EvalTotals,
  val groups: List<GroupLabel>,
  val closed: Boolean = false,
) {
  init {
    require(groups.map { it.person }.distinct().size == groups.size) { "Gruppi doppi in una sessione." }
    require(groups.size <= FacesLabelFormat.MAX_GROUPS) { "Troppi gruppi in una sessione." }
  }

  fun group(person: String): GroupLabel? = groups.firstOrNull { it.person == person }

  /** Il gruppo sostituito (stessa posizione); uno nuovo in fondo. Una sessione chiusa non cambia. */
  fun withGroup(label: GroupLabel): SessionLabels {
    check(!closed) { "La sessione e' chiusa." }
    val at = groups.indexOfFirst { it.person == label.person }
    val next = if (at < 0) groups + label else groups.toMutableList().also { it[at] = label }
    return SessionLabels(kind, seed, totals, next, closed)
  }

  fun closed(): SessionLabels = SessionLabels(kind, seed, totals, groups, closed = true)

  val reviewedGroups: List<GroupLabel> get() = groups.filter { it.reviewed }

  override fun toString(): String = "SessionLabels(${kind.code}, ${groups.size} gruppi, ${reviewedGroups.size} guardati, chiusa=$closed)"
}

/** Le etichette: al piu' una sessione per tipo. Immutabile; ogni modifica ne da' una nuova. */
class FacesLabelSet(val sessions: Map<EvalSessionKind, SessionLabels> = emptyMap()) {
  init {
    require(sessions.all { (kind, s) -> s.kind == kind }) { "Sessione nel posto sbagliato." }
  }

  fun session(kind: EvalSessionKind): SessionLabels? = sessions[kind]

  fun with(session: SessionLabels): FacesLabelSet = FacesLabelSet(sessions + (session.kind to session))

  fun without(kind: EvalSessionKind): FacesLabelSet = FacesLabelSet(sessions - kind)

  val isEmpty: Boolean get() = sessions.isEmpty()

  override fun toString(): String = "FacesLabelSet(${sessions.values.joinToString { it.toString() }})"
}

/**
 * Il file delle etichette: testo a righe separate da tabulazioni, intestazione [MAGIC].
 *  - `S` sessione: numero, seme, volti CORE, CORE assegnati, volti WEAK, WEAK assegnati, gruppi mostrati, chiusa (0/1);
 *  - `G` gruppo: sessione, persona, dimensione, con nome (0/1), volti mostrati, segni (`-` oppure `r` guardato e/o `m` misto);
 *  - `W` volto sbagliato: sessione, persona, localizzatore ([FaceLocator.encode]);
 *  - `E` stessa persona: sessione, persona, l'altra persona.
 * Un file rovinato o ostile da' un errore con il **numero** della riga, mai il contenuto.
 */
object FacesLabelFormat {
  const val MAGIC: String = "MCGFAC01"
  const val FILE_NAME: String = "persone.mcgfac"
  const val MAX_GROUPS: Int = 200
  const val MAX_FACES: Int = 100_000
  const val MAX_WRONG: Int = 200
  const val MAX_SAME: Int = 50
  const val MAX_BYTES: Long = 8L * 1024 * 1024

  private val PERSON = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  fun isPerson(id: String): Boolean = PERSON.matches(id)

  fun serialize(labels: FacesLabelSet): String {
    val out = StringBuilder(MAGIC).append('\n')
    for (kind in EvalSessionKind.entries) {
      val s = labels.session(kind) ?: continue
      val t = s.totals
      out.append("S\t").append(kind.number).append('\t').append(s.seed).append('\t').append(t.coreFaces).append('\t').append(t.coreAssigned)
        .append('\t').append(t.weakFaces).append('\t').append(t.weakAssigned).append('\t').append(t.groupsShown).append('\t')
        .append(if (s.closed) '1' else '0').append('\n')
      for (g in s.groups) {
        out.append("G\t").append(kind.number).append('\t').append(g.person).append('\t').append(g.size).append('\t').append(if (g.named) '1' else '0')
          .append('\t').append(g.shown).append('\t').append(flags(g)).append('\n')
        for (w in g.wrong.map { it.encode() }.sorted()) out.append("W\t").append(kind.number).append('\t').append(g.person).append('\t').append(w).append('\n')
        for (e in g.sameAs.sorted()) out.append("E\t").append(kind.number).append('\t').append(g.person).append('\t').append(e).append('\n')
      }
    }
    return out.toString()
  }

  private fun flags(g: GroupLabel): String = if (!g.reviewed && !g.mixed) "-" else (if (g.reviewed) "r" else "") + (if (g.mixed) "m" else "")

  /** Rilegge un file. `IllegalArgumentException` con il numero della riga se qualcosa non torna. */
  fun parse(text: String): FacesLabelSet {
    require(text.length <= MAX_BYTES) { "File di etichette troppo grande." }
    val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    require(lines.firstOrNull() == MAGIC) { "Non e' un file di etichette (intestazione)." }
    val sessions = LinkedHashMap<EvalSessionKind, SessionBuilder>()
    lines.drop(1).forEachIndexed { index, line ->
      val n = index + 2
      try {
        val c = line.split('\t')
        val kind = EvalSessionKind.fromNumber(c.getOrNull(1)?.toInt() ?: throw IllegalArgumentException())
          ?: throw IllegalArgumentException()
        when (c[0]) {
          "S" -> {
            require(c.size == 9 && kind !in sessions)
            val totals = EvalTotals(c[3].toInt(), c[4].toInt(), c[5].toInt(), c[6].toInt(), c[7].toInt())
            require(c[8] == "0" || c[8] == "1")
            sessions[kind] = SessionBuilder(kind, c[2].toLong(), totals, c[8] == "1")
          }
          "G" -> {
            require(c.size == 7)
            val b = sessions[kind] ?: throw IllegalArgumentException()
            require(isPerson(c[2]) && c[2] !in b.groups && (c[4] == "0" || c[4] == "1"))
            require(c[6].isNotEmpty() && c[6].all { it == 'r' || it == 'm' || it == '-' })
            b.groups[c[2]] = GroupBuilder(c[3].toInt(), c[4] == "1", c[5].toInt(), 'r' in c[6], 'm' in c[6])
          }
          "W" -> {
            require(c.size == 4)
            val g = sessions[kind]?.groups?.get(c[2]) ?: throw IllegalArgumentException()
            g.wrong += FaceLocator.parse(c[3]) ?: throw IllegalArgumentException()
          }
          "E" -> {
            require(c.size == 4 && isPerson(c[3]) && c[3] != c[2])
            (sessions[kind]?.groups?.get(c[2]) ?: throw IllegalArgumentException()).same += c[3]
          }
          else -> throw IllegalArgumentException()
        }
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Riga $n del file di etichette non valida.")
      } catch (e: NumberFormatException) {
        throw IllegalArgumentException("Riga $n del file di etichette non valida.")
      } catch (e: IllegalStateException) {
        throw IllegalArgumentException("Riga $n del file di etichette non valida.")
      }
    }
    return try {
      FacesLabelSet(sessions.mapValues { (_, b) -> b.build() })
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("Il file di etichette non e' coerente.")
    }
  }

  private class GroupBuilder(val size: Int, val named: Boolean, val shown: Int, val reviewed: Boolean, val mixed: Boolean) {
    val wrong = LinkedHashSet<FaceLocator>()
    val same = LinkedHashSet<String>()
  }

  private class SessionBuilder(val kind: EvalSessionKind, val seed: Long, val totals: EvalTotals, val closed: Boolean) {
    val groups = LinkedHashMap<String, GroupBuilder>()

    fun build(): SessionLabels =
      SessionLabels(kind, seed, totals, groups.map { (p, b) -> GroupLabel(p, b.size, b.named, b.shown, b.reviewed, b.mixed, b.wrong, b.same) }, closed)
  }
}
