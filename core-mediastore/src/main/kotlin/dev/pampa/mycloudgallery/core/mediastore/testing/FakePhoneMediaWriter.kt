package dev.pampa.mycloudgallery.core.mediastore.testing

import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * "Scarica sul telefono" finto (Fase 4, P0): scrive in memoria in `Pictures/MyCloud Gallery/`, nomi in conflitto come
 * "nome (2).jpg", SHA-256 calcolato mentre arriva, [PhoneMediaWriter.saveToPictures] chiama `beforePublish` **prima** di
 * rendere visibile il file ([visible]). Una sorgente che si interrompe non lascia niente di visibile.
 */
class FakePhoneMediaWriter : PhoneMediaWriter {

  /** I file pubblicati (`IS_PENDING = 0`), per nome. */
  val visible: MutableMap<String, ByteArray> = LinkedHashMap()

  /** Quante righe in attesa sono rimaste a meta' (deve restare 0). */
  var pendingLeft: Int = 0
    private set

  private var nextId = 1000L

  override suspend fun saveToPictures(
    request: SaveRequest,
    source: PhoneSaveSource,
    expectedSha256: Sha256?,
    progress: (Long) -> Unit,
    beforePublish: suspend (PendingCopy) -> Unit,
  ): SaveResult {
    val name = freeName(request.displayName)
    val out = ByteArrayOutputStream()
    val digest = MessageDigest.getInstance("SHA-256")
    pendingLeft++
    try {
      source.copyTo { buf, n ->
        out.write(buf, 0, n)
        digest.update(buf, 0, n)
        progress(out.size().toLong())
      }
    } catch (e: IOException) {
      pendingLeft--
      return SaveResult.Failed("SOURCE_FAILED")
    }
    val sha = Sha256(digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    if (expectedSha256 != null && expectedSha256 != sha) {
      pendingLeft--
      return SaveResult.Failed("HASH_MISMATCH")
    }
    val copy = PendingCopy(Location.Phone("external_primary", RELATIVE_PATH, name, mediaStoreId = nextId++), sha, out.size().toLong())
    beforePublish(copy)
    visible[name] = out.toByteArray()
    pendingLeft--
    return SaveResult.Saved(copy)
  }

  private fun freeName(wanted: String): String {
    if (wanted !in visible) return wanted
    val base = wanted.substringBeforeLast('.')
    val ext = wanted.substringAfterLast('.', missingDelimiterValue = "")
    var n = 2
    while (true) {
      val candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
      if (candidate !in visible) return candidate
      n++
    }
  }

  companion object {
    const val RELATIVE_PATH: String = "Pictures/MyCloud Gallery/"
  }
}
