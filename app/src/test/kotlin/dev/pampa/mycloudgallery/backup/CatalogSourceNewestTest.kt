package dev.pampa.mycloudgallery.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Le ultime foto di ogni sorgente del backup, per le copertine (I3; giro 1 della fase I, CS-11: prima nessun test). Sul catalogo vero in memoria:
 * dal piu' recente, al piu' tante per sorgente, solo le sorgenti chieste, solo le copie **presenti sul telefono** (una foto tolta dal telefono o una
 * copia del NAS non e' la copertina di una cartella del telefono), fotocamera e screenshot riconosciuti dal percorso. Ogni prova cade se si toglie
 * il controllo che prova. Il cancello della Libreria visibile (mai un nascosto) e' di chi disegna: `BackupCoversTest` di feature-backup.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CatalogSourceNewestTest {

  private val db = GalleryDatabase.build(ApplicationProvider.getApplicationContext(), name = null)
  private val newest = CatalogSourceNewest(db)
  private var serial = 0L

  @After
  fun close() = db.close()

  private val telegram = BackupSource.Folder("external_primary", "Pictures/Telegram/")
  private val whatsapp = BackupSource.Folder("external_primary", "Pictures/WhatsApp/")

  /** Un media con la sua versione e una posizione: [where] `phone` o `nas`, [state] `present` o `absent`. Restituisce l'id del media. */
  private fun media(path: String, sortTime: Long, where: String = "phone", state: String = "present"): MediaId {
    val n = ++serial
    val mediaId = UUID(0x5EEDL, n).toString()
    val versionId = UUID(0xFEEDL, n).toString()
    val sql = db.openHelper.writableDatabase
    // Come il catalogo: la versione, il media e la posizione in una transazione (le chiavi esterne sono controllate alla fine).
    sql.beginTransaction()
    try {
    sql.execSQL("INSERT OR REPLACE INTO media_store_cursor (volume, ms_version) VALUES ('external_primary', 'v1')")
    sql.execSQL(
      "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
        "VALUES (?, ?, 'original', NULL, 1000, ?, ?, '', 0)",
      arrayOf<Any?>(versionId, mediaId, "%064x".format(n), "%064x".format(n + 1_000_000)),
    )
    sql.execSQL(
      "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
        "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash, backup_todo) " +
        "VALUES (?, 'photo', 'camera', 'alessio', NULL, ?, 0, 0, ?, 'phone_only', 'no_nas_copy', 20251009, 1333, NULL, NULL, 0, NULL, 0)",
      arrayOf<Any?>(mediaId, versionId, sortTime),
    )
    if (where == "phone") {
      sql.execSQL(
        "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, volume, relative_path, display_name, " +
          "media_store_id, media_store_version, generation, account_id, share_name, share_key, nas_path, size, modified_at, absent_since, " +
          "verified_at, last_seen_at, file_id) VALUES (?, 'phone', ?, ?, ?, 'external_primary', ?, ?, ?, 'v1', 7, NULL, NULL, NULL, " +
          "NULL, 1000, 1000, NULL, NULL, 1000, NULL)",
        arrayOf<Any?>(versionId, "phone:external_primary:${path.lowercase()}img_$n.jpg", "phone:external_primary:${path.lowercase()}", state, path, "IMG_$n.jpg", 10_000L + n),
      )
    } else {
      sql.execSQL(
        "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, volume, relative_path, display_name, " +
          "media_store_id, media_store_version, generation, account_id, share_name, share_key, nas_path, size, modified_at, absent_since, " +
          "verified_at, last_seen_at, file_id) VALUES (?, 'nas', ?, ?, ?, 'external_primary', ?, NULL, NULL, NULL, NULL, 'alessio', 'alessio', " +
          "'alessio', ?, 1000, 1000, NULL, NULL, 1000, NULL)",
        // Giro 2 (mutazione di CS-11): la riga del NAS ha anche volume e cartella, cosi' a escluderla e' solo il controllo del tipo di posizione.
        arrayOf<Any?>(versionId, "nas:alessio:alessio:${path.lowercase()}img_$n.jpg", "nas:alessio:alessio:${path.lowercase()}", state, path, "${path}IMG_$n.jpg"),
      )
    }
    sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
    return MediaId(mediaId)
  }

  @Test
  fun dalPiuRecente_AlPiuTantePerSorgente_SoloQuelleChieste() = runBlocking<Unit> {
    val old = media("Pictures/Telegram/", sortTime = 1_000)
    val mid = media("Pictures/Telegram/", sortTime = 2_000)
    val last = media("Pictures/Telegram/", sortTime = 3_000)
    media("Pictures/WhatsApp/", sortTime = 4_000)
    val found = newest.newest(listOf(telegram), perSource = 2)
    // Il tetto per sorgente e l'ordine: le due piu' recenti, dalla piu' recente.
    assertThat(found.getValue(telegram.id)).containsExactly(last, mid).inOrder()
    assertThat(found.getValue(telegram.id)).doesNotContain(old)
    // Solo le sorgenti chieste: WhatsApp non c'e' anche se ha la foto piu' recente.
    assertThat(found.keys).containsExactly(telegram.id)
  }

  /**
   * Il tetto vale per ogni sorgente anche quando un'altra non e' ancora piena (giro 2, mutazione di CS-11: con una sola sorgente chiesta il ciclo si
   * fermava da solo e la prova non vedeva il tetto). Telegram ha tre foto piu' recenti di quella di WhatsApp: ne restano due, e WhatsApp ha la sua.
   */
  @Test
  fun ilTettoValePerOgniSorgenteAncheConLeAltreNonPiene() = runBlocking<Unit> {
    val chat = media("Pictures/WhatsApp/", sortTime = 1_000)
    media("Pictures/Telegram/", sortTime = 3_000)
    val mid = media("Pictures/Telegram/", sortTime = 4_000)
    val last = media("Pictures/Telegram/", sortTime = 5_000)
    val found = newest.newest(listOf(telegram, whatsapp), perSource = 2)
    assertThat(found.getValue(telegram.id)).containsExactly(last, mid).inOrder()
    assertThat(found.getValue(whatsapp.id)).containsExactly(chat)
  }

  @Test
  fun unaFotoToltaDalTelefonoOUnaCopiaDelNasNonSonoLaCopertina() = runBlocking<Unit> {
    val kept = media("Pictures/Telegram/", sortTime = 1_000)
    media("Pictures/Telegram/", sortTime = 2_000, state = "absent")
    media("Pictures/Telegram/", sortTime = 3_000, where = "nas")
    val found = newest.newest(listOf(telegram), perSource = 4)
    assertThat(found.getValue(telegram.id)).containsExactly(kept)
  }

  @Test
  fun fotocameraEScreenshotSiRiconosconoDalPercorso() = runBlocking<Unit> {
    val shot = media("DCIM/Camera/", sortTime = 1_000)
    val screen = media("Pictures/Screenshots/", sortTime = 2_000)
    val chat = media("Pictures/WhatsApp/", sortTime = 3_000)
    val found = newest.newest(listOf(BackupSource.Camera, BackupSource.Screenshots, whatsapp), perSource = 1)
    assertThat(found.getValue(BackupSource.Camera.id)).containsExactly(shot)
    assertThat(found.getValue(BackupSource.Screenshots.id)).containsExactly(screen)
    assertThat(found.getValue(whatsapp.id)).containsExactly(chat)
  }

  @Test
  fun senzaSorgentiONienteDaChiedereNonLeggeNiente() = runBlocking<Unit> {
    media("Pictures/Telegram/", sortTime = 1_000)
    assertThat(newest.newest(emptyList(), perSource = 3)).isEmpty()
    assertThat(newest.newest(listOf(telegram), perSource = 0)).isEmpty()
  }
}
