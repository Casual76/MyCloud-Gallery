package dev.pampa.mycloudgallery.core.models.download

import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// Il FILE PARZIALE di uno scaricamento (docs/fase6-progetto.md §2.1: `models/partial/<sha256>.part`, blocchi da 1 MiB; E-A1). Lo
// scrivono tutti i fetcher (HTTPS, NAS, cartella dal PC, asset): una sola regola di ripresa e una sola scrittura.
//
// **Regola**: si scrive per BLOCCHI interi da 1 MiB (l'ultimo e' il resto), ognuno sincronizzato su disco prima di registrare il
// traffico. Un'interruzione, anche a processo morto, lascia un file lungo un numero intero di blocchi piu' al piu' una coda: la
// ripresa riparte dall'ultimo confine di blocco e butta la coda, cosi' costa al piu' 1 MiB riscaricato (invariante 3 del §5.5) e la
// coda non verificata (una scrittura a meta') non entra mai nel file. Mai piu' byte della dimensione fissata.

/** Un file parziale aperto in scrittura, posizionato alla fine dei blocchi gia' buoni. */
internal class PartialFile private constructor(private val channel: FileChannel, startAt: Long) : Closeable {

  /** Quanti byte buoni ci sono (sono tutti sincronizzati su disco, tranne l'ultimo blocco appena scritto). */
  var length: Long = startAt
    private set

  /** Aggiunge [len] byte di [buf] e li sincronizza. Un [IOException] (anche disco pieno) risale a chi chiama. */
  fun append(buf: ByteArray, len: Int) {
    val bytes = ByteBuffer.wrap(buf, 0, len)
    while (bytes.hasRemaining()) channel.write(bytes)
    channel.force(false)
    length += len
  }

  override fun close() = channel.close()

  companion object {
    /**
     * Da dove si riprende: 0 se il file non c'e'; [total] se e' gia' lungo quanto il modello (resta da verificare l'impronta); il
     * confine di blocco sotto la sua lunghezza altrimenti. Un file piu' lungo del modello e' un errore (non puo' esserlo per
     * costruzione): si toglie e si riparte da zero. Non modifica il file tranne in quel caso: lo accorcia [open].
     */
    fun resumePoint(file: File, total: Long): Long {
      if (!file.isFile) return 0
      val length = file.length()
      return when {
        length > total -> {
          file.delete()
          0
        }
        length == total -> total
        else -> length / ModelLimits.BLOCK_BYTES * ModelLimits.BLOCK_BYTES
      }
    }

    /** Apre [file] (e crea le cartelle) accorciandolo a [at], che deve essere un [resumePoint] di questo file. */
    fun open(file: File, at: Long): PartialFile {
      file.parentFile?.mkdirs()
      val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try {
        if (channel.size() > at) channel.truncate(at)
        if (channel.size() < at) throw IOException("File parziale piu' corto del punto di ripresa.")
        channel.position(at)
      } catch (t: Throwable) {
        channel.close()
        throw t
      }
      return PartialFile(channel, at)
    }

    /** Vero se [e] dice che il disco e' pieno (Linux/Android `ENOSPC`, Windows `ERROR_DISK_FULL`). */
    fun isNoSpace(e: IOException): Boolean {
      val message = e.message?.lowercase().orEmpty()
      return "no space left" in message || "not enough space" in message || "disk full" in message || "quota exceeded" in message
    }
  }
}
