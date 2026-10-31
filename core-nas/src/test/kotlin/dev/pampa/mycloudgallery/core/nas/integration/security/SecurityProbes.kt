package dev.pampa.mycloudgallery.core.nas.integration.security

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.data.IndexKeys
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.MetaRecords
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random

// Le PROVE dal PC della revisione finale (Fase 10 P11; docs/fase10-progetto.md §2.11): S1 (b), S3 (b), S3 (c), S9. Girano sul NAS di prova
// (`SecurityNasIntegrationTest`) e sul NAS simulato (`SecurityProbesFakeNasTest`, sempre). Scrivono solo con `SecurityArea`, cioe' solo
// dentro `mcg-it/<giro>/` delle share di prova; i numeri tornano nei risultati (mai nomi, mai credenziali).

/** Un JPEG finto: l'intestazione `FF D8 FF E1` e byte che dipendono dal seme (per i file di prova del NAS). */
internal fun fakeJpeg(seed: Long, size: Int, mark: String? = null): ByteArray {
  val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) + (mark?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0))
  return head + Random(seed).nextBytes(maxOf(0, size - head.size))
}

internal fun ByteArray.indexOf(needle: ByteArray): Int {
  if (needle.isEmpty() || needle.size > size) return -1
  outer@ for (i in 0..size - needle.size) {
    if (this[i] != needle[0]) continue
    for (j in 1 until needle.size) if (this[i + j] != needle[j]) continue@outer
    return i
  }
  return -1
}

/**
 * **S1 (b)**, "un account non legge dati privati altrui tramite l'indice": `test` scrive nella **sua** area privata file con un segno unico
 * (`MCG-PRIV-<casuale>` nei byte, nel nome e nel "testo letto"), il loro indice (un segmento `meta` vero con quei percorsi e le loro impronte
 * rapide, cioe' le "impronte note") e un indice dell'area comune **senza** quei dati; poi con `test2` si legge **tutto** cio' che `test2`
 * puo' leggere sotto il giro (area comune, la sua area privata, e l'area privata di `test` se la vede) e si cercano i segni.
 */
internal object CrossAccountProbe {

  class Result(val written: Int, val readByOther: Int, val bytesRead: Long, val marksFound: Int, val privateDenied: Boolean, val needles: Int) {
    fun line(): String = "MCG-SEC S1b scritti=$written letti_da_test2=$readByOther byte_letti=$bytesRead segni=$needles " +
      "segni_trovati=$marksFound area_privata_di_test_negata=${if (privateDenied) 1 else 0}"
  }

  suspend fun run(area: SecurityArea, random: Random = Random(System.nanoTime())): Result {
    val other = checkNotNull(area.manager2) { "S1 (b) chiede il secondo account (user2/password2)" }
    val common = checkNotNull(area.commonShare) { "S1 (b) chiede l'area comune di prova (share2)" }
    val mark = "MCG-PRIV-" + random.nextLong().toULong().toString(16).padStart(16, '0')
    val privateFiles = (1..6).map { i -> area.path("privato", "$mark-$i.jpg") to fakeJpeg(random.nextLong(), 3_000 + i, "$mark testo letto $i") }
    val records = privateFiles.mapIndexed { i, (path, bytes) -> record(SharePath.parseMediaPath("privato/$mark-${i + 1}.jpg"), bytes, i + 1L) }
    val index = IndexSegments.meta(records)
    val knownPrints = records.map { (it as MetaRecord.Present).file.quickHash.hex }
    area.writeAll(area.manager, area.share, privateFiles + (area.path("privato", ".mycloudgallery", "index", "seg", index.ref.hash) to index.bytes))
    // L'area comune ha i suoi file e il suo indice: niente di privato.
    val commonFiles = (1..4).map { i -> area.path("comune", "foto-$i.jpg") to fakeJpeg(random.nextLong(), 2_000 + i) }
    val commonIndex = IndexSegments.meta(commonFiles.mapIndexed { i, (_, bytes) -> record(SharePath.parseMediaPath("comune/foto-${i + 1}.jpg"), bytes, i + 1L) })
    area.writeAll(area.manager, common, commonFiles + (area.path("comune", ".mycloudgallery", "index", "seg", commonIndex.ref.hash) to commonIndex.bytes))

    val needles = listOf(mark.toByteArray(Charsets.US_ASCII)) + knownPrints.map { it.toByteArray() } +
      records.map { (it as MetaRecord.Present).file.mediaId.value.toByteArray() }
    var read = 0
    var bytesRead = 0L
    var found = 0
    val shares = listOfNotNull(common, area.credentials.privateShare2, area.share).distinct()
    var privateDenied = false
    for (shareName in shares) {
      val denials = ArrayList<String>()
      val files = area.walk(other, shareName, area.guard.root, denials)
      if (shareName.equals(area.share, ignoreCase = true) && files.isEmpty()) privateDenied = denials.isNotEmpty()
      for ((path, _) in files) {
        val bytes = area.read(other, shareName, path) ?: continue
        read++
        bytesRead += bytes.size
        if (needles.any { bytes.indexOf(it) >= 0 }) found++
        if (path.value.contains(mark)) found++
      }
    }
    // Anche a colpo sicuro: i percorsi esatti dei file privati, letti direttamente da test2.
    for ((path, _) in privateFiles) {
      val bytes = area.read(other, area.share, path)
      if (bytes != null) found++
    }
    return Result(privateFiles.size + 1 + commonFiles.size + 1, read, bytesRead, found, privateDenied, needles.size)
  }

  private fun record(path: SharePath, bytes: ByteArray, seq: Long): MetaRecord = MetaRecord.Present(
    path,
    seq,
    MetaFile(
      stamp = NasStamp(bytes.size.toLong(), 1_792_000_000_000L, fileId = null),
      quickHash = QuickHashAlgorithm.ofContent(bytes),
      mediaId = MediaId.random(),
      versionId = VersionId.random(),
      kind = MediaKind.PHOTO,
      source = MediaSource.CAMERA,
      mime = "image/jpeg",
      prepared = 0,
    ),
  )
}

/**
 * **S3 (b)**, "un segmento ostile non fa danni", dal NAS: segmenti costruiti apposta (percorsi `..`, assoluti, UNC, con il carattere nullo e
 * l'inversione di direzione; record oltre il tetto; UTF-8 rotto; tabelle che dichiarano piu' voci o byte di quelli che ci sono; un segmento
 * con un'impronta diversa da quella della testa) piu' due segmenti buoni di controllo, scritti nel giro, **riletti dal NAS** e passati al
 * codec vero (`IndexSegments.decode`). Atteso: ostili tutti rifiutati, buoni accettati, byte riletti uguali, nessuna eccezione.
 */
internal object HostileSegmentsProbe {

  class Result(val hostile: Int, val rejected: Int, val controls: Int, val accepted: Int, val sameBytes: Int, val crashes: Int) {
    fun line(): String = "MCG-SEC S3b ostili=$hostile rifiutati=$rejected controlli=$controls accettati=$accepted riletti_uguali=$sameBytes crolli=$crashes"
  }

  /** (nome, byte, impronta dichiarata dalla "testa", ostile?). */
  fun segments(): List<Triple<String, Pair<ByteArray, SegmentRef>, Boolean>> {
    val good = record("Foto/buona.jpg", 1)
    val goodBody = MetaRecords.encode(good)
    val goodSegment = IndexSegments.meta(listOf(good))
    val out = ArrayList<Triple<String, Pair<ByteArray, SegmentRef>, Boolean>>()
    fun hostile(name: String, bytes: ByteArray, ref: SegmentRef = refOf(bytes)) = out.add(Triple(name, bytes to ref, true))
    val goodJson = String(goodBody, Charsets.UTF_8)
    for ((name, path) in listOf(
      "punto-punto" to "../../Windows/x.jpg", "dentro-punto-punto" to "Foto/../../x.jpg", "assoluto" to "/etc/passwd.jpg",
      "unita" to "C:\\\\Windows\\\\x.jpg", "unc" to "\\\\\\\\server\\\\share\\\\x.jpg", "nullo" to "Foto/a\\u0000.jpg",
      "direzione" to "Foto/gpj.\u202Eexe", "punto" to "./x.jpg", "vuoto" to "",
    )) {
      val json = goodJson.replace("\"path\":\"Foto/buona.jpg\"", "\"path\":\"$path\"").toByteArray(Charsets.UTF_8)
      hostile("percorso-$name", raw(listOf(IndexKeys.meta(good.path) to json)))
    }
    hostile("record-oltre-il-tetto", raw(listOf(IndexKeys.meta(good.path) to ByteArray(2 * 1024 * 1024) { ' '.code.toByte() })))
    hostile("utf8-rotto", raw(listOf(IndexKeys.meta(good.path) to (goodBody.copyOf(goodBody.size - 2) + byteArrayOf(0xC3.toByte(), '}'.code.toByte())))))
    hostile("troppe-voci", raw(listOf(IndexKeys.meta(good.path) to goodBody), declaredCount = 1_000_000))
    hostile("lunghezza-enorme", raw(listOf(IndexKeys.meta(good.path) to goodBody), declaredLength = 0x7FFFFFF0))
    hostile("tabella-oltre-la-fine", raw(listOf(IndexKeys.meta(good.path) to goodBody), declaredCount = 4_000))
    hostile("impronta-diversa", goodSegment.bytes, SegmentRef(Hashes.sha256Name(byteArrayOf(1, 2, 3)), goodSegment.ref.size))
    hostile("contenuto-cambiato", goodSegment.bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }, goodSegment.ref)
    out += Triple("buono-1", goodSegment.bytes to goodSegment.ref, false)
    val second = IndexSegments.meta(listOf(record("Foto/altra.jpg", 2), record("Viaggi/città.jpg", 3)))
    out += Triple("buono-2", second.bytes to second.ref, false)
    return out
  }

  suspend fun run(area: SecurityArea): Result {
    val shareName = area.commonShare ?: area.share
    val list = segments()
    area.writeAll(area.manager, shareName, list.map { (name, seg, _) -> area.path("ostili", ".mycloudgallery", "index", "seg", "$name.mcgseg") to seg.first })
    var rejected = 0
    var accepted = 0
    var same = 0
    var crashes = 0
    for ((name, seg, isHostile) in list) {
      val back = area.read(area.manager, shareName, area.path("ostili", ".mycloudgallery", "index", "seg", "$name.mcgseg")) ?: continue
      if (back.contentEquals(seg.first)) same++
      try {
        when (IndexSegments.decode(back, seg.second, SegmentClass.META)) {
          is Verdict.Accepted -> if (isHostile) Unit else accepted++
          is Verdict.Rejected -> if (isHostile) rejected++
        }
      } catch (t: Throwable) {
        crashes++
      }
    }
    return Result(list.count { it.third }, rejected, list.count { !it.third }, accepted, same, crashes)
  }

  private fun record(path: String, seq: Long): MetaRecord = MetaRecord.Present(
    SharePath.parseMediaPath(path),
    seq,
    MetaFile(
      stamp = NasStamp(1_000L + seq, 1_792_000_000_000L, null),
      quickHash = QuickHashAlgorithm.ofContent(byteArrayOf(seq.toByte())),
      mediaId = MediaId.random(),
      versionId = VersionId.random(),
      kind = MediaKind.PHOTO,
      source = MediaSource.CAMERA,
      mime = "image/jpeg",
      prepared = 0,
    ),
  )

  private fun refOf(bytes: ByteArray): SegmentRef = SegmentRef(Hashes.sha256Name(bytes), bytes.size.toLong().coerceAtLeast(1))

  /** Un segmento `meta` scritto a mano (formato §5.5-bis): conteggio e lunghezze dichiarati a scelta. */
  private fun raw(rows: List<Pair<ByteArray, ByteArray>>, declaredCount: Int = rows.size, declaredLength: Int? = null): ByteArray {
    val size = 4 + 1 + 1 + 3 + 4 + rows.size * 36 + rows.sumOf { it.second.size }
    val out = ByteBuffer.allocate(size)
    out.put("MCGB".toByteArray(Charsets.US_ASCII)).put(1).put(SegmentClass.META.code.toByte()).put(0).put(0).put(0).putInt(declaredCount)
    for ((key, body) in rows) out.put(key).putInt(declaredLength ?: body.size)
    for ((_, body) in rows) out.put(body)
    return out.array()
  }
}

/**
 * **S3 (c)**, le immagini malformate: i file di [dir] (scritti da `S3HostileImagesTest` di core-metadata con `MCG_SECURITY_OUT`) o, senza,
 * tre file minimi costruiti qui (PNG che dichiara 50.000 x 50.000, HEIC troncato, JPEG con un SOF enorme), caricati in
 * `mcg-it/<giro>/mcg-ostile/` e riletti uguali. La decodifica vera e' dell'emulatore e dell'S25 (seconda parte); il PC prova che arrivano
 * intatti e solo dove devono.
 */
internal object HostileImagesProbe {

  class Result(val uploaded: Int, val sameBytes: Int, val bytes: Long) {
    fun line(): String = "MCG-SEC S3c caricati=$uploaded riletti_uguali=$sameBytes byte=$bytes"
  }

  fun builtIn(): List<Pair<String, ByteArray>> {
    val png = ByteBuffer.allocate(33).put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
      .putInt(13).put("IHDR".toByteArray()).putInt(50_000).putInt(50_000).put(8).put(6).put(0).put(0).put(0).putInt(0).array()
    val heic = ByteBuffer.allocate(40).putInt(24).put("ftypheic".toByteArray()).putInt(0).put("mif1heic".toByteArray()).putInt(0x7FFFFFF0).put("meta".toByteArray()).putInt(0).array()
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 3) + ByteArray(9) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    return listOf("png-50000x50000.png" to png, "heic-troncato.heic" to heic, "jpeg-sof-enorme.jpg" to jpeg)
  }

  suspend fun run(area: SecurityArea, dir: File?): Result {
    val shareName = area.commonShare ?: area.share
    val files = dir?.listFiles { f -> f.isFile }?.sortedBy { it.name }?.map { it.name to it.readBytes() }?.takeIf { it.isNotEmpty() } ?: builtIn()
    area.writeAll(area.manager, shareName, files.map { (name, bytes) -> area.path("mcg-ostile", name) to bytes })
    var same = 0
    for ((name, bytes) in files) {
      val back = area.read(area.manager, shareName, area.path("mcg-ostile", name)) ?: continue
      if (MessageDigest.isEqual(back, bytes)) same++
    }
    return Result(files.size, same, files.sumOf { it.second.size.toLong() })
  }
}

/**
 * **S9** dal NAS: in `mcg-it/<giro>/massa/` dell'area comune di prova [files] file (JPEG finti), l'elenco "prima", poi un cambiamento di
 * massa oltre la soglia max(50, 5%): un sesto **rinominato**, un sesto **cambiato sul posto** (byte nuovi e piu' lunghi), un sesto
 * **"cifrato"** (rinominato in `.locked` e riscritto con byte casuali), e l'elenco "dopo". I due elenchi (nomi, dimensioni, date e `fileId`
 * come li da' il NAS vero) vanno in [listing] per `S9MassChangeTest` di core-nasmedia, che li rigioca con lo scanner vero.
 */
internal object MassChangeProbe {

  class Result(val files: Int, val renamed: Int, val changed: Int, val locked: Int, val before: Int, val after: Int, val keptIds: Int) {
    fun line(): String = "MCG-SEC S9pc file=$files rinominati=$renamed cambiati=$changed cifrati=$locked elenco_prima=$before elenco_dopo=$after fileId_conservati=$keptIds"
  }

  suspend fun run(area: SecurityArea, files: Int, listing: File, random: Random = Random(20261031)): Result {
    val shareName = area.commonShare ?: area.share
    val dir = area.path("massa")
    val names = (0 until files).map { "f%04d.jpg".format(it) }
    area.writeAll(area.manager, shareName, names.mapIndexed { i, n -> dir.child(n) to fakeJpeg(i.toLong(), 2_000 + i % 500) })
    val before = checkNotNull(area.list(area.manager, shareName, dir)) { "elenco prima non riuscito" }
    val part = files / 6
    val renamed = names.subList(0, part)
    val changed = names.subList(part, 2 * part)
    val locked = names.subList(2 * part, 3 * part)
    for (batch in renamed.chunked(SecurityArea.BATCH)) area.op(area.manager, shareName) { ctx ->
      batch.forEach { n -> ctx.share.openForChange(dir.child(n).smb(), exclusive = true).use { it.renameNoReplace(dir.child("rinominato-$n").smb()) } }
    }
    for (batch in changed.chunked(SecurityArea.BATCH)) area.op(area.manager, shareName) { ctx ->
      batch.forEach { n ->
        val bytes = fakeJpeg(random.nextLong(), 4_000)
        ctx.share.openExistingForWrite(dir.child(n).smb(), SmbOpenMode.CREATE_EXCLUSIVE).use { f -> f.write(bytes, 0, 0, bytes.size); f.flush() }
      }
    }
    for (batch in locked.chunked(SecurityArea.BATCH)) area.op(area.manager, shareName) { ctx ->
      batch.forEach { n ->
        ctx.share.openForChange(dir.child(n).smb(), exclusive = true).use { it.renameNoReplace(dir.child("$n.locked").smb()) }
        val bytes = random.nextBytes(4_100)
        ctx.share.openExistingForWrite(dir.child("$n.locked").smb(), SmbOpenMode.CREATE_EXCLUSIVE).use { f -> f.write(bytes, 0, 0, bytes.size); f.flush() }
      }
    }
    val after = checkNotNull(area.list(area.manager, shareName, dir)) { "elenco dopo non riuscito" }
    listing.parentFile?.mkdirs()
    listing.writeText(
      "# S9: elenco del NAS di prova prima e dopo il cambiamento di massa (fase, percorso, dimensione, data ms, fileId, tipo)\n" +
        (before.map { "prima" to it } + after.map { "dopo" to it }).filter { !it.second.isDirectory }.joinToString("\n") { (phase, e) ->
          listOf(phase, "Massa/${e.name}", e.size, e.modifiedAt, e.fileId ?: -1, if (e.name.endsWith(".locked")) "cifrato" else "jpeg").joinToString("\t")
        } + "\n",
    )
    val beforeIds = before.mapNotNull { it.fileId }.toSet()
    return Result(files, renamed.size, changed.size, locked.size, before.size, after.size, after.count { it.fileId != null && it.fileId in beforeIds })
  }
}
