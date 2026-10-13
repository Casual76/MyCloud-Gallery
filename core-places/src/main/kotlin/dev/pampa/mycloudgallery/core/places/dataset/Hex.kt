package dev.pampa.mycloudgallery.core.places.dataset

/** L'esadecimale minuscolo dei byte, senza `String.format` (che dipende dalla lingua del sistema). Lo usa anche lo strumento. */
internal object Hex {
  private const val DIGITS = "0123456789abcdef"

  fun of(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
      out.append(DIGITS[(b.toInt() shr 4) and 0xF]).append(DIGITS[b.toInt() and 0xF])
    }
    return out.toString()
  }
}
