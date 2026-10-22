package dev.pampa.mycloudgallery.core.ai.cutout

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Il pacchetto `.task` di MediaPipe (MagicTouch v2): un archivio ZIP **senza compressione** (metodo 0, "stored") con il codificatore,
 * il decodificatore e due file di servizio. Il pacchetto resta un file solo nell'APK e nel catalogo (una impronta sola); qui se ne
 * leggono i due modelli come fette del buffer mappato, senza copiarli.
 *
 * Il file e' gia' stato verificato (dimensione e SHA-256, D16): qui si controlla comunque che ogni lunghezza resti dentro il buffer e
 * che le voci non siano compresse; un pacchetto che non torna lancia `IllegalArgumentException`.
 */
internal object TaskBundle {

  class Parts(val encoder: ByteBuffer, val decoder: ByteBuffer)

  fun parts(bundle: ByteBuffer): Parts {
    val buffer = bundle.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val entries = entries(buffer)
    val encoder = entries.entries.firstOrNull { it.key.contains("encoder") && it.key.endsWith(".tflite") }
    val decoder = entries.entries.firstOrNull { it.key.contains("decoder") && it.key.endsWith(".tflite") }
    require(encoder != null && decoder != null) { "Codificatore o decodificatore assenti." }
    return Parts(encoder.value, decoder.value)
  }

  /** Nome -> fetta (nell'ordine nativo, come vuole l'interprete) di ogni voce non compressa. */
  fun entries(buffer: ByteBuffer): Map<String, ByteBuffer> {
    val le = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val total = le.limit()
    // La fine dell'archivio: l'ultima firma EOCD nei 64 KiB + 22 byte finali.
    var eocd = -1
    var p = total - EOCD_MIN
    val stop = maxOf(0, total - EOCD_MIN - 0xFFFF)
    while (p >= stop) {
      if (le.getInt(p) == EOCD_SIGNATURE) {
        eocd = p
        break
      }
      p--
    }
    require(eocd >= 0) { "Fine dell'archivio non trovata." }
    val count = le.getShort(eocd + 10).toInt() and 0xFFFF
    val directorySize = le.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
    val directoryOffset = le.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
    require(directoryOffset + directorySize <= total) { "Elenco fuori dal file." }
    val out = LinkedHashMap<String, ByteBuffer>()
    var q = directoryOffset.toInt()
    repeat(count) {
      require(q + CENTRAL_MIN <= total && le.getInt(q) == CENTRAL_SIGNATURE) { "Voce dell'elenco non valida." }
      val method = le.getShort(q + 10).toInt() and 0xFFFF
      val compressed = le.getInt(q + 20).toLong() and 0xFFFFFFFFL
      val size = le.getInt(q + 24).toLong() and 0xFFFFFFFFL
      val nameLength = le.getShort(q + 28).toInt() and 0xFFFF
      val extraLength = le.getShort(q + 30).toInt() and 0xFFFF
      val commentLength = le.getShort(q + 32).toInt() and 0xFFFF
      val local = le.getInt(q + 42).toLong() and 0xFFFFFFFFL
      require(q + CENTRAL_MIN + nameLength <= total) { "Nome fuori dal file." }
      val nameBytes = ByteArray(nameLength)
      le.duplicate().apply { position(q + CENTRAL_MIN) }.get(nameBytes)
      val name = String(nameBytes, Charsets.UTF_8)
      require(!name.startsWith("/") && !name.contains("..")) { "Nome sospetto." }
      require(method == 0 && compressed == size) { "Voce compressa: il pacchetto va salvato senza compressione." }
      require(local + LOCAL_MIN <= total && le.getInt(local.toInt()) == LOCAL_SIGNATURE) { "Intestazione locale non valida." }
      val localName = le.getShort(local.toInt() + 26).toInt() and 0xFFFF
      val localExtra = le.getShort(local.toInt() + 28).toInt() and 0xFFFF
      val start = local + LOCAL_MIN + localName + localExtra
      require(start + size <= total) { "Dati fuori dal file." }
      val slice = buffer.duplicate().apply {
        limit((start + size).toInt())
        position(start.toInt())
      }.slice().order(ByteOrder.nativeOrder())
      out[name] = slice
      q += CENTRAL_MIN + nameLength + extraLength + commentLength
    }
    return out
  }

  private const val EOCD_SIGNATURE = 0x06054b50
  private const val CENTRAL_SIGNATURE = 0x02014b50
  private const val LOCAL_SIGNATURE = 0x04034b50
  private const val EOCD_MIN = 22
  private const val CENTRAL_MIN = 46
  private const val LOCAL_MIN = 30
}
