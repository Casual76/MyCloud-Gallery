package dev.pampa.mycloudgallery.core.models.manager

import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Gli ERRORI DELLE SORGENTI (E-A2): "dopo due errori della stessa sorgente in un giorno la sorgente si salta fino al giorno dopo".
// Una sorgente che da' un file sbagliato (un dispositivo compromesso, un CDN rotto) non si riprova a ogni giro del lavoro: costerebbe
// una copia da 370 MiB ogni volta. Vale per i lavori automatici; chi preme "Scarica ora" riprova sempre (nessun blocco per l'utente).
//
// Il "giorno" e' una scadenza: si misura con l'ora del NAS (`NasClock`), mai con quella del telefono (D39), e si ricorda anche dopo
// la morte del processo (i lavori di WorkManager vivono pochi minuti). Un orologio che torna indietro o salta di piu' di un giorno
// azzera la voce: il peggio che puo' succedere e' riprovare una sorgente.

internal class SourceStrikes(private val file: File?, private val nowMillis: () -> Long) {

  private class Strike(var count: Int, var firstAtMillis: Long)

  private val lock = Any()
  private val strikes = LinkedHashMap<String, Strike>()

  init {
    load()
  }

  /** True se [key] ha preso [ModelLimits.STRIKES_BEFORE_SKIP] errori nell'ultimo giorno. */
  fun isQuarantined(key: String): Boolean =
    synchronized(lock) {
      val strike = strikes[key] ?: return false
      if (expired(strike)) {
        strikes.remove(key)
        persist()
        return false
      }
      strike.count >= ModelLimits.STRIKES_BEFORE_SKIP
    }

  /** Un errore di [key] (file sbagliato, redirezione fuori elenco). */
  fun strike(key: String) {
    synchronized(lock) {
      val now = nowMillis()
      val current = strikes[key]?.takeUnless { expired(it) }
      if (current == null) strikes[key] = Strike(1, now) else current.count++
      persist()
    }
  }

  /** [key] ha dato un file buono: i suoi errori si dimenticano. */
  fun clear(key: String) {
    synchronized(lock) { if (strikes.remove(key) != null) persist() }
  }

  private fun expired(strike: Strike): Boolean {
    val age = nowMillis() - strike.firstAtMillis
    return age < 0 || age >= ModelLimits.QUARANTINE.inWholeMilliseconds
  }

  private fun load() {
    val source = file ?: return
    try {
      if (!source.isFile || source.length() > MAX_BYTES) return
      source.readLines(Charsets.UTF_8).take(MAX_ENTRIES).forEach { line ->
        val parts = line.split(' ')
        val count = parts.getOrNull(1)?.toIntOrNull()
        val first = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size == 3 && count != null && first != null && count in 1..1000 && KEY.matches(parts[0])) {
          strikes[parts[0]] = Strike(count, first)
        }
      }
    } catch (e: IOException) {
      strikes.clear()
    }
  }

  private fun persist() {
    val target = file ?: return
    try {
      target.parentFile?.mkdirs()
      val temp = File(target.path + ".tmp")
      temp.writeText(strikes.entries.joinToString("\n") { (k, s) -> "$k ${s.count} ${s.firstAtMillis}" }, Charsets.UTF_8)
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
      // Best effort: senza il file la memoria si perde con il processo, non altro.
    }
  }

  private companion object {
    const val MAX_BYTES = 64 * 1024L
    const val MAX_ENTRIES = 512
    val KEY = Regex("^[A-Za-z0-9._:-]{1,200}$")
  }
}
