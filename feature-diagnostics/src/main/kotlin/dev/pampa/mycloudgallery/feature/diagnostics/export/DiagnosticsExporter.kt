package dev.pampa.mycloudgallery.feature.diagnostics.export

import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.feature.diagnostics.aggregate.DiagnosticsSnapshot

/**
 * Il file dell'esportazione, gia' scritto: il testo e le sue righe (per l'anteprima).
 *
 * @property itemCount le voci esportate.
 */
class ExportDocument(val text: String, val itemCount: Int) {
  val lines: List<String> get() = text.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }

  override fun toString(): String = "ExportDocument($itemCount voci, ${text.length} caratteri)"
}

/** Cio' che si sa del telefono e non e' personale. */
class ExportEnvironment(val sdkInt: Int? = null)

/**
 * L'esportazione della Diagnostica, **senza dati personali** (docs/fase2-progetto.md §2.6, piano 2.7).
 *
 * # Perche' il file non puo' contenere un nome, un IP, un utente o un percorso
 *
 * Tre difese, una dentro l'altra:
 *
 *  1. **Il tipo.** Dalle voci si legge solo il codice (scritto nel sorgente), la sezione, la gravita' e la mappa
 *     `export`, fatta di [ExportValue]: numeri, si'/no, durate, nomi di costanti di un enum dell'app e impronte
 *     HMAC. [ExportValue] non ha un caso «stringa»: un dato personale non ha un posto dove stare. Il testo che
 *     compare a schermo (`DiagItem.detail`, `DiagText`) non viene **mai letto** da qui.
 *  2. **La grammatica.** Il file e' prodotto riga per riga da forme fisse, e [conforms] lo rilegge con le stesse
 *     forme: ogni riga deve essere una di quelle, ogni valore un numero, un si'/no, una durata (`250ms`), una
 *     costante (`Tipo.COSTANTE`) o un'impronta (`#` e 12 cifre esadecimali): nessun'altra stringa. Una riga fuori
 *     dalla grammatica (un bug di domani) fa rifiutare l'esportazione invece di finire in un file mandato a
 *     qualcuno.
 *  3. **L'anteprima.** L'utente vede il file prima di salvarlo (`ACTION_CREATE_DOCUMENT`, nessun FileProvider), e
 *     quel che vede e' esattamente quel che viene scritto.
 *
 * Formato: JSON con una voce per riga. Le impronte hanno il prefisso `#`; le durate il suffisso `ms`; le costanti
 * la forma `Tipo.COSTANTE`. Le chiavi dei valori sono in ordine alfabetico, cosi' due esportazioni dello stesso
 * stato sono identiche.
 */
object DiagnosticsExporter {

  const val FORMAT = "mycloudgallery-diagnostics"
  const val VERSION = 1

  /** Il file per [snapshot]; lancia [IllegalStateException] se il risultato non rispetta la grammatica. */
  fun render(snapshot: DiagnosticsSnapshot, environment: ExportEnvironment = ExportEnvironment()): ExportDocument {
    val text = buildString {
      append("{\n")
      append("  \"format\": \"").append(FORMAT).append("\",\n")
      append("  \"version\": ").append(VERSION).append(",\n")
      environment.sdkInt?.let { append("  \"sdk\": ").append(it).append(",\n") }
      append("  \"sections\": {\n")
      val sections = snapshot.sections.filterValues { it.isNotEmpty() }.entries.toList()
      sections.forEachIndexed { sectionIndex, (section, items) ->
        append("    \"").append(section.name).append("\": [\n")
        items.forEachIndexed { index, item ->
          append("      ").append(itemLine(item))
          append(if (index < items.lastIndex) ",\n" else "\n")
        }
        append(if (sectionIndex < sections.lastIndex) "    ],\n" else "    ]\n")
      }
      append("  }\n")
      append("}\n")
    }
    check(conforms(text)) { "l'esportazione non rispetta la grammatica: non viene prodotta" }
    return ExportDocument(text, itemCount = snapshot.sections.values.sumOf { it.size })
  }

  private fun itemLine(item: DiagItem): String = buildString {
    append("{\"code\": \"").append(item.code.id).append("\", \"severity\": \"").append(item.severity.name).append('"')
    // Un valore che non ha la forma attesa si lascia fuori (vedi `encode`): l'esportazione prosegue senza.
    val pairs = item.export.toSortedMap().mapNotNull { (key, value) -> encode(value)?.let { "\"$key\": $it" } }
    if (pairs.isNotEmpty()) {
      append(", \"values\": {")
      append(pairs.joinToString(", "))
      append('}')
    }
    append('}')
  }

  /**
   * Il valore come testo JSON, o null se non ha la forma attesa.
   *
   * Il tipo [ExportValue] basta quando i valori nascono nel codice; non basta per un valore **riletto dal disco**
   * (l'anello degli eventi): `ExportValue.Enum.restore` accetta qualunque coppia di identificatori, quindi un
   * file manomesso potrebbe farne passare una che e' un nome («Mario.Rossi»). Un'enum vera ha il tipo in
   * `UpperCamel` e la costante in `MAIUSCOLO_CON_TRATTINI_BASSI`: qualunque altra forma non e' un'enum dell'app e non
   * viene esportata.
   */
  private fun encode(value: ExportValue): String? = when (value) {
    is ExportValue.Num -> value.value.toString()
    is ExportValue.Flag -> value.value.toString()
    is ExportValue.Dur -> if (value.millis >= 0) "\"${value.millis}ms\"" else null
    is ExportValue.Enum ->
      if (ENUM_TYPE.matches(value.type) && ENUM_CONSTANT.matches(value.constant)) "\"${value.type}.${value.constant}\"" else null
    is ExportValue.Hashed -> if (HASH.matches(value.hex)) "\"#${value.hex}\"" else null
  }

  // --- La grammatica -------------------------------------------------------------------------------------------

  private const val ENUM_TYPE_PATTERN = "[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*"
  private const val ENUM_CONSTANT_PATTERN = "[A-Z][A-Z0-9_]*"
  private val ENUM_TYPE = Regex(ENUM_TYPE_PATTERN)
  private val ENUM_CONSTANT = Regex(ENUM_CONSTANT_PATTERN)
  private val HASH = Regex("[0-9a-f]{12}")

  /** I soli quattro modi in cui un valore compare: numero, si'/no, durata, costante di un enum, impronta. */
  private const val WORD = "(?:[0-9]{1,15}ms|$ENUM_TYPE_PATTERN\\.$ENUM_CONSTANT_PATTERN|#[0-9a-f]{12})"
  private const val VALUE = "(?:-?[0-9]{1,19}|true|false|\"$WORD\")"
  private const val PAIR = "\"[a-z][A-Za-z0-9_.]{0,47}\": $VALUE"

  private val LINES = listOf(
    Regex("\\{"),
    Regex("  \"format\": \"$FORMAT\","),
    Regex("  \"version\": [0-9]{1,3},"),
    Regex("  \"sdk\": [0-9]{1,3},"),
    Regex("  \"sections\": \\{"),
    Regex("    \"[A-Z_]{3,40}\": \\["),
    Regex("      \\{\"code\": \"[A-Z][A-Z0-9_]{2,63}\", \"severity\": \"(?:OK|INFO|WARNING|ERROR)\"" +
      "(?:, \"values\": \\{$PAIR(?:, $PAIR)*\\})?\\},?"),
    Regex("    \\],?"),
    Regex("  \\}"),
    Regex("\\}"),
  )

  /**
   * Il testo rispetta la grammatica: ogni riga e' una delle forme ammesse, senza altro. E' il controllo che si
   * puo' rifare su un file qualunque (anche letto dal disco) per sapere che non porta testo libero.
   */
  fun conforms(text: String): Boolean {
    if (!text.endsWith("\n") || text.contains('\r')) return false
    val lines = text.removeSuffix("\n").split('\n')
    return lines.isNotEmpty() && lines.all { line -> LINES.any { it.matches(line) } }
  }
}
