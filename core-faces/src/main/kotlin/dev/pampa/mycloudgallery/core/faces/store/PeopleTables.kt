package dev.pampa.mycloudgallery.core.faces.store

import dev.pampa.mycloudgallery.core.database.faces.dao.PeopleRecordDao
import dev.pampa.mycloudgallery.core.database.faces.entity.PeopleRecordEntity
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchiveLimits
import dev.pampa.mycloudgallery.core.faces.contract.PeopleKey
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.security.faces.FacesDataKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// LE VOCI `people` NEL DATABASE (docs/fase8-progetto.md §2.4, D14, D23, D24). Una riga per chiave (soggetto, campo), l'ultima scrittura
// vince (`PeopleRecord.winner`). Il **nome** di una persona (campo `name`) e' sigillato con la chiave dati (`sealed_value`, `aad` = soggetto
// e campo): la colonna in chiaro e' vuota; id, localizzatori e `true` degli altri campi stanno in chiaro. Una riga che non si apre (altra
// chiave, byte alterati) o non si capisce si salta e si riscrive alla prossima voce di quella chiave. Mai valori nei log (D37).
//
// R3 (correzioni della Fase 8): ogni riga ricorda la sua **provenienza** (`via`: il dispositivo che ha firmato il segmento da cui e' arrivata;
// l'autore per le scelte fatte qui). La revoca toglie per autore e per provenienza; si ripubblicano solo le voci arrivate dal loro autore; le
// chiavi nuove che arrivano dagli altri hanno un tetto in tutto e per provenienza (`FacesArchiveLimits`).

internal class PeopleTables(
  private val dao: PeopleRecordDao,
  private val key: FacesDataKey,
  private val maxPeople: Int = FacesArchiveLimits.MAX_PEOPLE,
  private val maxPerSigner: Int = FacesArchiveLimits.MAX_PEOPLE_PER_SIGNER,
) {

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

  /** R3: le voci arrivate dal loro autore (le sole che si ripubblicano, `FacesArchive.directPeople`). */
  suspend fun direct(): Map<PeopleKey, PeopleRecord> {
    val out = LinkedHashMap<PeopleKey, PeopleRecord>()
    for (row in dao.direct()) {
      val record = toRecord(row) ?: continue
      out[record.key] = record
    }
    return out
  }

  /** Unisce [records] scelte qui (provenienza = autore, nessun tetto): [addFrom] senza provenienza. */
  suspend fun add(records: List<PeopleRecord>) {
    addFrom(null, records)
  }

  /**
   * Unisce [records] con [PeopleRecord.merge] (e la regola delle voci "da datare", sotto), arrivate da [via] (null = scelte fatte qui: la
   * provenienza e' l'autore). Una voce che vince prende la sua provenienza; una voce uguale a quella che c'e' (stessa scelta, stessa ora, stesso
   * autore) che arriva dal suo autore le da' la provenienza dell'autore. Con [via], una chiave nuova oltre i tetti non entra. Restituisce quante
   * voci non sono entrate. Idempotente: una voce che non cambia niente non scrive.
   */
  suspend fun addFrom(via: String?, records: List<PeopleRecord>, own: String? = null): Int {
    if (records.isEmpty()) return 0
    return lock.withLock {
      val changed = LinkedHashMap<PeopleKey, Pair<PeopleRecord, String>>()
      var total = if (via == null) 0 else dao.count()
      var fromVia = if (via == null) 0 else dao.countVia(via)
      var refused = 0
      for (record in records) {
        val provenance = via ?: record.author
        val known = changed[record.key]
        val row = if (known == null) dao.get(record.edit.subject, record.edit.field) else null
        val current = known ?: row?.let { r -> toRecord(r)?.let { it to (r.via ?: r.author) } }
        val fresh = known == null && row == null
        if (fresh && via != null && (total >= maxPeople || fromVia >= maxPerSigner)) {
          refused++
          continue
        }
        val next = when {
          current == null -> record to provenance
          // Una mia scelta non la sostituisce una copia a mio nome portata da un altro.
          own != null && provenance != own && record.author == own && current.second == own && current.first.author == own -> null
          resolve(current.first, record) !== current.first -> record to provenance
          sameChoice(current.first, record) && provenance == record.author && current.second != record.author -> current.first to provenance
          else -> null
        } ?: continue
        changed[record.key] = next
        if (fresh) {
          total++
          if (provenance == via) fromVia++
        }
      }
      if (changed.isNotEmpty()) dao.upsertAll(changed.values.map { (record, provenance) -> toRow(record, provenance) })
      refused
    }
  }

  private fun sameChoice(a: PeopleRecord, b: PeopleRecord): Boolean = a.edit == b.edit && a.atNas == b.atNas && a.author == b.author

  /** Le voci "da datare" prendono l'ora del NAS [nasNow] (D36, `FacesArchive.datePeople`); quante. Nello stesso giro di `add`, mai insieme. */
  suspend fun date(nasNow: Long): Int = lock.withLock { dao.date(nasNow) }

  /** Toglie le voci scritte da [authors] **o arrivate da loro** (dispositivi revocati o persi, L99, R3, `FacesArchive.dropAuthors`); quante. */
  suspend fun dropAuthors(authors: Set<String>): Int = lock.withLock {
    authors.toList().chunked(PEOPLE_IN_LIMIT).sumOf { dao.deleteByAuthorsOrVia(it) }
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

  private fun toRow(record: PeopleRecord, via: String): PeopleRecordEntity {
    val edit = record.edit
    val value = edit.value
    return if (edit.field == NAME && value != null) {
      val sealed = key.seal(value.toByteArray(Charsets.UTF_8), FaceSecrets.nameAad(edit.subject, edit.field))
      PeopleRecordEntity(edit.subject, edit.field, value = null, sealedValue = sealed, atNas = record.atNas, author = record.author, via = via)
    } else {
      PeopleRecordEntity(edit.subject, edit.field, value = value, sealedValue = null, atNas = record.atNas, author = record.author, via = via)
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
    const val PEOPLE_IN_LIMIT = 500
  }
}
