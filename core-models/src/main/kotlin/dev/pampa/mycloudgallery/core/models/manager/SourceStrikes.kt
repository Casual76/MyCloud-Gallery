package dev.pampa.mycloudgallery.core.models.manager

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import java.io.File
import java.io.IOException

// Gli ERRORI DELLE SORGENTI (E-A2): "dopo due errori della stessa sorgente in un giorno la sorgente si salta fino al giorno dopo".
// Una sorgente che da' un file sbagliato (un dispositivo compromesso, un CDN rotto) non si riprova a ogni giro del lavoro: costerebbe
// una copia da 370 MiB ogni volta. Vale per i lavori automatici; chi preme "Scarica ora" riprova sempre (nessun blocco per l'utente).
//
// Il "giorno" e' una scadenza: si misura con l'ora del NAS (`NasClock`), mai con quella del telefono (D39), e si ricorda anche dopo
// la morte del processo (i lavori di WorkManager vivono pochi minuti). Un orologio che torna indietro o salta di piu' di un giorno
// azzera la voce: il peggio che puo' succedere e' riprovare una sorgente.
//
// **Senza l'ora del NAS** (R10 della revisione di sicurezza della Fase 6: fuori casa senza VPN, o in un processo che non ha ancora fatto un
// accesso SMB) `NasClock.nowMillis()` lancia. Prima lanciava anche qui, fuori da ogni `try`: la sorgente dopo non si provava, il lavoro cadeva e
// il giro dopo si riscaricava tutto. Adesso [nowMillis] puo' dire "non so" (null):
//  - [strike] ripiega sull'orologio **monotono** ([monotonicMillis]) e ricorda l'errore solo in memoria, per la vita del processo (un tempo
//    monotono non vale dopo un riavvio, quindi non si scrive su disco);
//  - [isQuarantined] risponde **prudente**: una sorgente con gli errori sul disco di cui non si puo' dire se e' scaduto resta saltata
//    (costerebbe 157-370 MB), e la voce non si toglie. Si sblocca quando l'ora del NAS torna. "Scarica ora" non guarda mai la quarantena.

internal class SourceStrikes(
  private val file: File?,
  private val nowMillis: () -> Long?,
  private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {

  private class Strike(var count: Int, var firstAtMillis: Long)

  private val lock = Any()
  private val strikes = LinkedHashMap<String, Strike>()

  /** Gli errori presi senza l'ora del NAS: solo in memoria, con l'orologio monotono. */
  private val volatileStrikes = HashMap<String, Strike>()

  init {
    load()
  }

  /** True se [key] ha preso [ModelLimits.STRIKES_BEFORE_SKIP] errori nell'ultimo giorno. */
  fun isQuarantined(key: String): Boolean =
    synchronized(lock) {
      if (volatileQuarantined(key)) return true
      val strike = strikes[key] ?: return false
      val now = nowMillis()
      if (now == null) {
        // Non si sa che ora e': non si toglie niente e si resta prudenti.
        return strike.count >= ModelLimits.STRIKES_BEFORE_SKIP
      }
      if (expired(strike, now)) {
        strikes.remove(key)
        persist()
        return false
      }
      strike.count >= ModelLimits.STRIKES_BEFORE_SKIP
    }

  /** Un errore di [key] (file sbagliato, redirezione fuori elenco). Mai un'eccezione: senza l'ora del NAS si ricorda in memoria. */
  fun strike(key: String) {
    synchronized(lock) {
      val now = nowMillis()
      if (now == null) {
        val mono = monotonicMillis()
        val current = volatileStrikes[key]?.takeUnless { monotonicExpired(it, mono) }
        if (current == null) volatileStrikes[key] = Strike(1, mono) else current.count++
        return
      }
      val current = strikes[key]?.takeUnless { expired(it, now) }
      if (current == null) strikes[key] = Strike(1, now) else current.count++
      persist()
    }
  }

  /** [key] ha dato un file buono: i suoi errori si dimenticano. */
  fun clear(key: String) {
    synchronized(lock) {
      volatileStrikes.remove(key)
      if (strikes.remove(key) != null) persist()
    }
  }

  private fun volatileQuarantined(key: String): Boolean {
    val strike = volatileStrikes[key] ?: return false
    if (monotonicExpired(strike, monotonicMillis())) {
      volatileStrikes.remove(key)
      return false
    }
    return strike.count >= ModelLimits.STRIKES_BEFORE_SKIP
  }

  private fun monotonicExpired(strike: Strike, now: Long): Boolean {
    val age = now - strike.firstAtMillis
    return age < 0 || age >= ModelLimits.QUARANTINE.inWholeMilliseconds
  }

  private fun expired(strike: Strike, now: Long): Boolean {
    val age = now - strike.firstAtMillis
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
      StateFiles.writeText(target, strikes.entries.joinToString("\n") { (k, s) -> "$k ${s.count} ${s.firstAtMillis}" }, Charsets.UTF_8)
    } catch (e: IOException) {
      // Best effort: senza il file la memoria si perde con il processo, non altro (l'errore e' nel log di `StateFiles`).
    }
  }

  private companion object {
    const val MAX_BYTES = 64 * 1024L
    const val MAX_ENTRIES = 512
    val KEY = Regex("^[A-Za-z0-9._:-]{1,200}$")
  }
}
