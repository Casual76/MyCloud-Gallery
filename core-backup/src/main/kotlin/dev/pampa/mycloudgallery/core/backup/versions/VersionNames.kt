package dev.pampa.mycloudgallery.core.backup.versions

import dev.pampa.mycloudgallery.core.backup.plan.BackupPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * I nomi delle versioni in `Versioni/` (D17, Q2 (a)): la cartella `Versioni/` alla radice della share rispecchia il percorso del file
 * ("Versioni/Backup/S25 di Mario/2026/10/"), e il nome dice che cosa e': "IMG_1234 (originale).jpg", "IMG_1234 (versione del 2026-10-15
 * 14.32).jpg". L'ora e' quella del NAS del file (mai l'orologio del telefono, D31), mostrata nel fuso [zone]. Un nome che con l'etichetta
 * supererebbe i 255 byte si accorcia nella radice, l'estensione resta (come `BackupPaths.alternateName`, R1 della revisione 5A); un nome
 * occupato da un altro file diventa "… (2)", "… (3)" ([withAttempt]). Puri e deterministici: lo stesso ingresso da' sempre lo stesso nome.
 */
object VersionNames {

  const val ORIGINAL_LABEL: String = "originale"

  private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm", Locale.ROOT)

  /** "versione del 2026-10-15 14.32" per un file scritto sul NAS all'istante [nasMillis] (ora del NAS). */
  fun versionLabel(nasMillis: Long, zone: ZoneId): String = "versione del " + STAMP.format(Instant.ofEpochMilli(nasMillis).atZone(zone))

  /** Il nome in `Versioni/` di un file del solito percorso [usualPath], con l'etichetta [label] ("originale", "versione del …"). */
  fun inVersions(usualPath: SharePath, label: String): SharePath {
    val mirror = VersionPaths.mirrorOf(usualPath)
    return mirror.parent()!!.child(labelled(usualPath.name, label))
  }

  /** Il tentativo [attempt] dopo nomi occupati da file diversi: 0 = [base], 1 = "… (2)". */
  fun withAttempt(base: SharePath, attempt: Int): SharePath = BackupPaths.alternateName(base, attempt)

  /** "IMG_1234.jpg" + "originale" = "IMG_1234 (originale).jpg"; la radice si accorcia se serve, l'estensione resta. */
  internal fun labelled(name: String, label: String): String {
    val suffix = " ($label)"
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && name.length - dot <= MAX_EXTENSION_CHARS) name.substring(dot) else ""
    val stem = name.substring(0, name.length - extension.length)
    var out = stem + suffix + extension
    if (out.toByteArray(Charsets.UTF_8).size > TrustLimits.MAX_COMPONENT_UTF8_BYTES) {
      val room = TrustLimits.MAX_COMPONENT_UTF8_BYTES - suffix.toByteArray(Charsets.UTF_8).size - extension.toByteArray(Charsets.UTF_8).size
      out = BackupPaths.cutToBytes(stem, room) + suffix + extension
    }
    return if (SharePath.isValidComponent(out)) out else BackupPaths.component(out, BackupPaths.FALLBACK_FILE)
  }

  private const val MAX_EXTENSION_CHARS = 12
}
