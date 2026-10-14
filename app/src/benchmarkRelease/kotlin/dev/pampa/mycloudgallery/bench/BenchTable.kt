package dev.pampa.mycloudgallery.bench

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement

/**
 * Un `INSERT OR REPLACE` preparato su una tabella del catalogo, per scrivere 100 mila righe in una transazione.
 *
 * Scrive **solo le colonne che il dataset conosce** ([provided]); le altre le lascia al `DEFAULT` o a `NULL`. Le colonne
 * `NOT NULL` senza `DEFAULT` che il dataset non conosce (le aggiungera' una migrazione futura) le riempie con un valore
 * neutro del loro tipo, letto da `PRAGMA table_info`: cosi' un campo nuovo del catalogo non rompe la variante di misura.
 * Una colonna che il dataset scrive e che non esiste piu' invece **fa fallire** subito, con il nome della tabella.
 */
internal class BenchTable(
  db: SupportSQLiteDatabase,
  private val table: String,
  provided: List<String>,
  /**
   * Colonne che il dataset scrive **se la tabella le ha** (P10: la chiave di contenuto, che lo schema v3 di P2 potra'
   * tenere in una colonna): se non ci sono, [optionalLong] non fa niente invece di fallire. Una colonna di [provided]
   * che manca, invece, fa fallire subito.
   */
  optional: List<String> = emptyList(),
) {

  private val position = HashMap<String, Int>()
  private val statement: SupportSQLiteStatement
  private val extras: List<Extra>

  private class Extra(val index: Int, val type: String)

  init {
    val known = LinkedHashMap<String, TableColumn>()
    db.query("PRAGMA table_info(`$table`)").use { c ->
      val iName = c.getColumnIndexOrThrow("name")
      val iType = c.getColumnIndexOrThrow("type")
      val iNotNull = c.getColumnIndexOrThrow("notnull")
      val iDefault = c.getColumnIndexOrThrow("dflt_value")
      val iPk = c.getColumnIndexOrThrow("pk")
      while (c.moveToNext()) {
        known[c.getString(iName)] = TableColumn(
          type = c.getString(iType).orEmpty().uppercase(),
          notNull = c.getInt(iNotNull) != 0,
          hasDefault = !c.isNull(iDefault),
          primaryKey = c.getInt(iPk) != 0,
        )
      }
    }
    require(known.isNotEmpty()) { "La tabella $table non esiste." }
    val missing = provided.filter { it !in known }
    require(missing.isEmpty()) { "Colonne di $table cambiate: mancano ${missing.joinToString()}. Aggiorna BenchSeeder." }

    val all = ArrayList(provided)
    all += optional.filter { it in known && it !in provided }
    val extraColumns = known.filter { (name, col) -> name !in all && col.notNull && !col.hasDefault && !col.primaryKey }
    all += extraColumns.keys
    all.forEachIndexed { i, name -> position[name] = i + 1 }
    extras = extraColumns.map { (name, col) -> Extra(position.getValue(name), col.type) }

    val columns = all.joinToString(", ") { "`$it`" }
    val marks = all.joinToString(", ") { "?" }
    statement = db.compileStatement("INSERT OR REPLACE INTO `$table` ($columns) VALUES ($marks)")
  }

  private class TableColumn(val type: String, val notNull: Boolean, val hasDefault: Boolean, val primaryKey: Boolean)

  fun text(column: String, value: String?) {
    val i = index(column)
    if (value == null) statement.bindNull(i) else statement.bindString(i, value)
  }

  /** Scrive [value] in [column] se la tabella ha la colonna (vedi `optional`), altrimenti niente. */
  fun optionalLong(column: String, value: Long?) {
    if (column in position) long(column, value)
  }

  fun long(column: String, value: Long?) {
    val i = index(column)
    if (value == null) statement.bindNull(i) else statement.bindLong(i, value)
  }

  fun int(column: String, value: Int?) = long(column, value?.toLong())

  fun blob(column: String, value: ByteArray?) {
    val i = index(column)
    if (value == null) statement.bindNull(i) else statement.bindBlob(i, value)
  }

  fun real(column: String, value: Double?) {
    val i = index(column)
    if (value == null) statement.bindNull(i) else statement.bindDouble(i, value)
  }

  /** Scrive la riga bindata e prepara la successiva. */
  fun commit() {
    for (extra in extras) {
      when {
        extra.type.contains("INT") -> statement.bindLong(extra.index, 0L)
        extra.type.contains("REAL") || extra.type.contains("FLOA") || extra.type.contains("DOUB") -> statement.bindDouble(extra.index, 0.0)
        extra.type.contains("BLOB") -> statement.bindBlob(extra.index, ByteArray(0))
        else -> statement.bindString(extra.index, "")
      }
    }
    statement.executeInsert()
    statement.clearBindings()
  }

  fun close() = statement.close()

  private fun index(column: String): Int =
    position[column] ?: throw IllegalArgumentException("Colonna $column non dichiarata per $table.")
}
