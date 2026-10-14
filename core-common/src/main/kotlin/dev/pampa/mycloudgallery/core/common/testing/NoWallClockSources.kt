package dev.pampa.mycloudgallery.core.common.testing

import java.io.File

/**
 * La regola dell'ora della Fase 4 (`docs/fase4-progetto.md` §4.3, D32; `docs/formato-nas.md` §6.1): nel codice che
 * decide scadenze, prenotazioni, stabilita' dei file, ordinamenti e istanti firmati si usa **solo** l'ora del NAS
 * (`NasClock`) oppure, per misurare durate, un orologio monotono. L'orologio di sistema del telefono no: oggi e' circa
 * 55 s indietro rispetto al NAS, e l'utente o la rete lo possono spostare.
 *
 * Ogni modulo nuovo della Fase 4 ha un `NoWallClockSourcesTest` che chiama [violations] sulla propria `src/main`.
 * Ammessi: `System.nanoTime`, `SystemClock.elapsedRealtime`, `TimeSource.Monotonic` (durate), `NasClock`.
 *
 * Come [NoNetworkSources], i commenti non contano (devono poter dire cosa si vieta). Sta nel package `testing`:
 * `tools/check.ps1` vieta questi package nel dex di release.
 */
object NoWallClockSources {

  /** I modi noti di leggere l'ora "da muro" del dispositivo. */
  val FORBIDDEN: List<NoNetworkSources.Forbidden> = listOf(
    NoNetworkSources.Forbidden(Regex("""\bcurrentTimeMillis\b"""), "System.currentTimeMillis (ora del telefono)"),
    NoNetworkSources.Forbidden(
      Regex("""\b(Instant|LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime|OffsetTime|Year|YearMonth|MonthDay)\s*(\.|::)\s*now\b"""),
      "java.time ...now() (ora del telefono)",
    ),
    NoNetworkSources.Forbidden(Regex("""\bClock\s*\.\s*(system\w*|System)\b"""), "Clock di sistema (java.time o kotlinx-datetime)"),
    NoNetworkSources.Forbidden(Regex("""\b(java\.util\.)?Date\s*\(\s*\)"""), "java.util.Date() (ora del telefono)"),
    NoNetworkSources.Forbidden(Regex("""\bCalendar\s*\.\s*getInstance\b|\bGregorianCalendar\b"""), "Calendar (ora del telefono)"),
    NoNetworkSources.Forbidden(Regex("""\bSystemClock\s*\.\s*current\w*"""), "SystemClock.current... (ora da muro)"),
  )

  /**
   * Le violazioni sotto [sourceRoot] (di solito `File("src/main")`): una riga `percorso:riga: perche'` per ognuna.
   * Una cartella che non esiste e' un errore (un controllo che non vede i sorgenti non controlla niente).
   */
  fun violations(sourceRoot: File): List<String> {
    require(sourceRoot.isDirectory) { "Non trovo i sorgenti da controllare: ${sourceRoot.path}" }
    val found = mutableListOf<String>()
    sourceRoot.walkTopDown()
      .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
      .sortedBy { it.path }
      .forEach { file ->
        val code = NoNetworkSources.stripComments(file.readText())
        code.lineSequence().forEachIndexed { index, line ->
          FORBIDDEN.filter { it.pattern.containsMatchIn(line) }.forEach {
            found += "${file.relativeTo(sourceRoot).path.replace('\\', '/')}:${index + 1}: ${it.why}"
          }
        }
      }
    return found
  }
}
