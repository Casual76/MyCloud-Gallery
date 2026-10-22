package dev.pampa.mycloudgallery.core.backup.settings

import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.BackupSources
import dev.pampa.mycloudgallery.core.backup.contract.CutoutBackup

/**
 * La forma salvata delle sorgenti del backup (impostazione del dispositivo): l'[BackupSource.id] di ognuna, `camera`, `screenshots` o
 * `folder:<volume>:<percorso relativo>`. Lettura difensiva: un id che non si capisce si ignora (mai un'eccezione, mai una sorgente
 * inventata).
 */
object BackupSourceCodec {

  fun encode(sources: BackupSources): Set<String> = sources.sources.mapTo(LinkedHashSet()) { it.id }

  fun decode(ids: Set<String>): BackupSources = BackupSources(ids.mapNotNullTo(LinkedHashSet(), ::decodeOne))

  fun decodeOne(id: String): BackupSource? =
    when {
      id == BackupSource.Camera.id -> BackupSource.Camera
      id == BackupSource.Screenshots.id -> BackupSource.Screenshots
      id.startsWith(FOLDER_PREFIX) -> {
        val rest = id.removePrefix(FOLDER_PREFIX)
        // Il volume non ha mai i due punti (`external_primary`, l'id di una microSD); il percorso relativo li puo' avere.
        val colon = rest.indexOf(':')
        if (colon <= 0 || colon == rest.length - 1) null else runCatching { BackupSource.Folder(rest.substring(0, colon), rest.substring(colon + 1)) }.getOrNull()
      }
      else -> null
    }

  private const val FOLDER_PREFIX = "folder:"

  /**
   * Le sorgenti che l'onboarding (passo 6) ha scelto: la fotocamera e gli screenshot, ognuna se spuntata; con almeno una scelta anche i ritagli
   * del soggetto (Fase 6, D47: foto fatte dall'app, accese di serie e spegnibili dalle impostazioni del backup come ogni cartella).
   */
  fun fromOnboarding(camera: Boolean, screenshots: Boolean): BackupSources =
    BackupSources(
      buildSet {
        if (camera) add(BackupSource.Camera)
        if (screenshots) add(BackupSource.Screenshots)
        // Chi non ha scelto niente non accende nemmeno i ritagli: "nessuna sorgente" resta "il backup non e' stato voluto".
        if (camera || screenshots) add(CutoutBackup.source())
      },
    )
}
