package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.model.CellFlags
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

/**
 * Una Libreria sintetica per le prove di prestazione degli album (Fase 7, P1): [count] media scritti in SQL grezzo in una transazione
 * (il catalogo ne scriverebbe 100 mila in minuti: qui si prova la lettura), con tre anni di giorni, 200 luoghi, tre dispositivi d'origine,
 * le tre aree, il GPS, i nascosti, i membri di gruppo, i media senza copie e i doppioni. Tiene in memoria i fatti di ogni media
 * (primitivi) per calcolare il risultato atteso di una query senza passare dal database.
 */
internal class BigLibrary(val count: Int) {

  val ids = arrayOfNulls<String>(count)
  val day = IntArray(count)
  val time = LongArray(count)
  val place = IntArray(count) { NO_PLACE }
  val device = arrayOfNulls<String>(count)
  val hasGps = BooleanArray(count)

  /** Visibile: una cella (non nascosto, non membro di un gruppo, con almeno una copia). */
  val visible = BooleanArray(count)

  /** 0 telefono, 1 area privata, 2 area comune, 3 familiare (area comune con un altro proprietario). */
  val source = IntArray(count)
  val screenshot = BooleanArray(count)

  /** Quanti doppioni identici ci sono (media con due copie dalla stessa parte, visibili). */
  var duplicates = 0

  fun insertInto(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
    val random = Random(7)
    sql.beginTransaction()
    try {
      val media = sql.compileStatement(
        "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, " +
          "presence, safety, local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash, backup_todo, origin_device, copy_areas) " +
          "VALUES (?, ?, ?, ?, NULL, ?, 0, 0, ?, ?, 'no_nas_copy', ?, 1333, NULL, ?, ?, NULL, 0, ?, ?)",
      )
      val version = sql.compileStatement(
        "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
          "VALUES (?, ?, 'original', NULL, ?, ?, ?, '', 0)",
      )
      val metadata = sql.compileStatement(
        "INSERT INTO version_metadata (version_id, mime_type, width, height, orientation, duration_ms, taken_at_utc, taken_offset_min, " +
          "taken_confidence, taken_source, latitude, longitude, altitude, updated_at) VALUES (?, 'image/jpeg', 4000, 3000, 0, NULL, ?, 120, " +
          "'exact', 'exif_offset', ?, ?, NULL, 0)",
      )
      val phone = sql.compileStatement(
        "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, volume, relative_path, display_name, " +
          "media_store_id, media_store_version, generation, size, modified_at, last_seen_at) VALUES (?, 'phone', ?, " +
          "'phone:external_primary:dcim/camera/', 'present', 'external_primary', 'DCIM/Camera/', ?, ?, 'store-v1', 7, ?, 0, 0)",
      )
      val nas = sql.compileStatement(
        "INSERT INTO media_location (version_id, location_type, location_key, folder_key, state, account_id, share_name, share_key, " +
          "nas_path, size, modified_at, last_seen_at, verified_at) VALUES (?, 'nas', ?, ?, 'present', 'alessio', ?, ?, ?, ?, 0, 0, ?)",
      )
      val hidden = sql.compileStatement("INSERT INTO media_personal (media_id, favorite, hidden, updated_at) VALUES (?, 0, 1, 0)")
      val firstDay = LocalDate.of(2023, 1, 1)
      for (i in 0 until count) {
        val mediaId = UUID(random.nextLong(), random.nextLong()).toString()
        val versionId = UUID(random.nextLong(), random.nextLong()).toString()
        ids[i] = mediaId
        val dayIndex = random.nextInt(DAYS)
        val localDate = firstDay.plusDays(dayIndex.toLong())
        day[i] = localDate.year * 10_000 + localDate.monthValue * 100 + localDate.dayOfMonth
        val hour = random.nextInt(24)
        time[i] = localDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 120 * 60_000L + hour * 3_600_000L + random.nextInt(3_600) * 1_000L
        val kind = i % 12
        val deviceKind = i % 10
        val onPhone = deviceKind <= 4
        val common = deviceKind == 9
        device[i] = when (deviceKind) {
          in 0..4 -> OriginDevice.PHONE
          in 5..7 -> OriginDevice.backup("s25")
          8 -> OriginDevice.backup("tab")
          else -> null
        }
        val family = common && i % 20 == 9
        source[i] = if (onPhone) 0 else if (!common) 1 else if (family) 3 else 2
        screenshot[i] = i % 25 == 3
        val gone = i % 300 == 211
        val member = i % 100 == 37
        val isHidden = i % 200 == 113
        visible[i] = !gone && !member && !isHidden
        if (i % 5 < 2) place[i] = 1_000 + (i * 7_919) % PLACES
        hasGps[i] = place[i] != NO_PLACE || i % 10 == 4
        val withNas = !onPhone || i % 3 == 0
        val duplicate = i % 33 == 5 && !gone
        if (duplicate && !isHidden) duplicates++
        val flags = (if (onPhone) CellFlags.ON_PHONE else 0) or (if (withNas) CellFlags.ON_NAS else 0) or (if (member) CellFlags.GROUP_MEMBER else 0)
        val areas = (if (!common && withNas) CopyAreas.PRIVATE else 0) or (if (common) CopyAreas.COMMON else 0)
        media.clearBindings()
        media.bindString(1, mediaId)
        media.bindString(2, if (kind == 0) "video" else "photo")
        media.bindString(3, if (screenshot[i]) "screenshot" else "camera")
        if (family) media.bindString(4, "marco") else if (common) media.bindNull(4) else media.bindString(4, "alessio")
        media.bindString(5, versionId)
        media.bindLong(6, time[i])
        media.bindString(7, if (gone) "none" else if (onPhone && withNas) "phone_and_nas" else if (onPhone) "phone_only" else "nas_only")
        media.bindLong(8, day[i].toLong())
        if (place[i] != NO_PLACE) media.bindLong(9, place[i].toLong()) else media.bindNull(9)
        media.bindLong(10, flags.toLong())
        device[i]?.let { media.bindString(11, it) } ?: media.bindNull(11)
        media.bindLong(12, areas.toLong())
        media.executeInsert()
        version.clearBindings()
        version.bindString(1, versionId)
        version.bindString(2, mediaId)
        version.bindLong(3, 1_000L + i)
        version.bindString(4, "%064x".format(i))
        if (duplicate) version.bindString(5, "%064x".format(i + 1_000_000)) else version.bindNull(5)
        version.executeInsert()
        metadata.clearBindings()
        metadata.bindString(1, versionId)
        metadata.bindLong(2, time[i])
        if (hasGps[i]) {
          metadata.bindDouble(3, 35.0 + random.nextDouble() * 20.0)
          metadata.bindDouble(4, 5.0 + random.nextDouble() * 20.0)
        } else {
          metadata.bindNull(3)
          metadata.bindNull(4)
        }
        metadata.executeInsert()
        if (onPhone) insertPhone(phone, versionId, i, "img_$i.jpg")
        if (onPhone && duplicate) insertPhone(phone, versionId, i, "img_${i}_copia.jpg")
        if (withNas && !common) {
          val folder = if (deviceKind == 8) "tab" else "s25"
          insertNas(nas, versionId, i, "alessio", "alessio", "backup/$folder/2023/img_$i.jpg", "Backup/$folder/2023/img_$i.jpg", duplicate)
          if (duplicate && !onPhone) insertNas(nas, versionId, i, "alessio", "alessio", "backup/$folder/2023/img_${i}_b.jpg", "Backup/$folder/2023/img_${i}_b.jpg", true)
        }
        if (common) {
          insertNas(nas, versionId, i, "NAS", "nas", "album condivisi/img_$i.jpg", "Album condivisi/img_$i.jpg", duplicate)
          if (duplicate) insertNas(nas, versionId, i, "NAS", "nas", "album condivisi/img_${i}_b.jpg", "Album condivisi/img_${i}_b.jpg", true)
        }
        if (isHidden) {
          hidden.clearBindings()
          hidden.bindString(1, mediaId)
          hidden.executeInsert()
        }
      }
      sql.setTransactionSuccessful()
    } finally {
      sql.endTransaction()
    }
  }

  private fun insertPhone(statement: androidx.sqlite.db.SupportSQLiteStatement, versionId: String, i: Int, name: String) {
    statement.clearBindings()
    statement.bindString(1, versionId)
    statement.bindString(2, "phone:external_primary:dcim/camera/$name")
    statement.bindString(3, name)
    statement.bindLong(4, 10_000L + i * 2 + if (name.contains("copia")) 1 else 0)
    statement.bindLong(5, 1_000L + i)
    statement.executeInsert()
  }

  private fun insertNas(
    statement: androidx.sqlite.db.SupportSQLiteStatement,
    versionId: String,
    i: Int,
    shareName: String,
    shareKey: String,
    keyPath: String,
    path: String,
    verified: Boolean,
  ) {
    statement.clearBindings()
    statement.bindString(1, versionId)
    statement.bindString(2, "nas:alessio:$shareKey:$keyPath")
    statement.bindString(3, "nas:alessio:$shareKey:" + keyPath.substringBeforeLast('/'))
    statement.bindString(4, shareName)
    statement.bindString(5, shareKey)
    statement.bindString(6, path)
    statement.bindLong(7, 1_000L + i)
    if (verified) statement.bindLong(8, 1L) else statement.bindNull(8)
    statement.executeInsert()
  }

  companion object {
    const val NO_PLACE: Int = -1
    const val DAYS: Int = 1_100
    const val PLACES: Int = 200
  }
}
