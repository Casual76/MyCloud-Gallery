package dev.pampa.mycloudgallery.core.backup.testing

import android.media.MediaDataSource
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.PhoneFile
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import java.io.IOException
import java.security.MessageDigest

/**
 * Il **telefono finto** alla porta [PhoneBytes] (Fase 5A, P0, `docs/fase5-progetto.md` §5.2): righe con byte veri o virtuali (un video
 * "da GB" generato da un seme, lo stesso tipo di contenuto del NAS finto, cosi' `InMemoryNasMedia.registerKnownContent` lo riconosce e
 * non lo copia), e i colpi del banco delle interruzioni:
 * - riga riscritta (Samsung "Salva", E-B15), cancellata (E-B4), MediaStore ricostruito con gli `_ID` rinumerati (E-P12, E-P22);
 * - gli stessi eventi **durante una lettura**, dopo n byte ([duringRead]);
 * - permessi ritirati, permesso della posizione negato, accesso parziale ([denied]);
 * - il file che **cresce senza che il MediaStore lo sappia** ([growSilently], R6 della revisione 5A): la riga dice ancora la dimensione di
 *   prima, il descrittore aperto ha quella nuova.
 *
 * E' il telefono: sopravvive alla "morte del processo" dell'app. core-backup non vede core-mediastore: `FakeMediaStore` (esteso nello
 * stesso modo) serve all'adattatore vero di `:app` (P7); questo serve al motore e al banco di core-backup (P2, P6).
 */
class FakePhoneBytes(volume: String = VOLUME, version: String = "v1") {

  /** Cosa succede a una riga mentre la si legge (come `FakeMediaStore.MidRead`). */
  sealed interface MidRead {
    /** Il file viene riscritto sul posto: chi legge vede i byte nuovi da qui in poi, la riga ha un timbro nuovo. */
    class Rewritten(val content: NasContent, val modifiedMs: Long) : MidRead

    data object Vanished : MidRead

    /** Il MediaStore si ricostruisce: versione nuova e la riga cambia `_ID`; la lettura in corso continua. */
    class Renumbered(val newVersion: String, val newId: Long) : MidRead
  }

  /** [stampSize] e' la dimensione che dice il MediaStore (la riga); il contenuto e' quello vero del file, che puo' essere piu' lungo (R6). */
  private class Row(var content: NasContent, var modifiedMs: Long, var generation: Long, var stampSize: Long = content.size)

  private class Trigger(val id: Long, val afterBytes: Long, val event: MidRead) {
    @Volatile var fired = false
  }

  private val lock = Any()
  private val volumeName = volume
  private var mediaStoreVersion = version
  private var generation = 1L
  private val rows = LinkedHashMap<Long, Row>()
  private val triggers = ArrayList<Trigger>()

  /** Se non null, il telefono nega i byte (E-P15, E-P21, E-P1). */
  @Volatile var denied: PhoneDenied? = null

  /** Le aperture e i byte letti: per provare che la scorciatoia del timbro non apre file e che i video si leggono in streaming. */
  @Volatile var opens: Int = 0
    private set

  @Volatile var bytesRead: Long = 0
    private set

  /** La porta vista dal backup. */
  val port: PhoneBytes = Port()

  val version: String get() = synchronized(lock) { mediaStoreVersion }

  /** Aggiunge una riga con il contenuto [content] e la data di modifica [modifiedMs]; restituisce il riferimento con la versione attuale. */
  fun add(id: Long, content: NasContent, modifiedMs: Long = 1_000): PhoneRef = synchronized(lock) {
    require(id > 0) { "Un _ID e' positivo." }
    rows[id] = Row(content, modifiedMs, ++generation)
    PhoneRef(volumeName, id, mediaStoreVersion)
  }

  /** Il riferimento di una riga com'e' adesso (null se non c'e'). */
  fun ref(id: Long): PhoneRef? = synchronized(lock) { if (id in rows) PhoneRef(volumeName, id, mediaStoreVersion) else null }

  /** Il timbro di una riga adesso. */
  fun stamp(id: Long): FileStamp? = synchronized(lock) { rows[id]?.let { FileStamp(it.stampSize, it.modifiedMs, it.generation) } }

  /** Samsung "Salva" (E-B15): byte nuovi sul posto, timbro nuovo. */
  fun rewrite(id: Long, content: NasContent, modifiedMs: Long) {
    synchronized(lock) {
      val row = rows.getValue(id)
      row.content = content
      row.stampSize = content.size
      row.modifiedMs = modifiedMs
      row.generation = ++generation
    }
  }

  /**
   * R6: il file cresce (un'app che scrive in piu' sessioni, la memoria condivisa di Android 10) **senza che il MediaStore lo sappia**: il
   * contenuto vero e' [content] (di solito i byte di prima piu' una coda), ma la riga ha ancora la dimensione, la data e la generazione di
   * prima. Chi guarda solo il timbro non se ne accorge; chi guarda dove finisce il file si'.
   */
  fun growSilently(id: Long, content: NasContent) {
    synchronized(lock) { rows.getValue(id).content = content }
  }

  /** Cancellata (E-B4): la riga non c'e' piu'. */
  fun delete(id: Long) {
    synchronized(lock) { rows.remove(id) }
  }

  /** Il MediaStore si ricostruisce (E-P12): versione nuova, gli `_ID` di [renumber] cambiano (vecchio → nuovo). */
  fun rebuild(newVersion: String, renumber: Map<Long, Long> = emptyMap()) {
    synchronized(lock) {
      val old = LinkedHashMap(rows)
      rows.clear()
      for ((id, row) in old) rows[renumber[id] ?: id] = row
      mediaStoreVersion = newVersion
    }
  }

  /** Programma [event] sulla riga [id] dopo [afterBytes] byte letti da un'apertura. Scatta una volta sola. */
  fun duringRead(id: Long, afterBytes: Long, event: MidRead) {
    synchronized(lock) { triggers += Trigger(id, afterBytes, event) }
  }

  /** Quanti eventi programmati sono scattati. */
  fun firedEvents(): Int = synchronized(lock) { triggers.count { it.fired } }

  /** Lo SHA-256 dei byte di una riga adesso (per il verificatore degli invarianti). */
  fun sha256(id: Long): Sha256? = synchronized(lock) { rows[id]?.content }?.let { sha256Of(it) }

  /** Gli SHA-256 di tutte le righe adesso. */
  fun allSha256(): Set<Sha256> = synchronized(lock) { rows.values.map { it.content } }.mapTo(LinkedHashSet()) { sha256Of(it) }

  private fun check(ref: PhoneRef): Row? {
    if (ref.volume != volumeName) return null
    return rows[ref.mediaStoreId]
  }

  private inner class Port : PhoneBytes {
    override suspend fun lookup(ref: PhoneRef): PhoneLookup = synchronized(lock) {
      denied?.let { return PhoneLookup.Denied(it) }
      if (ref.mediaStoreVersion != mediaStoreVersion) return PhoneLookup.Stale
      val row = check(ref) ?: return PhoneLookup.Gone
      PhoneLookup.Present(FileStamp(row.stampSize, row.modifiedMs, row.generation))
    }

    override suspend fun open(ref: PhoneRef): PhoneOpen = synchronized(lock) {
      denied?.let { return PhoneOpen.Denied(it) }
      if (ref.mediaStoreVersion != mediaStoreVersion) return PhoneOpen.Stale
      val row = check(ref) ?: return PhoneOpen.Gone
      opens++
      val stamp = FileStamp(row.stampSize, row.modifiedMs, row.generation)
      PhoneOpen.Opened(PhoneFile(OpenRow(ref.mediaStoreId, row.content), stamp))
    }
  }

  /** Una riga aperta: legge il contenuto del momento (un file riscritto sul posto si vede da li' in poi); dopo la sparizione fallisce. */
  private inner class OpenRow(private var id: Long, private var content: NasContent) : MediaBytes {
    private var readHere = 0L
    private var vanished = false

    override val size: Long get() = content.size

    override suspend fun read(range: LongRange): ByteArray {
      if (vanished) throw IOException("Riga sparita durante la lettura.")
      if (range.isEmpty() || range.first >= content.size) return ByteArray(0)
      val from = maxOf(range.first, 0L)
      val to = minOf(range.last, content.size - 1)
      val out = ByteArray((to - from + 1).toInt())
      content.copy(from, out, 0, out.size)
      readHere += out.size
      synchronized(lock) {
        bytesRead += out.size
        for (t in triggers) {
          if (t.fired || t.id != id || readHere < t.afterBytes) continue
          t.fired = true
          when (val e = t.event) {
            is MidRead.Rewritten -> {
              val row = rows[id]
              if (row != null) {
                row.content = e.content
                row.stampSize = e.content.size
                row.modifiedMs = e.modifiedMs
                row.generation = ++generation
                content = e.content
              }
            }
            MidRead.Vanished -> {
              rows.remove(id)
              vanished = true
            }
            is MidRead.Renumbered -> {
              val row = rows.remove(id)
              if (row != null) rows[e.newId] = row
              mediaStoreVersion = e.newVersion
              id = e.newId
            }
          }
        }
      }
      return out
    }

    override fun dataSource(): MediaDataSource = object : MediaDataSource() {
      override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= content.size) return -1
        val n = minOf(size.toLong(), content.size - position).toInt()
        content.copy(position, buffer, offset, n)
        return n
      }

      override fun getSize(): Long = content.size

      override fun close() = Unit
    }
  }

  companion object {
    const val VOLUME: String = "external_primary"

    /** Lo SHA-256 di un contenuto (anche virtuale, a pezzi da 1 MiB). */
    fun sha256Of(content: NasContent): Sha256 {
      val md = MessageDigest.getInstance("SHA-256")
      val buffer = ByteArray(1 shl 20)
      var at = 0L
      while (at < content.size) {
        val n = minOf(buffer.size.toLong(), content.size - at).toInt()
        content.copy(at, buffer, 0, n)
        md.update(buffer, 0, n)
        at += n
      }
      return Sha256(md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }
  }
}
