package dev.pampa.mycloudgallery.core.search.eval

// LE ETICHETTE DEL SET PRIVATO (§5.3): le query dell'utente, per ogni query le celle attese (chiavi di contenuto) e, per "Documenti" (D34), le
// foto segnate come documento o no. Un file di testo, versionato, che sta SOLO sul telefono in `noBackupFilesDir/eval/` (fuori dai backup) e si
// esporta con `adb` in `%USERPROFILE%\.mycloudgallery\eval\`: **mai nel repository** (`.gitignore`, `NoPrivateEvalDataInGitTest`, `check.ps1`).
// Stesso file in core-search (test) e in :app (debug).

/** Una query del set privato. Il testo e' privato: mai in un `toString`, in un log o in un file esportato come numeri. */
class EvalQuery(val id: String, val category: EvalCategory, val text: String) {
  init {
    require(ID.matches(id)) { "Identificativo di query non valido." }
    require(text.isNotBlank() && text.length <= MAX_TEXT) { "Testo di query non valido." }
  }

  override fun toString(): String = "EvalQuery($id, ${category.code})"

  companion object {
    val ID = Regex("[a-z0-9]{1,12}")
    const val MAX_TEXT: Int = 200
  }
}

/** Le etichette: query, attese per query e documenti. Immutabile; ogni modifica ne da' una nuova. */
class LabelSet(
  val queries: List<EvalQuery> = emptyList(),
  /** id della query -> chiavi di contenuto delle celle attese. */
  val expected: Map<String, Set<String>> = emptyMap(),
  /** chiave di contenuto -> e' un documento (vero) o no (falso). */
  val documents: Map<String, Boolean> = emptyMap(),
) {
  init {
    require(queries.map { it.id }.distinct().size == queries.size) { "Identificativi di query doppi." }
    require(expected.keys.all { id -> queries.any { it.id == id } }) { "Attese di una query che non c'e'." }
    require(expected.values.all { keys -> keys.all(LabelFormat::isKey) } && documents.keys.all(LabelFormat::isKey)) { "Chiave di contenuto non valida." }
  }

  fun query(id: String): EvalQuery? = queries.firstOrNull { it.id == id }

  fun expectedOf(id: String): Set<String> = expected[id].orEmpty()

  fun withQuery(query: EvalQuery): LabelSet {
    require(queries.size < MAX_QUERIES || queries.any { it.id == query.id }) { "Al piu' $MAX_QUERIES query." }
    return LabelSet(queries.filter { it.id != query.id } + query, expected, documents).sorted()
  }

  fun withoutQuery(id: String): LabelSet = LabelSet(queries.filter { it.id != id }, expected - id, documents)

  fun withExpected(id: String, key: String, on: Boolean): LabelSet {
    require(query(id) != null) { "Query sconosciuta." }
    require(LabelFormat.isKey(key)) { "Chiave non valida." }
    val keys = expectedOf(id)
    return LabelSet(queries, expected + (id to if (on) keys + key else keys - key), documents)
  }

  fun withDocument(key: String, isDocument: Boolean?): LabelSet {
    require(LabelFormat.isKey(key)) { "Chiave non valida." }
    return LabelSet(queries, expected, if (isDocument == null) documents - key else documents + (key to isDocument))
  }

  private fun sorted(): LabelSet = LabelSet(queries.sortedBy { it.id }, expected, documents)

  /** Solo conteggi. */
  override fun toString(): String = "LabelSet(${queries.size} query, ${expected.values.sumOf { it.size }} attese, ${documents.size} documenti)"

  companion object {
    const val MAX_QUERIES: Int = 100
  }
}

object LabelFormat {
  const val MAGIC: String = "MCGEVAL1"
  private val KEY = Regex("[0-9a-f]{64}")

  fun isKey(key: String): Boolean = KEY.matches(key)

  fun serialize(labels: LabelSet): String {
    val out = StringBuilder(MAGIC).append('\n')
    for (q in labels.queries) out.append("Q\t").append(q.id).append('\t').append(q.category.code).append('\t').append(escape(q.text)).append('\n')
    for (q in labels.queries) for (key in labels.expectedOf(q.id).sorted()) out.append("E\t").append(q.id).append('\t').append(key).append('\n')
    for ((key, doc) in labels.documents.toSortedMap()) out.append("D\t").append(key).append('\t').append(if (doc) '1' else '0').append('\n')
    return out.toString()
  }

  /** Rilegge un file; un file rovinato da' un errore con il **numero** della riga, mai il contenuto. */
  fun parse(text: String): LabelSet {
    val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    require(lines.firstOrNull() == MAGIC) { "Non e' un file di etichette (intestazione)." }
    val queries = ArrayList<EvalQuery>()
    val expected = LinkedHashMap<String, MutableSet<String>>()
    val documents = LinkedHashMap<String, Boolean>()
    lines.drop(1).forEachIndexed { index, line ->
      val n = index + 2
      val c = line.split('\t')
      try {
        when (c[0]) {
          "Q" -> {
            require(c.size == 4)
            queries += EvalQuery(c[1], requireNotNull(EvalCategory.fromCode(c[2])), unescape(c[3]))
          }
          "E" -> {
            require(c.size == 3 && isKey(c[2]))
            expected.getOrPut(c[1]) { LinkedHashSet() } += c[2]
          }
          "D" -> {
            require(c.size == 3 && isKey(c[1]) && (c[2] == "1" || c[2] == "0"))
            documents[c[1]] = c[2] == "1"
          }
          else -> throw IllegalArgumentException()
        }
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Riga $n del file di etichette non valida.")
      }
    }
    return LabelSet(queries, expected, documents)
  }

  private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

  private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        i++
        out.append(
          when (s[i]) {
            'n' -> '\n'
            't' -> '\t'
            else -> s[i]
          },
        )
      } else {
        out.append(c)
      }
      i++
    }
    return out.toString()
  }
}
