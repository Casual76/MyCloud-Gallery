package dev.pampa.mycloudgallery.backup

import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.PhoneBytes
import dev.pampa.mycloudgallery.core.backup.contract.PhoneDenied
import dev.pampa.mycloudgallery.core.backup.contract.PhoneFile
import dev.pampa.mycloudgallery.core.backup.contract.PhoneLookup
import dev.pampa.mycloudgallery.core.backup.contract.PhoneOpen
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaAccess
import dev.pampa.mycloudgallery.core.mediastore.contract.MediaStoreRow
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneAccess
import dev.pampa.mycloudgallery.core.mediastore.android.ContentUriMediaBytes
import dev.pampa.mycloudgallery.core.mediastore.contract.RowType
import dev.pampa.mycloudgallery.core.mediastore.sync.OriginalBytesUnavailableException
import dev.pampa.mycloudgallery.core.metadata.contract.MediaBytes
import dev.pampa.mycloudgallery.gallery.PhoneRowsSource
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * La porta del telefono del backup (Fase 5A, P7; docs/fase5-progetto.md §1.2 `PhoneBytes`, D10, D13): i byte **originali** di una riga
 * del MediaStore, sopra [PhoneRowsSource] (`AndroidMediaStoreSource`: le foto con `setRequireOriginal` e `ACCESS_MEDIA_LOCATION`, mai
 * byte oscurati; i video in streaming). Nella 5A **sola lettura**: nessuna scrittura sul telefono.
 *
 * - L'`_ID` di [PhoneRef] vale solo con la versione del MediaStore con cui e' stato letto (E-P12, E-P22): se il volume ne ha una nuova la
 *   riga e' [PhoneLookup.Stale] e la ritrova il catalogo (`phoneRowOf`).
 * - Un permesso ritirato non e' mai una riga sparita: [PhoneDenied.MEDIA_PERMISSION] (E-P15); con l'accesso parziale una riga che non si
 *   vede e' [PhoneDenied.NOT_SELECTED], mai "persa prima del backup" (E-P1: nessuna deduzione dall'assenza); senza la posizione delle foto
 *   [PhoneDenied.MEDIA_LOCATION] (E-P21).
 * - **D13**: con la recinzione del telefono (debug con gli account di prova) una riga fuori da `DCIM/mcg-prova/` e `Pictures/mcg-prova/`
 *   non si apre mai ([PhoneDenied.NOT_SELECTED], fallire chiusi).
 * - **R6 della revisione 5A**: la dimensione del MediaStore puo' essere rimasta indietro (un file che cresce in piu' sessioni, la memoria
 *   condivisa di Android 10). I byte consegnati dicono la dimensione **vera** del descrittore aperto, adesso ([LiveSizeBytes]): il motore, dopo
 *   aver letto `size` byte, la confronta con quella del MediaStore e cerca la fine del file; se non coincidono la copia sarebbe un prefisso e
 *   il file e' cambiato (E-B15).
 * - Un file che non si apre per un errore di I/O non e' ne' sparito ne' vietato: si consegna lo stesso e la lettura fallisce a modo suo,
 *   cosi' il motore conta i tentativi e dopo tre dice `UNREADABLE` (D15).
 *
 * Nei log niente: nessun nome, percorso o `_ID`.
 */
class MediaStorePhoneBytes(
  private val rows: () -> PhoneRowsSource?,
  private val access: () -> PhoneAccess,
  private val fence: BackupFence? = null,
  private val account: () -> String? = { null },
) : PhoneBytes {

  private sealed interface Found {
    class Row(val row: MediaStoreRow) : Found

    data object Gone : Found

    data object Stale : Found

    class Denied(val reason: PhoneDenied) : Found
  }

  override suspend fun lookup(ref: PhoneRef): PhoneLookup = when (val found = find(ref)) {
    is Found.Row -> PhoneLookup.Present(found.row.stamp())
    Found.Gone -> PhoneLookup.Gone
    Found.Stale -> PhoneLookup.Stale
    is Found.Denied -> PhoneLookup.Denied(found.reason)
  }

  override suspend fun open(ref: PhoneRef): PhoneOpen {
    val row = when (val found = find(ref)) {
      is Found.Row -> found.row
      Found.Gone -> return PhoneOpen.Gone
      Found.Stale -> return PhoneOpen.Stale
      is Found.Denied -> return PhoneOpen.Denied(found.reason)
    }
    val source = rows()?.source ?: return PhoneOpen.Stale
    val current = access()
    // Una foto senza la posizione si leggerebbe oscurata: mai un'impronta di byte che non sono quelli del file (E-P21).
    if (row.type == RowType.IMAGE && !current.mediaLocation) return PhoneOpen.Denied(PhoneDenied.MEDIA_LOCATION)
    val bytes: MediaBytes = try {
      source.open(row).let { opened -> if (opened is ContentUriMediaBytes) LiveSizeBytes(opened) else opened }
    } catch (e: CancellationException) {
      throw e
    } catch (_: OriginalBytesUnavailableException) {
      return PhoneOpen.Denied(PhoneDenied.MEDIA_LOCATION)
    } catch (_: SecurityException) {
      return PhoneOpen.Denied(if (access().media == MediaAccess.PARTIAL) PhoneDenied.NOT_SELECTED else PhoneDenied.MEDIA_PERMISSION)
    } catch (_: FileNotFoundException) {
      // Sparito fra la ricerca e l'apertura (E-B4): la riga non c'e' piu'.
      return if (find(ref) is Found.Row) PhoneOpen.Opened(PhoneFile(UnreadableBytes(row.size), row.stamp())) else PhoneOpen.Gone
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "file del telefono non aperto (${e.javaClass.simpleName})" }
      UnreadableBytes(row.size)
    }
    return PhoneOpen.Opened(PhoneFile(bytes, row.stamp()) { (bytes as? AutoCloseable)?.close() })
  }

  private suspend fun find(ref: PhoneRef): Found {
    val phone = rows() ?: return Found.Stale
    val current = access()
    if (current.media == MediaAccess.NONE) return Found.Denied(PhoneDenied.MEDIA_PERMISSION)
    val row = try {
      if (phone.source.version(ref.volume) != ref.mediaStoreVersion) return Found.Stale
      phone.lookup.rowsById(ref.volume, listOf(ref.mediaStoreId)).firstOrNull { it.id == ref.mediaStoreId && it.volume == ref.volume }
    } catch (e: CancellationException) {
      throw e
    } catch (_: SecurityException) {
      return Found.Denied(if (access().media == MediaAccess.PARTIAL) PhoneDenied.NOT_SELECTED else PhoneDenied.MEDIA_PERMISSION)
    } catch (e: IOException) {
      // Il volume non risponde (microSD tolta, MediaStore occupato): non e' una riga persa, l'elemento aspetta.
      GalleryLog.w(TAG) { "MediaStore non letto (${e.javaClass.simpleName})" }
      return Found.Stale
    }
    if (row == null) {
      // Con "foto selezionate" una riga che non si vede puo' essere solo non scelta: nessuna deduzione dall'assenza (E-P1).
      return if (current.media == MediaAccess.PARTIAL) Found.Denied(PhoneDenied.NOT_SELECTED) else Found.Gone
    }
    if (!allowedByFence(row)) return Found.Denied(PhoneDenied.NOT_SELECTED)
    return Found.Row(row)
  }

  /** D13: con una recinzione e senza account non si legge niente (fallire chiusi). */
  private fun allowedByFence(row: MediaStoreRow): Boolean {
    val fence = fence ?: return true
    val account = account() ?: return false
    return fence.allowsPhoneFolder(account, row.relativePath)
  }

  /**
   * I byte di una riga del MediaStore con la dimensione **di adesso** (R6): [size] e' un `fstat` del descrittore a ogni lettura, non quella
   * del momento dell'apertura. Le letture e la sorgente per i lettori di video sono quelle di [inner], che si chiude con lui.
   */
  private class LiveSizeBytes(private val inner: ContentUriMediaBytes) : MediaBytes by inner, AutoCloseable by inner {
    override val size: Long get() = inner.currentSize()
  }

  /** Un file che non si lascia aprire: ogni lettura fallisce con un errore di I/O (il motore conta i tentativi, D15). */
  private class UnreadableBytes(override val size: Long) : MediaBytes {
    override suspend fun read(range: LongRange): ByteArray = throw IOException("File del telefono non leggibile.")

    override fun dataSource(): android.media.MediaDataSource = throw IOException("File del telefono non leggibile.")
  }

  private companion object {
    const val TAG = "BackupPhone"
  }
}
