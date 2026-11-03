package dev.pampa.mycloudgallery.core.nas.integration.security

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumHead
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.Segments
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistrationRequest
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import java.io.File
import java.util.UUID

// Le operazioni del PC sull'area della famiglia di prova (Fase 10 P11, seconda parte): fotografia e confronto per il reset della famiglia,
// archivio di `family`, richieste dei dispositivi di prova, semina di S2, S3 (b), S3 (c) e S7, pulizia del solo registro. I risultati sono
// numeri (righe `MCG-SEC`); i codici di approvazione vanno in un file dello stato (li digita lo script nell'app), mai nel resoconto.

/** La famiglia di prova com'e' sul NAS: id, versione del registro, stato dei dispositivi di prova (null = non nel registro). */
internal class FamilyInfo(val familyId: String, val registryVersion: Long, val statusOf: Map<String, DeviceStatus?>, val devices: Int) {
  fun line(): String = "MCG-SEC famiglia trovata=1 versione_registro=$registryVersion dispositivi=$devices " +
    FamilyRunState.ROLES.joinToString(" ") { "${it}_stato=${statusOf[it]?.wire ?: "assente"}" }
}

internal object FamilyAreaProbes {

  private val APP = SharePath.of(".mycloudgallery")

  /**
   * La famiglia di prova attuale: il kit nell'area privata (`recovery/<famiglia>/<impronta>`, intestazione pubblica: id e impronta della
   * chiave) e l'ultima versione del registro nell'area comune, accettata solo se firmata da quella chiave. Null se non c'e' (o non torna).
   */
  suspend fun readFamily(area: FamilyArea): FamilyInfo? {
    val recovery = APP.child("recovery")
    val headers = ArrayList<dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKitHeader>()
    for (family in area.list(area.privateShare, recovery).orEmpty().filter { it.isDirectory }) {
      for (kit in area.list(area.privateShare, recovery.child(family.name)).orEmpty().filter { !it.isDirectory }) {
        val bytes = area.read(area.privateShare, recovery.child(family.name).child(kit.name)) ?: continue
        RecoveryKit.inspect(bytes).acceptedOrNull()?.let { headers += it }
      }
    }
    val versions = area.list(area.commonShare, NasLayout.REGISTRY_DIR).orEmpty().mapNotNull { NasLayout.parseVersionName(it.name) }
    val latest = versions.maxOrNull() ?: return null
    val bytes = area.read(area.commonShare, NasLayout.registryFile(latest)) ?: return null
    for (header in headers.sortedByDescending { it.createdAt }) {
      val state = RegistryVerifier.restore(bytes, header.keyFingerprint).acceptedOrNull() ?: continue
      if (state.registry.familyId != header.familyId) continue
      val statuses = FamilyRunState.ROLES.associateWith { role -> state.registry.device(area.state.device(role).id)?.status }
      return FamilyInfo(state.registry.familyId, state.registry.version, statuses, state.registry.devices.size)
    }
    return null
  }

  /** Il reset ("Ricominciare da zero", ON-6): `family` diventa `family-vecchia-<giro>`, niente si cancella. Falso se non c'era. */
  suspend fun archiveFamily(area: FamilyArea): Boolean {
    if (area.list(area.commonShare, APP).orEmpty().none { it.isDirectory && it.name.equals("family", ignoreCase = true) }) return false
    area.op(area.commonShare) { ctx ->
      ctx.share.openForChange(APP.child("family").smb(), exclusive = true).use { it.renameNoReplace(APP.child(area.guard.familyArchive).smb()) }
    }
    // La famiglia vecchia non e' un file della prova: la pulizia non la tocca (la toglie l'utente, se vuole).
    area.guard.forget('f', area.commonShare, APP.child(area.guard.familyArchive).value)
    area.state.saveLedger(area.guard.ledger())
    return true
  }

  /** Le richieste di `approvato` e `revocato` (o dei soli [roles], se un foglio dell'impronta e' scaduto) in `family/requests/`; i codici di approvazione in [codes] (file dello stato). */
  suspend fun requests(area: FamilyArea, info: FamilyInfo, now: Long, codes: File, roles: List<String> = listOf("approvato", "revocato")): Int {
    val lines = ArrayList<String>()
    for (role in roles) {
      val device = area.state.device(role)
      val bytes = RegistrationRequest.sign(device, info.familyId, "MCG prova $role", "PC di prova", area.credentials.user, now)
      area.create(area.commonShare, NasLayout.requestFile(device.id), bytes)
      val request = RegistrationRequest.verify(bytes, info.familyId, device.id).acceptedOrNull() ?: error("richiesta non valida")
      lines += "$role=${request.approvalCode}"
    }
    codes.writeText(lines.joinToString("\n", postfix = "\n"))
    return roles.size
  }

  class Counts(val label: String, val files: Int, val heads: Int, val segments: Int, val bytes: Long) {
    fun line(extra: String = ""): String = "MCG-SEC $label file=$files teste=$heads segmenti=$segments byte=$bytes$extra"
  }

  /**
   * **S2** nell'area della famiglia di prova: `canaglia` (mai approvato) e `revocato` scrivono ognuno una testa `index` dell'area comune
   * con un segmento `meta` valido, una testa `personal` dell'area privata e la testa di un album condiviso (con un id nuovo). L'app li
   * deve rifiutare tutti (firmatario non approvato o revocato) e non mostrarne niente.
   */
  suspend fun seedRogue(area: FamilyArea, info: FamilyInfo, now: Long): Counts {
    var files = 0
    var heads = 0
    var bytes = 0L
    for (role in listOf("canaglia", "revocato")) {
      val device = area.state.device(role)
      val meta = IndexSegments.meta(listOf(record("Foto/mcg-s2-$role.jpg", 1)))
      val indexHead = DeviceHead(info.familyId, device.id, area.commonShare, HeadKind.INDEX, 1, info.registryVersion, now, listOf(ClassedSegment(SegmentClass.META, meta.ref))).sign(device)
      val (personalBytes, personalRef) = Segments.wrap("MCG-S2 dati personali di $role".toByteArray())
      val personalHead = DeviceHead(info.familyId, device.id, area.privateShare, HeadKind.PERSONAL, 1, info.registryVersion, now, listOf(ClassedSegment(SegmentClass.PERSONAL, personalRef))).sign(device)
      val album = AlbumId(UUID.randomUUID().toString())
      val (albumBytes, albumRef) = Segments.wrap("MCG-S2 definizione d'album di $role".toByteArray())
      val albumHead = AlbumDocs.encodeHead(AlbumHead(info.familyId, album, device.id, 1, info.registryVersion, now, listOf(albumRef)), device).bytes
      val common = listOf(
        NasLayout.segmentFile(device.id, HeadKind.INDEX.wire, meta.ref.hash) to meta.bytes,
        NasLayout.headFile(device.id, HeadKind.INDEX.wire, 1) to indexHead,
        NasLayout.albumSegment(album.value, device.id, albumRef.hash) to albumBytes,
        NasLayout.albumHead(album.value, device.id, 1) to albumHead,
      )
      val private = listOf(
        NasLayout.segmentFile(device.id, HeadKind.PERSONAL.wire, personalRef.hash) to personalBytes,
        NasLayout.headFile(device.id, HeadKind.PERSONAL.wire, 1) to personalHead,
      )
      area.createAll(area.commonShare, common)
      area.createAll(area.privateShare, private)
      files += common.size + private.size
      heads += 3
      bytes += (common + private).sumOf { it.second.size.toLong() }
    }
    return Counts("S2app", files, heads, files - heads, bytes)
  }

  /**
   * **S3 (b)** nell'app: i segmenti di [HostileSegmentsProbe] (piu' i buoni di controllo) come indice dell'area comune del dispositivo
   * `approvato`, con una testa firmata da lui: se l'admin l'ha approvato, l'app arriva ai codec e li deve rifiutare uno a uno senza crolli.
   * Un riferimento ripetuto (lo stesso nome di file) si tiene una volta sola, il primo (l'ostile).
   */
  suspend fun seedHostileSegments(area: FamilyArea, info: FamilyInfo, now: Long): Counts {
    val device = area.state.device("approvato")
    val segments = HostileSegmentsProbe.segments().distinctBy { it.second.second.hash }.sortedBy { it.second.second.hash }
    val head = DeviceHead(info.familyId, device.id, area.commonShare, HeadKind.INDEX, 1, info.registryVersion, now, segments.map { ClassedSegment(SegmentClass.META, it.second.second) }).sign(device)
    val files = segments.map { (_, seg, _) -> NasLayout.segmentFile(device.id, HeadKind.INDEX.wire, seg.second.hash) to seg.first } +
      (NasLayout.headFile(device.id, HeadKind.INDEX.wire, 1) to head)
    area.createAll(area.commonShare, files)
    val hostile = segments.count { it.third }
    return Counts("S3bapp", files.size, 1, segments.size, files.sumOf { it.second.size.toLong() }).also {
      check(hostile >= 15) { "troppo pochi segmenti ostili: $hostile" }
    }
  }

  /**
   * **S1 (c)**: il dispositivo `approvato` (account `test`) pubblica l'indice dell'area **privata** `test` con tre voci il cui nome porta
   * un segno unico `MCG-PRIV-<casuale>` (scritto in [markFile] per lo script). L'app di `test` lo trova in Cerca (controllo), quella di
   * `test2` no: legge solo l'area comune e la sua privata.
   */
  suspend fun seedPrivateMarks(area: FamilyArea, info: FamilyInfo, now: Long, markFile: File): Counts {
    val device = area.state.device("approvato")
    val mark = "MCG-PRIV-" + UUID.randomUUID().toString().replace("-", "").take(12)
    val meta = IndexSegments.meta((1..3).map { record("Foto/$mark-$it.jpg", it.toLong()) })
    val head = DeviceHead(info.familyId, device.id, area.privateShare, HeadKind.INDEX, 1, info.registryVersion, now, listOf(ClassedSegment(SegmentClass.META, meta.ref))).sign(device)
    val files = listOf(NasLayout.segmentFile(device.id, HeadKind.INDEX.wire, meta.ref.hash) to meta.bytes, NasLayout.headFile(device.id, HeadKind.INDEX.wire, 1) to head)
    area.createAll(area.privateShare, files)
    markFile.writeText(mark + "\n")
    return Counts("S1capp", files.size, 1, 1, files.sumOf { it.second.size.toLong() })
  }

  /** **S9** nell'app, prima meta': [files] JPEG finti in `test-comune/mcg-it/<giro>/massa/` (il giro e' quello dello stato). */
  suspend fun massCreate(area: SecurityArea, files: Int): Int {
    val shareName = area.commonShare ?: area.share
    val dir = area.path("massa")
    area.writeAll(area.manager, shareName, (0 until files).map { dir.child("f%04d.jpg".format(it)) to fakeJpeg(it.toLong(), 2_000 + it % 500) })
    return checkNotNull(area.list(area.manager, shareName, dir)).count { !it.isDirectory }
  }

  /** **S9** nell'app, seconda meta' (dopo un giro di scansione dell'app): un sesto rinominato, un sesto cambiato, un sesto "cifrato". */
  suspend fun massChange(area: SecurityArea, random: kotlin.random.Random = kotlin.random.Random(20261102)): Triple<Int, Int, Int> {
    val shareName = area.commonShare ?: area.share
    val dir = area.path("massa")
    val names = checkNotNull(area.list(area.manager, shareName, dir)).filter { !it.isDirectory && it.name.matches(Regex("^f[0-9]{4}\\.jpg$")) }.map { it.name }.sorted()
    val part = names.size / 6
    for (batch in names.subList(0, part).chunked(SecurityArea.BATCH)) area.op(area.manager, shareName) { ctx ->
      batch.forEach { n -> ctx.share.openForChange(dir.child(n).smb(), exclusive = true).use { it.renameNoReplace(dir.child("rinominato-$n").smb()) } }
    }
    for (batch in names.subList(part, 2 * part).chunked(SecurityArea.BATCH)) area.op(area.manager, shareName) { ctx ->
      batch.forEach { n ->
        val bytes = fakeJpeg(random.nextLong(), 4_000)
        ctx.share.openExistingForWrite(dir.child(n).smb(), dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode.CREATE_EXCLUSIVE).use { f -> f.write(bytes, 0, 0, bytes.size); f.flush() }
      }
    }
    for (batch in names.subList(2 * part, 3 * part).chunked(SecurityArea.BATCH)) area.op(area.manager, shareName) { ctx ->
      batch.forEach { n ->
        ctx.share.openForChange(dir.child(n).smb(), exclusive = true).use { it.renameNoReplace(dir.child("$n.locked").smb()) }
        val bytes = random.nextBytes(4_100)
        ctx.share.openExistingForWrite(dir.child("$n.locked").smb(), dev.pampa.mycloudgallery.core.nas.smb.SmbOpenMode.CREATE_EXCLUSIVE).use { f -> f.write(bytes, 0, 0, bytes.size); f.flush() }
      }
    }
    return Triple(part, part, part)
  }

  /**
   * **S7a** nell'app: sullo scaffale dei modelli della famiglia di prova (`devices/<id>/models/<sha256>` dell'area comune, D18) il
   * dispositivo `approvato` pubblica, con il nome giusto di ogni modello del catalogo ([shas]), byte diversi. Un dispositivo che scarica un
   * modello dallo scaffale lo deve rifiutare e togliere (`AI_MODEL_MISMATCH`), poi passare alla sorgente dopo.
   */
  suspend fun seedTamperedModels(area: FamilyArea, shas: List<String>): Counts {
    val device = area.state.device("approvato")
    val random = java.security.SecureRandom()
    val files = shas.filter { it.matches(Regex("^[0-9a-f]{64}$")) }.distinct().map { sha -> NasLayout.modelCopy(device.id, sha) to ByteArray(4_096).also(random::nextBytes) }
    area.createAll(area.commonShare, files)
    return Counts("S7aapp", files.size, 0, 0, files.sumOf { it.second.size.toLong() })
  }

  /** **S3 (c)** nell'app: le immagini di [dir] (o le tre minime di [HostileImagesProbe]) in `test-comune/mcg-ostile/`. */
  suspend fun seedImages(area: FamilyArea, dir: File?): Counts {
    val files = dir?.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.sortedBy { it.name }?.map { it.name to it.readBytes() }?.takeIf { it.isNotEmpty() }
      ?: HostileImagesProbe.builtIn()
    area.createAll(area.commonShare, files.map { (name, bytes) -> SharePath.of(FamilyAreaGuard.HOSTILE, name) to bytes })
    return Counts("S3capp", files.size, 0, 0, files.sumOf { it.second.size.toLong() })
  }

  /**
   * **S7** nell'app: la busta [bytes] diventa `test-comune/.mycloudgallery/release/manifest.mcgr`. Al primo uso un manifest che c'era si
   * mette da parte (`manifest-prima-<giro>.mcgr`); una busta messa prima da questo giro si toglie. Vero se il file e' quello atteso.
   */
  suspend fun placeManifest(area: FamilyArea, bytes: ByteArray): Boolean {
    val release = APP.child("release")
    val manifest = release.child("manifest.mcgr")
    val names = area.list(area.commonShare, release).orEmpty().map { it.name.lowercase() }
    val ours = area.guard.ledger().any { it == "f|${area.commonShare.lowercase()}|${manifest.value.lowercase()}" }
    if ("manifest.mcgr" in names) {
      area.op(area.commonShare) { ctx ->
        if (ours) ctx.share.deleteFile(manifest.smb())
        else ctx.share.openForChange(manifest.smb(), exclusive = true).use { it.renameNoReplace(release.child(area.guard.manifestArchive).smb()) }
      }
      if (ours) area.guard.forget('f', area.commonShare, manifest.value)
      area.guard.forget('f', area.commonShare, release.child(area.guard.manifestArchive).value)
    }
    area.create(area.commonShare, manifest, bytes)
    return area.read(area.commonShare, manifest)?.contentEquals(bytes) == true
  }

  /**
   * La pulizia: toglie **solo** cio' che il registro dice creato da questo giro (file, poi cartelle dalla piu' profonda) e rimette il
   * manifest che c'era. `family-vecchia-<giro>` resta (la famiglia di prima: la toglie l'utente, se vuole). Restituisce i residui.
   */
  suspend fun cleanup(area: FamilyArea): List<String> {
    val ledger = area.guard.ledger()
    val files = ledger.filter { it.startsWith("f|") }.map { it.split('|', limit = 3) }
    val dirs = ledger.filter { it.startsWith("d|") }.map { it.split('|', limit = 3) }.sortedByDescending { it[2].count { c -> c == '/' } }
    for ((_, share, path) in files) tolerant { area.op(share) { ctx -> ctx.share.deleteFile(path.replace('/', '\\')) } }
    val release = APP.child("release")
    val manifest = release.child("manifest.mcgr").value.lowercase()
    var restored = false
    if (area.list(area.commonShare, release).orEmpty().any { it.name.equals(area.guard.manifestArchive, ignoreCase = true) }) {
      area.op(area.commonShare) { ctx ->
        ctx.share.openForChange(release.child(area.guard.manifestArchive).smb(), exclusive = true).use { it.renameNoReplace(release.child("manifest.mcgr").smb()) }
      }
      // Il manifest rimesso e' quello di prima, non della prova: fuori dal registro.
      area.guard.forget('f', area.commonShare, manifest)
      restored = true
    }
    for ((_, share, path) in dirs) tolerant { area.op(share) { ctx -> ctx.share.removeEmptyDirectory(path.replace('/', '\\')) } }
    val residue = ArrayList<String>()
    for ((kind, share, path) in files + dirs) {
      if (restored && share == area.commonShare.lowercase() && path == manifest) continue
      val parts = path.split('/')
      val parent = if (parts.size > 1) SharePath.of(*parts.dropLast(1).toTypedArray()) else null
      if (area.list(share, parent).orEmpty().any { it.name.equals(parts.last(), ignoreCase = true) }) residue += "$kind $share" else area.guard.forget(kind[0], share, path)
    }
    area.state.saveLedger(area.guard.ledger())
    return residue
  }

  private suspend fun tolerant(block: suspend () -> Unit) {
    try {
      block()
    } catch (_: AssertionError) {
      // gia' tolto, o la cartella non e' vuota perche' l'app ci ha scritto: lo dice la verifica dei residui
    }
  }

  /**
   * Il confronto di due fotografie delle share di prova: cosa e' cambiato **dentro** `.mycloudgallery/` (la famiglia nuova, il kit nuovo,
   * l'archivio della vecchia: atteso) e **fuori** (atteso zero, salvo `mcg-ostile/` della prova). Una voce che c'era e non c'e' piu'
   * (fuori dall'archivio) e' sempre un errore.
   */
  class Diff(val addedInside: Int, val changedInside: Int, val removedInside: Int, val addedOutside: Int, val changedOutside: Int, val removedOutside: Int, val familyArchived: Boolean) {
    val clean: Boolean get() = addedOutside == 0 && changedOutside == 0 && removedOutside == 0 && removedInside == 0

    fun line(): String = "MCG-SEC famiglia_confronto dentro_aggiunti=$addedInside dentro_cambiati=$changedInside dentro_tolti=$removedInside " +
      "fuori_aggiunti=$addedOutside fuori_cambiati=$changedOutside fuori_tolti=$removedOutside family_archiviata=${if (familyArchived) 1 else 0} pulito=${if (clean) 1 else 0}"
  }

  fun compare(before: List<SnapshotEntry>, after: List<SnapshotEntry>, archive: String): Diff {
    fun key(e: SnapshotEntry) = e.share + "|" + e.path.lowercase()
    val old = before.associateBy(::key)
    val new = after.associateBy(::key)
    val familyOld = ".mycloudgallery/family"
    val archived = ".mycloudgallery/${archive.lowercase()}"
    fun inside(e: SnapshotEntry) = e.path.lowercase().let { it == ".mycloudgallery" || it.startsWith(".mycloudgallery/") }
    fun ignored(e: SnapshotEntry) = e.path.lowercase().let { it == "mcg-ostile" || it.startsWith("mcg-ostile/") }
    var ai = 0; var ci = 0; var ri = 0; var ao = 0; var co = 0; var ro = 0
    for ((k, e) in new) {
      if (ignored(e)) continue
      val o = old[k]
      when {
        o == null -> if (inside(e)) ai++ else ao++
        !e.dir && (o.size != e.size || o.modified != e.modified) -> if (inside(e)) ci++ else co++
      }
    }
    for ((k, e) in old) {
      if (ignored(e) || k in new) continue
      val p = e.path.lowercase()
      // `family` spostata in `family-vecchia-<giro>`: la sua voce c'e' ancora, sotto l'archivio.
      val moved = (p == familyOld || p.startsWith("$familyOld/")) && "${e.share}|${archived + p.removePrefix(familyOld)}" in new
      if (moved) continue
      if (inside(e)) ri++ else ro++
    }
    val familyArchived = after.any { it.dir && it.path.equals(".mycloudgallery/$archive", ignoreCase = true) }
    return Diff(ai, ci, ri, ao, co, ro, familyArchived)
  }

  private fun record(path: String, seq: Long): MetaRecord = MetaRecord.Present(
    SharePath.parseMediaPath(path),
    seq,
    MetaFile(
      stamp = NasStamp(1_000L + seq, 1_792_000_000_000L, null),
      quickHash = QuickHashAlgorithm.ofContent(byteArrayOf(seq.toByte(), 7)),
      mediaId = MediaId.random(),
      versionId = VersionId.random(),
      kind = MediaKind.PHOTO,
      source = MediaSource.CAMERA,
      mime = "image/jpeg",
      prepared = 0,
    ),
  )
}
