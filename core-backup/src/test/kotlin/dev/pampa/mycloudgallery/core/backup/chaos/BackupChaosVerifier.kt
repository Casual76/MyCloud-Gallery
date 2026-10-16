package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupPaths
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Locale

/**
 * Il **verificatore degli invarianti del §5.4** (docs/fase5-progetto.md) per il backup della 5A. Lavora su uno [BackupState] (fatti
 * semplici, anche letti da un file: `BackupStateFile`), quindi gira nel banco dopo ogni morte e a fine passo, ma anche DA SOLO su uno
 * stato salvato. Non legge il NAS e non conosce il motore: giudica soltanto cio' che c'e'.
 *
 * 1. nessun file a meta' visibile: ogni file nuovo fuori da `.mycloudgallery/` ha lo SHA-256 di un contenuto che il telefono ha avuto
 *    (prima o dopo una riscrittura) o di un file che c'era prima del giro;
 * 2. nessun doppione: nessun file nuovo ha i byte di un file che c'era gia' nell'area privata, e nessun byte nuovo compare due volte;
 * 3. nessuna sostituzione: ogni file che c'era ha ancora gli stessi byte, lo stesso nome e lo stesso `fileId`;
 * 4. nessuna perdita: ogni media "al sicuro" ha nell'area privata una copia presente con lo SHA-256 della versione;
 * 5. hash al 100%: a lavoro finito la rilettura e' completa, senza nessuna cache;
 * 6. temporanei: a meta' solo i propri `<io>-bk-` citati da un elemento non finito; a lavoro finito nessuno; la pulizia non ne toglie
 *    uno citato dal diario (D33);
 * 7. catalogo coerente: ogni elemento `DONE` o `LINKED_EXISTING` ha la sua copia al sicuro; nessun "al sicuro" per l'ultimo elemento
 *    di un media se e' `FAILED`;
 * 8. diario coerente: ogni transizione e' ammessa da [UploadStateMachine]; i nomi finali del diario sono sul NAS;
 * 9. il lavoro arriva in fondo (a lavoro finito): ogni elemento e' `DONE`, `LINKED_EXISTING` o `GONE`, nessun `FAILED` per un guasto
 *    passeggero, ogni media ancora sul telefono e' al sicuro;
 * 10. nessun file fuori posto: niente di nuovo fuori da `Backup/` dell'area privata (e dai temporanei);
 * 11. il NAS si legge: una lettura che non riesce non passa in silenzio (le note del banco);
 * 12. il motore non lancia per un guasto del NAS o del telefono.
 *
 * [showNames] = falso nei rapporti delle prove contro il NAS vero: niente nomi di file, solo cio' che e' successo.
 */
object BackupVerifier {

  fun check(state: BackupState, phase: Phase, showNames: Boolean = true): List<Violation> {
    val out = ArrayList<Violation>()
    fun add(invariant: Invariant, detail: String) {
      out += Violation(invariant, detail)
    }
    fun nm(path: String) = if (showNames) path else "un file"

    val privateShare = state.privateShare.lowercase(Locale.ROOT)
    val beforeByKey = state.before.associateBy { it.key }
    val nowByKey = HashMap<String, NasFileFact>().also { map -> state.files.forEach { map[it.key] = it } }
    val citedLower = state.cited.map { it.lowercase(Locale.ROOT) }.toSet()

    // 11 e 12: cio' che il banco ha visto da solo.
    state.notes.forEach { add(it.invariant, it.text) }

    // 5. Hash al 100%.
    if (phase == Phase.FINAL) {
      if (state.cacheHits != 0) add(Invariant.HASH_100, "la rilettura finale ha usato una cache per ${state.cacheHits} file")
      if (state.filesHashed < state.files.size) add(Invariant.HASH_100, "la rilettura finale ha riletto ${state.filesHashed} file su ${state.files.size}")
    }

    // 3. Nessuna sostituzione.
    for (old in state.before) {
      val cur = nowByKey[old.key]
      when {
        cur == null -> if (!old.appArea) add(Invariant.REPLACED, "sparito un file che c'era (${nm(old.path)})")
        cur.sha != old.sha || cur.size != old.size -> add(Invariant.REPLACED, "byte cambiati in un file che c'era (${nm(old.path)})")
        cur.fileId != old.fileId -> add(Invariant.REPLACED, "fileId cambiato in un file che c'era (${nm(old.path)})")
        cur.name != old.name -> add(Invariant.REPLACED, "nome cambiato in un file che c'era (${nm(old.path)})")
      }
    }

    // 1, 2, 10. I file nuovi visibili.
    val knownShas = state.phoneShas + state.before.filter { !it.appArea }.map { it.sha }
    val privateBeforeShas = state.before.filter { !it.appArea && it.share.lowercase(Locale.ROOT) == privateShare }.map { it.sha }.toSet()
    val newVisible = state.files.filter { it.key !in beforeByKey && !it.appArea }
    for (f in newVisible) {
      val inPrivate = f.share.lowercase(Locale.ROOT) == privateShare
      if (f.sha !in knownShas) add(Invariant.HALF_FILE, "file visibile con byte che non sono di nessun media del telefono ne' di un file che c'era (${nm(f.path)})")
      if (!(inPrivate && f.path.lowercase(Locale.ROOT).startsWith("backup/"))) add(Invariant.OUT_OF_PLACE, "file scritto fuori dalle radici ammesse (${nm(f.path)})")
      if (inPrivate && f.sha in privateBeforeShas) add(Invariant.DUPLICATE, "doppione di un file che c'era gia' (${nm(f.path)})")
    }
    newVisible.filter { it.share.lowercase(Locale.ROOT) == privateShare }.groupBy { it.sha }.filterValues { it.size > 1 }.forEach { (_, same) ->
      add(Invariant.DUPLICATE, "gli stessi byte pubblicati ${same.size} volte (${same.joinToString { nm(it.path) }})")
    }

    // 6. Temporanei (e il resto della cartella dell'app).
    for (f in state.files.filter { it.key !in beforeByKey && it.appArea }) {
      val lower = f.path.lowercase(Locale.ROOT)
      if (!lower.startsWith("${NasFileFact.APP_DIR}tmp/")) {
        add(Invariant.OUT_OF_PLACE, "file dell'app che il backup non scrive (${nm(f.path)})")
        continue
      }
      when {
        !isBackupTemp(f.path, state.deviceId) -> add(Invariant.TEMP, "temporaneo che non e' del backup (${nm(f.path)})")
        phase == Phase.FINAL -> add(Invariant.TEMP, "temporaneo rimasto a lavoro finito (${nm(f.path)})")
        lower !in citedLower -> add(Invariant.TEMP, "temporaneo non citato dal diario (${nm(f.path)})")
      }
    }
    state.cleanerRemovedCited.forEach { add(Invariant.TEMP, "la pulizia ha tolto un temporaneo citato dal diario (${nm(it)})") }

    // 4. Nessuna perdita: ogni media al sicuro ha una copia privata presente con i byte della versione.
    for (m in state.media.filter { it.safe }) {
      val good = state.copies.any { c ->
        c.media == m.media && c.version == m.version && c.share.lowercase(Locale.ROOT) == privateShare && c.verified &&
          nowByKey[NasFileFact.keyOf(c.share, c.path)]?.let { it.sha == m.fullHash && it.size == m.size } == true
      }
      if (!good) add(Invariant.LOSS, "un media al sicuro senza una copia privata presente con i byte della versione")
    }

    // 7 e 8. Catalogo e diario.
    val mediaByVersion = state.media.associateBy { it.media + "/" + it.version }
    val lastItemOfMedia = state.items.groupBy { it.media }.mapValues { (_, own) -> own.maxOf { it.id } }
    val itemsById = state.items.associateBy { it.id }
    for (item in state.items) {
      val safe = mediaByVersion[item.media + "/" + item.version]?.safe == true
      val finalOk = item.finalPath?.let { nowByKey[NasFileFact.keyOf(item.share, it)] != null } == true
      when (item.state) {
        UploadState.DONE.code, UploadState.LINKED_EXISTING.code -> {
          if (!safe) add(Invariant.CATALOG, "elemento #${item.id} ${item.state} senza la sua copia al sicuro")
          if (!finalOk) add(Invariant.DIARY, "nome finale di #${item.id} assente dal NAS")
        }
        UploadState.PUBLISHED.code, UploadState.LINKED.code ->
          if (!finalOk) add(Invariant.DIARY, "nome finale di #${item.id} (${item.state}) assente dal NAS")
        UploadState.FAILED.code ->
          if (lastItemOfMedia[item.media] == item.id && safe) add(Invariant.CATALOG, "al sicuro per l'elemento fallito #${item.id}")
      }
    }
    for (t in state.transitions) {
      val item = itemsById[t.item] ?: continue
      val kind = UploadKind.entries.firstOrNull { it.code == item.kind }
      val from = UploadState.entries.firstOrNull { it.code == t.from }
      val to = UploadState.entries.firstOrNull { it.code == t.to }
      if (kind == null || from == null || to == null) {
        add(Invariant.DIARY, "stato o tipo sconosciuto nel diario (#${t.item}: ${t.from} -> ${t.to})")
      } else if (!UploadStateMachine.canTransition(UploadStateMachine.flowOf(kind), from, to)) {
        add(Invariant.DIARY, "transizione non ammessa ${t.from} -> ${t.to} (#${t.item})")
      }
    }

    // 9. A lavoro finito il lavoro e' arrivato in fondo.
    if (phase == Phase.FINAL) {
      val finished = setOf(UploadState.DONE.code, UploadState.LINKED_EXISTING.code, UploadState.GONE.code)
      for (item in state.items) {
        val superseded = item.state == UploadState.FAILED.code && lastItemOfMedia[item.media] != item.id
        if (item.state in finished || superseded) continue
        add(Invariant.CONVERGENCE, "elemento #${item.id} fermo in ${item.state}")
      }
      for (m in state.media) {
        if (m.onPhone && !m.safe) add(Invariant.CONVERGENCE, "un media ancora sul telefono non e' al sicuro${m.problem?.let { " (problema: $it)" }.orEmpty()}")
      }
    }
    return out
  }

  private fun isBackupTemp(path: String, deviceId: String): Boolean = try {
    NasBackupPaths.isBackupTempOf(SharePath.parse(path), deviceId)
  } catch (_: Exception) {
    // Un percorso che SharePath rifiuta (TrustViolation) non e' un temporaneo del backup.
    false
  }
}
