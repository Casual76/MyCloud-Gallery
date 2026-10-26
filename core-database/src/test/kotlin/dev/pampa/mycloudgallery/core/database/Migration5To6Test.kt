package dev.pampa.mycloudgallery.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.NasArea
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 5 -> 6 (Fase 7, D34): le colonne che si **ricavano** dai dati esistenti sono calcolate dalla migrazione con le stesse regole del
 * catalogo ([OriginDevice], [CopyAreas], l'impronta degli elementi degli album) e non perdono nulla. Un database v5 con dodici media in
 * tutte le situazioni (telefono, cartelle `Backup/<dispositivo>/`, area comune, share sconosciuta, posizioni assenti o non raggiungibili)
 * migra, e per ognuno la colonna scritta dalla migrazione e' quella che il catalogo calcolerebbe dalle sue posizioni.
 */
@RunWith(AndroidJUnit4::class)
class Migration5To6Test {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val schemaDir = File("schemas/${GalleryDatabase::class.java.name}")
  private val me = AccountId.of("alessio")

  private class Situation(val id: Int, val expectedDevice: String?, val expectedAreas: Int, val locations: List<Place>)

  private sealed interface Place {
    val state: String

    data class Phone(val name: String, override val state: String = "present") : Place

    data class Nas(val share: String, val path: String, override val state: String = "present") : Place
  }

  /** Ogni media: dove sta e che etichetta e aree ci si aspetta (area privata 1, comune 2, sconosciuta 4). */
  private val situations = listOf(
    Situation(1, OriginDevice.PHONE, CopyAreas.PRIVATE, listOf(Place.Phone("a.jpg"), Place.Nas("alessio", "Backup/S25/2026/10/a.jpg"))),
    Situation(2, null, 0, listOf(Place.Phone("b.jpg", state = "absent"))),
    Situation(3, "backup:tab", CopyAreas.PRIVATE, listOf(Place.Nas("alessio", "Backup/Tab/2026/10/c.jpg"))),
    Situation(4, null, CopyAreas.PRIVATE, listOf(Place.Nas("alessio", "prova/Backup/Prova/d.jpg"))),
    Situation(5, null, CopyAreas.PRIVATE, listOf(Place.Nas("alessio", "Backup/Area comune/2026/10/e.jpg"))),
    Situation(6, null, CopyAreas.COMMON, listOf(Place.Nas("NAS", "Backup/Marco/f.jpg"))),
    Situation(7, "backup:altro", CopyAreas.PRIVATE, listOf(Place.Nas("alessio", "Backup/S25/g.jpg"), Place.Nas("alessio", "Backup/Altro/g.jpg"))),
    Situation(8, null, CopyAreas.OTHER, listOf(Place.Nas("ignota", "Backup/S25/h.jpg"))),
    Situation(9, null, CopyAreas.PRIVATE, listOf(Place.Nas("alessio", "Documenti/Backup/zio/i.jpg"))),
    Situation(10, null, CopyAreas.PRIVATE or CopyAreas.COMMON, listOf(Place.Nas("alessio", "Famiglia/l.jpg"), Place.Nas("NAS", "Album condivisi/Oslo/l.jpg"))),
    Situation(11, null, 0, listOf(Place.Nas("alessio", "Backup/S25/gone.jpg", state = "absent"))),
    Situation(12, "backup:s25", 0, listOf(Place.Nas("alessio", "Backup/S25/u.jpg", state = "unavailable"))),
    // Una radice che non e' `Backup` e `Backup` come file (nessuna cartella del dispositivo): nessun dispositivo.
    Situation(13, null, CopyAreas.PRIVATE, listOf(Place.Nas("alessio", "prova/Altro/Backup/m.jpg"))),
    Situation(14, null, CopyAreas.PRIVATE, listOf(Place.Nas("alessio", "Backup/solo.jpg"))),
  )

  private fun mediaId(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

  private fun versionId(n: Int) = "10000000-0000-4000-8000-%012d".format(n)

  private fun quick(n: Int) = "%064x".format(n + 1000)

  /** La v5 con queste situazioni, due album (uno con un elemento di un media con piu' impronte) e le tre share del test. */
  private fun seedV5(db: SQLiteDatabase) {
    db.execSQL("INSERT INTO nas_share (account_id, share_key, share_name, area) VALUES ('alessio', 'alessio', 'alessio', 'private'), ('alessio', 'nas', 'NAS', 'common')")
    for (s in situations) {
      db.execSQL(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash) VALUES " +
          "('${mediaId(s.id)}', 'photo', 'camera', 'alessio', NULL, '${versionId(s.id)}', 0, 0, ${Db.T0 - s.id}, 'phone_and_nas', 'no_nas_copy', " +
          "20251009, 1000, NULL, NULL, 0, NULL)",
      )
      db.execSQL(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES ('${versionId(s.id)}', '${mediaId(s.id)}', 'original', NULL, ${1000 + s.id}, '${quick(s.id)}', NULL, '', 0)",
      )
      s.locations.forEachIndexed { index, place ->
        val location = when (place) {
          is Place.Phone -> Location.Phone("external_primary", "DCIM/Camera/", place.name, 100L * s.id + index, "store-v1")
          is Place.Nas -> Location.Nas(me, place.share, NasPath.of(place.path))
        }
        val phone = location as? Location.Phone
        val nas = location as? Location.Nas
        db.execSQL(
          "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, volume, relative_path, display_name, " +
            "media_store_id, media_store_version, generation, account_id, share_name, share_key, nas_path, size, modified_at, absent_since, " +
            "verified_at, last_seen_at, file_id) VALUES ('${versionId(s.id)}', '${location.type.code}', '${location.key.value}', " +
            "'${location.folderKey}', '${place.state}', ${phone?.let { "'${it.volume}'" } ?: "NULL"}, ${phone?.let { "'${it.relativePath}'" } ?: "NULL"}, " +
            "${phone?.let { "'${it.displayName}'" } ?: "NULL"}, ${phone?.mediaStoreId ?: "NULL"}, ${phone?.let { "'store-v1'" } ?: "NULL"}, NULL, " +
            "${nas?.let { "'alessio'" } ?: "NULL"}, ${nas?.let { "'${it.share}'" } ?: "NULL"}, ${nas?.let { "'${it.shareKey}'" } ?: "NULL"}, " +
            "${nas?.let { "'${it.path.value}'" } ?: "NULL"}, ${1000 + s.id}, ${Db.T0}, ${if (place.state == "absent") Db.T0 else "NULL"}, NULL, ${Db.T0}, NULL)",
        )
      }
    }
    // Due album della v1: l'elemento 1 ha l'impronta della sua versione corrente; un secondo media ha due versioni (la corrente e'
    // la seconda, con un'altra impronta): `fp` e' quella della corrente, come `fingerprintOfMedia`.
    db.execSQL("INSERT INTO album (album_id, title, cover_media_id, created_at, updated_at) VALUES ('album-uno', 'Uno', '${mediaId(1)}', ${Db.T0}, ${Db.T0}), ('album-due', 'Due', NULL, ${Db.T0}, ${Db.T0})")
    db.execSQL(
      "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
        "VALUES ('20000000-0000-4000-8000-000000000003', '${mediaId(3)}', 'edited', '${versionId(3)}', 777, '${"%064x".format(5)}', NULL, '', 1)",
    )
    db.execSQL("UPDATE media SET current_version_id = '20000000-0000-4000-8000-000000000003' WHERE media_id = '${mediaId(3)}'")
    db.execSQL(
      "INSERT INTO album_item (album_id, media_id, position, added_at) VALUES ('album-uno', '${mediaId(1)}', 1000, ${Db.T0}), " +
        "('album-due', '${mediaId(3)}', 1000, ${Db.T0}), ('album-due', '${mediaId(1)}', 2000, ${Db.T0})",
    )
  }

  @Test
  fun laMigrazioneRicavaDispositivoAreeEImprontaDalleStesseRegoleDelCatalogo() = runBlocking<Unit> {
    val name = "migrazione-5-6-derivati.db"
    SchemaFiles.create(context, schemaDir, 5, name) { seedV5(it) }
    val database = GalleryDatabase.build(context, name)
    try {
      val sql = database.openHelper.writableDatabase
      sql.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }

      // I valori attesi, situazione per situazione.
      for (s in situations) {
        val row = checkNotNull(database.mediaDao().get(mediaId(s.id)))
        assertWithMessage("origin_device del media ${s.id}").that(row.originDevice).isEqualTo(s.expectedDevice)
        assertWithMessage("copy_areas del media ${s.id}").that(row.copyAreas).isEqualTo(s.expectedAreas)
      }

      // La stessa risposta che da' il catalogo dalle posizioni (OriginDevice e CopyAreas sono la sua `derive`).
      val catalog = MediaCatalog(database, me) { Db.T0 }
      val areas = NasAreaLookup.of(listOf(NasShare(me, "alessio", NasArea.PRIVATE), NasShare(me, "NAS", NasArea.COMMON)))
      for (s in situations) {
        val media = checkNotNull(catalog.media(MediaId(mediaId(s.id))))
        val row = checkNotNull(database.mediaDao().get(mediaId(s.id)))
        assertWithMessage("origin_device del media ${s.id}").that(row.originDevice).isEqualTo(OriginDevice.of(media.locations, areas))
        assertWithMessage("copy_areas del media ${s.id}").that(row.copyAreas).isEqualTo(CopyAreas.of(media.locations, areas))
      }

      // L'impronta di ogni elemento e' <qh1>:<dimensione> della versione corrente del suo media; il resto e' manuale e personale.
      val uno = database.albumDao().items("album-uno").single()
      assertThat(uno.fp).isEqualTo("${quick(1)}:${1000 + 1}")
      val due = database.albumDao().items("album-due").associateBy { it.mediaId }
      assertThat(due.getValue(mediaId(3)).fp).isEqualTo("${"%064x".format(5)}:777")
      assertThat(due.getValue(mediaId(1)).fp).isEqualTo("${quick(1)}:${1000 + 1}")
      assertThat(database.albumDao().all().map { listOf(it.kind, it.sort, it.frozen, it.sharedId, it.state) }.distinct())
        .containsExactly(listOf("manual", "newest", false, null, null))
      assertThat(due.values.map { listOf(it.origin, it.addedBy) }.distinct()).containsExactly(listOf("manual", null))
    } finally {
      database.close()
    }
  }

  /**
   * Dopo la migrazione il ricalcolo dei derivati (`derive`) riscrive le due colonne nuove con gli stessi valori: la migrazione e il
   * catalogo hanno una regola sola. (Gli altri derivati del seed, `presence` e `safety`, sono inventati e il ricalcolo li corregge: qui
   * si guardano solo le colonne della v6.)
   */
  @Test
  fun ilRicalcoloDopoLaMigrazioneRiscriveLeColonneNuoveConGliStessiValori() = runBlocking<Unit> {
    val name = "migrazione-5-6-ricalcolo.db"
    SchemaFiles.create(context, schemaDir, 5, name) { seedV5(it) }
    val database = GalleryDatabase.build(context, name)
    try {
      val catalog = MediaCatalog(database, me) { Db.T0 }
      val before = situations.map { checkNotNull(database.mediaDao().get(mediaId(it.id))) }
      // Registrare di nuovo le share tocca ogni media che ha una copia li': `derive` ricalcola e `store` riscrive.
      catalog.registerShare(NasShare(me, "alessio", NasArea.PRIVATE))
      catalog.registerShare(NasShare(me, "NAS", NasArea.COMMON))
      val after = situations.map { checkNotNull(database.mediaDao().get(mediaId(it.id))) }
      assertThat(after.map { it.originDevice to it.copyAreas }).isEqualTo(before.map { it.originDevice to it.copyAreas })
      assertThat(after.map { it.originDevice to it.copyAreas }).isEqualTo(situations.map { it.expectedDevice to it.expectedAreas })
    } finally {
      database.close()
    }
  }
}
