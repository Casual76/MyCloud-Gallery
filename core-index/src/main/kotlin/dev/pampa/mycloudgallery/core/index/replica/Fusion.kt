package dev.pampa.mycloudgallery.core.index.replica

import dev.pampa.mycloudgallery.core.index.replica.store.MergedFile
import dev.pampa.mycloudgallery.core.index.replica.store.RecordRow
import dev.pampa.mycloudgallery.core.index.replica.store.SharedIdentity
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.text.Normalizer
import java.util.Locale

/**
 * La chiave di un percorso nella replica: NFC e minuscolo (`Locale.ROOT`), come la chiave dei record `meta`
 * (`docs/formato-nas.md` §5.5-bis) e `Location.Nas.key`: due nomi diversi solo per le maiuscole sono lo stesso posto per
 * Samba (E-N7). Mai nei log.
 */
object PathKeys {
  fun of(path: SharePath): String = Normalizer.normalize(path.value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

  fun share(share: String): String = share.lowercase(Locale.ROOT)
}

/** Il record corrente di un dispositivo per un percorso. */
data class DeviceRecord(val deviceId: String, val record: MetaRecord)

/**
 * La FUSIONE fra dispositivi (D5, D6), pura e deterministica: dispositivi diversi con gli stessi dati arrivano alla
 * stessa vista senza parlarsi.
 *
 * - **D5**: per ogni dispositivo vale il record col `seq` piu' alto fra i suoi segmenti (e i propri dati locali); una
 *   lapide vince sul file se e' piu' recente.
 * - **D6**: un record vale solo se il suo timbro coincide con l'ultimo elenco della scansione (`NasStamp.sameAs`); senza
 *   elenco vale "provvisorio". Fra i validi vince chi ha **piu' classi preparate**, poi l'**id di dispositivo minore**
 *   (base32 di Crockford: l'ordine dei caratteri e' quello dei valori).
 * - **D7**: l'identita' e' il `MediaId` **minore** fra i record validi con lo stesso contenuto (`qh1` + dimensione) del
 *   vincente, con il suo `VersionId`.
 */
object Fusion {

  /** D5: il record corrente di ogni dispositivo fra [rows] (righe dello stesso percorso). */
  fun latestByDevice(rows: Collection<RecordRow>): List<DeviceRecord> {
    val best = HashMap<String, MetaRecord>()
    for (row in rows) {
      val current = best[row.deviceId]
      if (current == null || row.record.seq > current.seq) best[row.deviceId] = row.record
    }
    return best.entries.sortedBy { it.key }.map { DeviceRecord(it.key, it.value) }
  }

  /** Vero se il record vale rispetto all'elenco (D6, E-N12); senza elenco vale (provvisorio). */
  fun isValid(record: MetaRecord.Present, listing: NasStamp?): Boolean = listing == null || record.file.stamp.sameAs(listing)

  /** L'ordine D6: piu' classi preparate, poi id di dispositivo minore. */
  val ORDER: Comparator<Pair<String, MetaRecord.Present>> =
    compareByDescending<Pair<String, MetaRecord.Present>> { Integer.bitCount(it.second.file.prepared) }.thenBy { it.first }

  /** Il vincente D6 fra i record correnti [current], o null. */
  fun winner(current: List<DeviceRecord>, listing: NasStamp?): Pair<String, MetaRecord.Present>? =
    valid(current, listing).minWithOrNull(ORDER)

  /** La vista fusa di un percorso: vincente, provvisorio, identita' (D6, D7). */
  fun merge(pathKey: String, rows: Collection<RecordRow>, listing: NasStamp?): MergedFile {
    val current = latestByDevice(rows)
    val valid = valid(current, listing)
    val path = current.firstOrNull()?.record?.path ?: rows.firstOrNull()?.record?.path
    val winner = valid.minWithOrNull(ORDER) ?: return MergedFile(pathKey, path, null, null, listing == null, null)
    val content = winner.second.file.contentKey
    val identity = valid
      .filter { it.second.file.contentKey == content }
      .minWith(compareBy<Pair<String, MetaRecord.Present>> { it.second.file.mediaId }.thenBy { it.first })
      .second.file
    return MergedFile(
      pathKey = pathKey,
      path = winner.second.path,
      winner = winner.second,
      winnerDevice = winner.first,
      provisional = listing == null,
      identity = SharedIdentity(identity.mediaId, identity.versionId, content),
    )
  }

  private fun valid(current: List<DeviceRecord>, listing: NasStamp?): List<Pair<String, MetaRecord.Present>> =
    current.mapNotNull { (device, record) -> (record as? MetaRecord.Present)?.takeIf { isValid(it, listing) }?.let { device to it } }
}
