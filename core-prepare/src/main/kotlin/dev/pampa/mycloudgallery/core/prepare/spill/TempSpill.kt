package dev.pampa.mycloudgallery.core.prepare.spill

import android.annotation.SuppressLint
import dev.pampa.mycloudgallery.core.trust.nas.LocalNames
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * I temporanei della preparazione (`cacheDir/prep/`, §2.4): una foto oltre 64 MiB (DNG, 200 MP, panorami) si copia qui,
 * con l'hash che segue la copia, e si decodifica da file. Il nome viene da [LocalNames] (un hash, mai testo letto dal NAS)
 * e il file sparisce appena il media e' preparato. Se il processo muore resta un orfano: [sweepStale] lo toglie al giro dopo.
 *
 * Non conosce l'ora: un orfano si riconosce dal non essere nell'elenco di quelli in uso, non dalla data.
 */
internal class TempSpill(private val dir: File) {

  private val live: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** Un file nuovo e vuoto, nell'elenco di quelli in uso. */
  fun create(): File {
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cartella dei temporanei non creabile.")
    val file = File(dir, LocalNames.of("prep", UUID.randomUUID().toString()))
    if (!file.createNewFile()) throw IOException("Temporaneo gia' presente.")
    live += file.name
    return file
  }

  /** Il file non serve piu': si toglie dall'elenco e dal disco. */
  fun release(file: File) {
    live -= file.name
    file.delete()
  }

  /**
   * Spazio libero dove stanno i temporanei (la cartella o, se non c'e' ancora, il suo primo genitore che esiste). Basta una stima
   * prudente: i temporanei sono in `cacheDir`, che il sistema libera da solo, e il margine di 64 MiB copre il resto.
   */
  @SuppressLint("UsableSpace")
  fun usableBytes(): Long {
    var d: File? = dir
    while (d != null && !d.exists()) d = d.parentFile
    return d?.usableSpace ?: 0L
  }

  /** Toglie i temporanei che nessuno usa (orfani di un processo morto). Restituisce quanti. */
  fun sweepStale(): Int {
    val files = dir.listFiles() ?: return 0
    var removed = 0
    for (f in files) {
      if (f.isFile && f.name !in live && f.delete()) removed++
    }
    return removed
  }

  /** Quanti temporanei sono in uso adesso (per i test). */
  fun liveCount(): Int = live.size
}
