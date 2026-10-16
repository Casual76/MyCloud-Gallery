package dev.pampa.mycloudgallery.core.backup.testing

import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.NasOpsPaths
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

/**
 * Il verificatore degli **invarianti del §5.4** per la 5A (`docs/fase5-progetto.md`), sul banco [BackupTestKit]: P0 lo usa in
 * `EveryCallTest`, P6 nel banco delle 500 interruzioni (gira anche da solo, dopo un giro qualunque). Ogni controllo rilegge i byte dal
 * NAS finto per intero (invariante 5: nessuna cache) e restituisce le violazioni come testo senza dati dell'utente oltre ai percorsi
 * finti del banco.
 *
 * 1. nessun file a meta' visibile: ogni file nuovo fuori da `.mycloudgallery/` ha lo SHA-256 di un media del telefono (prima o adesso)
 *    o di un file che c'era prima del giro (la copia del guardiano, D9, ha i byte della copia dell'area comune);
 * 2. nessun doppione creato: nessun file nuovo ha i byte di un file che c'era gia' nell'area privata, e nessun byte nuovo compare due
 *    volte;
 * 3. nessuna sostituzione: ogni file che c'era ha gli stessi byte, lo stesso nome e lo stesso `fileId`;
 * 4. nessuna perdita: ogni media "al sicuro" ha nell'area privata una copia presente con lo SHA-256 della versione;
 * 6. temporanei: a meta' solo i propri `<io>-bk-` citati da un elemento non finito; a lavoro finito nessuno;
 * 7. catalogo coerente: ogni elemento `DONE` o `LINKED_EXISTING` ha la sua copia al sicuro; nessun "al sicuro" per un `FAILED`;
 * 8. diario coerente: ogni transizione e' ammessa da `UploadStateMachine`; i nomi finali del diario sono sul NAS;
 * 9. (a lavoro finito) ogni elemento e' `DONE`, `LINKED_EXISTING` o `GONE`;
 * 10. nessun file nuovo fuori da `Backup/` (e dai temporanei).
 * L'11 ("il NAS si legge") lo prova il motore stesso: una lettura che non riesce ferma il giro (i test dei guasti lo vedono).
 */
object BackupInvariants {

  /** Un file sul NAS: share, percorso scritto come sul server, impronta, `fileId`. */
  class FileFact(val share: String, val path: String, val sha: Sha256, val fileId: Long?) {
    val name: String get() = path.substringAfterLast('/')

    val appArea: Boolean get() = NasOpsPaths.isAppArea(SharePath.parse(path))
  }

  /** Cosa c'era: i file di tutte le share del banco e le impronte dei media del telefono. */
  class Snapshot(val files: Map<String, FileFact>, val phoneShas: Set<Sha256>)

  private fun key(share: String, path: String) = share.lowercase(Locale.ROOT) + ":" + path.lowercase(Locale.ROOT)

  suspend fun snapshot(kit: BackupTestKit): Snapshot {
    val files = LinkedHashMap<String, FileFact>()
    for (share in listOf(kit.share, kit.commonShare)) {
      for (path in kit.nas.filePaths(share)) {
        val bytes = kit.nas.bytes(share, path) ?: continue
        val entry = kit.nas.reader.stat(share, SharePath.parse(path), Purpose.BACKGROUND).okOrNull()
        files[key(share, path)] = FileFact(share, path, FakePhoneBytes.sha256Of(NasContent.of(bytes)), entry?.fileId)
      }
    }
    return Snapshot(files, kit.phone.allSha256())
  }

  suspend fun check(kit: BackupTestKit, before: Snapshot, final: Boolean): List<String> {
    val v = ArrayList<String>()
    val now = snapshot(kit)
    val items = kit.store.all()
    val phoneShas = before.phoneShas + now.phoneShas
    val knownShas = phoneShas + before.files.values.filter { !it.appArea }.map { it.sha }

    // 3. Nessuna sostituzione.
    for ((k, old) in before.files) {
      val cur = now.files[k]
      when {
        cur == null -> if (!old.appArea) v += "3: sparito un file che c'era (${old.path})"
        cur.sha != old.sha -> v += "3: byte cambiati in un file che c'era (${old.path})"
        cur.fileId != old.fileId -> v += "3: fileId cambiato (${old.path})"
        cur.name != old.name -> v += "3: nome cambiato (${old.path})"
      }
    }

    // 1, 2, 10. I file nuovi visibili.
    val newVisible = now.files.filterKeys { it !in before.files }.values.filter { !it.appArea }
    val privateBefore = before.files.values.filter { !it.appArea && it.share.equals(kit.share, ignoreCase = true) }.map { it.sha }.toSet()
    for (f in newVisible) {
      if (f.sha !in knownShas) v += "1: file visibile con byte che non sono di nessun media del telefono ne' di un file che c'era (${f.path})"
      if (!f.path.startsWith("Backup/")) v += "10: file scritto fuori dalle radici ammesse (${f.path})"
      if (f.share.equals(kit.share, ignoreCase = true) && f.sha in privateBefore) v += "2: doppione di un file che c'era gia' (${f.path})"
    }
    newVisible.groupBy { it.share.lowercase(Locale.ROOT) to it.sha }.filterValues { it.size > 1 }.forEach { (_, dup) ->
      v += "2: gli stessi byte pubblicati due volte (${dup.joinToString { it.path }})"
    }

    // 6. Temporanei.
    val cited = kit.store.citedTemps().map { it.value.lowercase(Locale.ROOT) }.toSet()
    for (t in now.files.values.filter { it.path.lowercase(Locale.ROOT).startsWith(".mycloudgallery/tmp/") }) {
      if (key(t.share, t.path) in before.files) continue
      val path = SharePath.parse(t.path)
      when {
        !NasBackupPaths.isBackupTempOf(path, kit.deviceId) -> v += "6: temporaneo che non e' del backup (${t.path})"
        final -> v += "6: temporaneo rimasto a lavoro finito (${t.path})"
        t.path.lowercase(Locale.ROOT) !in cited -> v += "6: temporaneo non citato dal diario (${t.path})"
      }
    }

    // 4, 7. Catalogo.
    for (m in kit.catalog.allMedia()) {
      if (!kit.catalog.isSafe(m.mediaId)) continue
      val ok = kit.catalog.copiesOf(m.mediaId)
        .filter { it.share.equals(kit.share, ignoreCase = true) && it.verifiedAtMillis != null && it.versionId == m.versionId }
        .any { c -> now.files[key(c.share, c.path.value)]?.sha.let { it != null && it == m.fullHash } }
      if (!ok) v += "4: un media al sicuro senza una copia privata con i byte della versione"
    }
    for (item in items) {
      if (item.state == UploadState.DONE || item.state == UploadState.LINKED_EXISTING) {
        if (!kit.catalog.isSafe(item.mediaId)) v += "7: elemento #${item.id} ${item.state.code} senza la sua copia al sicuro"
        val fp = item.finalPath
        if (fp == null || now.files[key(item.share, fp.value)] == null) v += "8: nome finale di #${item.id} assente dal NAS"
      }
      if (item.state == UploadState.FAILED && kit.catalog.isSafe(item.mediaId)) v += "7: al sicuro per l'elemento fallito #${item.id}"
      if (final && item.state !in FINISHED) v += "9: elemento #${item.id} fermo in ${item.state.code}"
    }

    // 8. Diario: ogni transizione registrata e' ammessa.
    val kinds = items.associate { it.id to it.flow }
    for (t in kit.store.transitions) {
      val flow = kinds[t.item] ?: continue
      if (!UploadStateMachine.canTransition(flow, t.from, t.to)) v += "8: transizione non ammessa ${t.from.code} -> ${t.to.code}"
    }
    return v
  }

  private val FINISHED = setOf(UploadState.DONE, UploadState.LINKED_EXISTING, UploadState.GONE)
}
