package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.database.faces.dao.PeopleRecordDao
import dev.pampa.mycloudgallery.core.database.faces.entity.PeopleRecordEntity
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// LE VOCI `people` NEL DATABASE (docs/fase8-progetto.md §2.4, D14, D23, D24). Una riga per chiave (soggetto, campo), l'ultima scrittura
// vince (`PeopleRecord.winner`). Il **nome** di una persona (campo `name`) e' sigillato con la chiave dati (`sealed_value`, `aad` = soggetto
// e campo): la colonna in chiaro e' vuota; id, localizzatori e `true` degli altri campi stanno in chiaro. Una riga che non si apre (altra
// chiave, byte alterati) o non si capisce si salta e si riscrive alla prossima voce di quella chiave. Mai valori nei log (D37).

internal class PeopleTables(private val dao: PeopleRecordDao, private val key: FacesDataKey) {

  /** Una scrittura per volta: leggere, scegliere il vincitore e scrivere non si intrecciano con un'altra `add`. */
  private val lock = Mutex()

  suspend fun all(): Map<PeopleKey, PeopleRecord> {
    val out = LinkedHashMap<PeopleKey, PeopleRecord>()
    for (row in dao.all()) {
      val record = toRecord(row) ?: continue
      out[record.key] = record
    }
    return out
  }

  /** Unisce [records] con [PeopleRecord.merge] (e la regola delle voci "da datare", sotto). Idempotente: una voce che non cambia niente non scrive. */
  suspend fun add(records: List<PeopleRecord>) {
    if (records.isEmpty()) return
    lock.withLock {
      val changed = LinkedHashMap<PeopleKey, PeopleRecord>()
      for (record in records) {
        val current = changed[record.key] ?: dao.get(record.edit.subject, record.edit.field)?.let(::toRecord)
        val winner = if (current == null) record else resolve(current, record)
        if (winner !== current) changed[record.key] = winner
      }
      if (changed.isNotEmpty()) dao.upsertAll(changed.values.map(::toRow))
    }
  }

  /**
   * [PeopleRecord.winner], con due regole in piu' per le voci "da datare" (`atNas` null: una scelta appena fatta qui, che vale subito e si data
   * alla prima sincronizzazione):
   * - due voci **entrambe da datare** sono entrambe di questo dispositivo, e chi scrive la nuova **sostituisce** la vecchia (scritto nel
   *   contratto di P0; l'ordine di `winner` le tratterebbe per valore: "Bea" poi "Anna" lascerebbe "Bea");
   * - una voce **datata** che e' la stessa scelta (stessa modifica, stesso autore) di una da datare **la data**: la sostituisce. Senza, la
   *   voce da datare (che vince su tutte) non verrebbe mai datata e il suo valore resterebbe sopra quello degli altri dispositivi.
   */
  private fun resolve(current: PeopleRecord, incoming: PeopleRecord): PeopleRecord = when {
    current.atNas != null -> PeopleRecord.winner(current, incoming)
    incoming.atNas == null -> incoming
    incoming.edit == current.edit && incoming.author == current.author -> incoming
    else -> PeopleRecord.winner(current, incoming)
  }

  private fun toRow(record: PeopleRecord): PeopleRecordEntity {
    val edit = record.edit
    val value = edit.value
    return if (edit.field == NAME && value != null) {
      val sealed = key.seal(value.toByteArray(Charsets.UTF_8), FaceSecrets.nameAad(edit.subject, edit.field))
      PeopleRecordEntity(edit.subject, edit.field, value = null, sealedValue = sealed, atNas = record.atNas, author = record.author)
    } else {
      PeopleRecordEntity(edit.subject, edit.field, value = value, sealedValue = null, atNas = record.atNas, author = record.author)
    }
  }

  private fun toRecord(row: PeopleRecordEntity): PeopleRecord? {
    val sealed = row.sealedValue
    val value = if (row.field == NAME) {
      // Un nome sta solo sigillato: una riga `name` con il testo in chiaro non l'abbiamo scritta noi.
      if (row.value != null) return null
      if (sealed == null) null else key.open(sealed, FaceSecrets.nameAad(row.subject, row.field))?.toString(Charsets.UTF_8) ?: return null
    } else {
      if (sealed != null) return null
      row.value
    }
    val edit = PeopleEditCodec.parse(row.subject, row.field, value) ?: return null
    return PeopleRecord(edit, row.atNas, row.author)
  }

  private companion object {
    const val NAME = "name"
  }
}
