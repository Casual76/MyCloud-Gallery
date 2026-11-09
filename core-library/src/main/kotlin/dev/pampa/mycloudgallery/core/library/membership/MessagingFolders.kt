package dev.pampa.mycloudgallery.core.library.membership

import java.util.Locale

/**
 * Fase grafica (G2b): le cartelle in cui **Telegram** e **Signal** salvano foto e video su Android, per la voce Messaggistica (WhatsApp ha gia'
 * la sua sorgente, `MediaSource.WHATSAPP`, decisa da `SourceClassifier`). Funzioni pure: le usa la lettura del catalogo
 * ([CatalogFilterMembership]) sui percorsi delle posizioni, e le provano i test.
 *
 * **Telefono** (percorso relativo del MediaStore, maiuscole e minuscole non contano):
 * - Telegram: `Pictures/Telegram/`, `Movies/Telegram/`, `DCIM/Telegram/`, `Download/Telegram/` (le versioni da Android 10), la cartella storica
 *   `Telegram/` (`Telegram Images`, `Telegram Video`...), e `Android/media/<pacchetto>/` di Telegram, Telegram Web, Plus Messenger e Telegram X;
 * - Signal: `Pictures/Signal/`, `Movies/Signal/`, `Download/Signal/`, la cartella storica `Signal/`, e `Android/media/org.thoughtcrime.securesms/`.
 *
 * **NAS** (percorso dentro la share): le copie del backup di quelle cartelle, `Backup/<dispositivo>/[microSD/]<nome>/...`, dove `<nome>` e' l'ultima
 * parte della cartella del telefono (`BackupPaths.sourceFolders`): `Telegram`, `Telegram Images`, `Telegram Video`, `Signal`. Cosi' un media resta
 * in Messaggistica anche dopo "Libera spazio".
 *
 * Come per WhatsApp, gli adesivi (`stickers`) restano fuori.
 */
object MessagingFolders {

  private val PHONE_PREFIXES = listOf(
    "pictures/telegram/", "movies/telegram/", "dcim/telegram/", "download/telegram/", "telegram/",
    "android/media/org.telegram.messenger/", "android/media/org.telegram.messenger.web/", "android/media/org.telegram.plus/",
    "android/media/org.thunderdog.challegram/",
    "pictures/signal/", "movies/signal/", "download/signal/", "signal/",
    "android/media/org.thoughtcrime.securesms/",
  )

  /** Le cartelle del backup (in forma di confronto) che vengono da una cartella di Telegram o di Signal. */
  private val BACKUP_FOLDERS = setOf("telegram", "telegram images", "telegram video", "signal")

  private const val BACKUP_ROOT = "backup"
  private const val REMOVABLE = "microsd"

  /** Le parole che una riga del catalogo deve contenere per essere guardata (il filtro grezzo in SQL, `LIKE` senza maiuscole). */
  val CATALOG_HINTS: List<String> = listOf("telegram", "signal", "challegram", "securesms")

  /** Se il percorso relativo [relativePath] di una posizione del telefono e' una cartella di Telegram o di Signal. */
  fun isPhoneFolder(relativePath: String?): Boolean {
    val path = normalize(relativePath ?: return false)
    if (path.contains("stickers")) return false
    return PHONE_PREFIXES.any { path.startsWith(it) }
  }

  /** Se [nasPath], un file in una share del NAS, e' la copia del backup di una cartella di Telegram o di Signal. */
  fun isBackupFile(nasPath: String?): Boolean {
    val segments = normalize(nasPath ?: return false).split('/').filter { it.isNotEmpty() }
    // Backup / dispositivo / [microSD /] cartella / ... / file
    if (segments.size < 4 || segments[0] != BACKUP_ROOT) return false
    if (segments.any { it.contains("stickers") }) return false
    val folder = if (segments[2] == REMOVABLE && segments.size >= 5) segments[3] else segments[2]
    return folder in BACKUP_FOLDERS
  }

  /** Minuscolo, con `/`, senza `/` iniziale e con `/` finale: come `SourceClassifier`. */
  private fun normalize(path: String): String {
    val p = path.replace('\\', '/').trim().trimStart('/').lowercase(Locale.ROOT)
    return if (p.isEmpty() || p.endsWith("/")) p else "$p/"
  }
}
