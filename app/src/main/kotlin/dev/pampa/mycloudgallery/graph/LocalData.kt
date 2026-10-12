package dev.pampa.mycloudgallery.graph

import android.content.Context
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.family.contract.LocalDataEraser
import dev.pampa.mycloudgallery.core.family.lifecycle.FileWipeJournal
import dev.pampa.mycloudgallery.core.network.contract.VpnControlConfig
import dev.pampa.mycloudgallery.feature.diagnostics.contributors.HeartbeatSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Il "battito" del lavoro in background (§2.7, E-P19): l'ora del dispositivo dell'ultimo giro di `TrustSyncWorker`. La
 * Diagnostica ne ricava "ore dall'ultimo battito" e l'avviso "il profilo puo' restare fermo". Un file con un numero.
 */
class HeartbeatStore(private val file: File) : HeartbeatSource {

  override suspend fun lastBeatAt(): Long? = withContext(Dispatchers.IO) {
    try {
      if (file.exists()) file.readText(Charsets.US_ASCII).trim().toLongOrNull()?.takeIf { it > 0 } else null
    } catch (e: IOException) {
      null
    }
  }

  suspend fun beat(at: Long) = withContext(Dispatchers.IO) {
    try {
      file.parentFile?.mkdirs()
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(at.toString(), Charsets.US_ASCII)
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "battito non salvato" }
    }
  }

  suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

  private companion object {
    const val TAG = "Heartbeat"
  }
}

/**
 * La fase (5) dell'azzeramento (`LocalDataEraser`, docs/famiglia-dispositivi.md §3): cancella cio' che l'app sa di
 * questa famiglia sul telefono. Lo stato fidato e le richieste viste li cancella gia' `DeviceSelfWipe`; le chiavi e le
 * credenziali sono gia' sparite nelle fasi (1)-(3).
 *
 * - **attraverso le loro API** (DataStore non tollera che gli si tolga il file da sotto): avanzamento dell'onboarding,
 *   account NAS, impronta di casa, controllo di WireGuard, battito;
 * - **come file**: cache, database (la replica dell'indice, Fase 4), miniature e anteprime future.
 *
 * **Mai** il diario dell'azzeramento ([FileWipeJournal.FILE_NAME]): e' quello che fa riprendere un azzeramento
 * interrotto e mostrare "dispositivo azzerato". Restano anche il registro della Diagnostica (solo codici e numeri, mai
 * dati personali) e le preferenze del telefono (blocco app, preset delle regole): non dicono niente della famiglia.
 *
 * Vero se e' andato tutto bene; un errore si riprova al prossimo avvio (la fase resta da fare).
 */
class AppLocalDataEraser(private val context: Context, private val graph: AppGraph) : LocalDataEraser {

  override suspend fun eraseAll(): Boolean {
    var ok = true
    suspend fun step(name: String, block: suspend () -> Unit) {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        GalleryLog.e(TAG, e) { "cancellazione non riuscita: $name" }
        ok = false
      }
    }
    step("onboarding") { graph.onboardingStore.clear() }
    step("account") { graph.accountStore.clear() }
    step("casa") { graph.homeStore.clear() }
    step("vpn") { graph.vpnConfigStore.save(VpnControlConfig.OFF) }
    step("battito") { graph.heartbeat.clear() }
    step("file") {
      withContext(Dispatchers.IO) {
        if (!deleteChildren(context.cacheDir)) ok = false
        for (name in context.databaseList()) {
          if (!context.deleteDatabase(name) && File(context.getDatabasePath(name).path).exists()) ok = false
        }
        for (dir in MEDIA_DIRS) {
          val target = File(context.noBackupFilesDir, dir)
          if (target.exists() && !target.deleteRecursively()) ok = false
        }
      }
    }
    return ok
  }

  private fun deleteChildren(dir: File?): Boolean {
    if (dir == null || !dir.exists()) return true
    return dir.listFiles().orEmpty().all { it.deleteRecursively() }
  }

  private companion object {
    const val TAG = "LocalDataEraser"

    /** Le cartelle dei dati della galleria in `noBackupFilesDir` (le riempiono le fasi 3-6). Mai la radice. */
    val MEDIA_DIRS = listOf("thumbnails", "previews", "replica")
  }
}
