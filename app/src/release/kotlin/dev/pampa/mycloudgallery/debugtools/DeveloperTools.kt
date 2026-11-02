package dev.pampa.mycloudgallery.debugtools

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailSource
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailSource
import dev.pampa.mycloudgallery.core.nas.session.ShareGuard
import dev.pampa.mycloudgallery.gallery.DebugLibraryKit
import dev.pampa.mycloudgallery.gallery.SyntheticLibraryKit

/**
 * Release: nessuno strumento di sviluppo. Stessa forma della versione debug (src/debug), tutto vuoto: il catalogo dei
 * componenti, la Libreria sintetica e la famiglia pronta per l'emulatore non esistono, quindi non entrano nell'APK
 * (tools/check.ps1 lo verifica sul dex).
 */
internal object DeveloperTools {

  /** Nessuna Libreria sintetica in release: il menu "Vista" non la offre. */
  fun syntheticKit(): SyntheticLibraryKit? = null

  fun syntheticThumbnails(): ThumbnailSource = NoThumbnailSource

  /** Nessun NAS sintetico in release (Fase 4A, P11): la voce di Impostazioni → Sviluppo non esiste. */
  fun nasLibrary(): DebugLibraryKit? = null

  /** Nessuna scorciatoia: lo stato del dispositivo e' sempre quello vero. */
  @Suppress("UNUSED_PARAMETER")
  fun familyOverride(context: Context): FamilySetupRepository? = null

  /** Nessuna recinzione delle prove (D33 vale solo in debug): `ShareFence` non entra nell'APK di release. */
  fun shareGuard(): ShareGuard? = null

  /** Il cestino del NAS tiene le voci 30 giorni, sempre: il gancio della prova F20 esiste solo in debug. */
  @Suppress("UNUSED_PARAMETER")
  fun trashRetentionMillis(context: Context): Long? = null

  /** Nessuna recinzione del telefono (D13 vale solo in debug con gli account di prova): in release valgono D12 e le sorgenti scelte. */
  fun backupFence(): BackupFence? = null

  /** Nessuna cartella recintata dei ritagli (D13 vale solo in debug): in release `Pictures/MyCloud Gallery/Ritagli/` e basta. */
  fun fencedCutouts(): dev.pampa.mycloudgallery.backup.FencedCutouts? = null

  /** Nessun widget di prova in release (Fase 7, P9): niente da ridisegnare quando si nasconde qualcosa. */
  @Suppress("UNUSED_PARAMETER")
  fun hiddenChanged(context: Context) = Unit

  fun NavGraphBuilder.destinations(nav: NavController) = Unit

  fun settingsSection(nav: NavController): (@Composable () -> Unit)? = null

  /** Nessuno strumento di debug, quindi nessun dato di debug da azzerare (I11: in debug sono i dati dello strumento di valutazione). */
  fun erasableDirs(): List<String> = emptyList()

  /** Fase 6 (P11): lo strumento di valutazione della ricerca (P9) esiste solo in debug. */
  @Suppress("UNUSED_PARAMETER")
  fun installSearch(stack: dev.pampa.mycloudgallery.ai.AiStack, gallery: dev.pampa.mycloudgallery.gallery.GalleryGraph, context: Context) = Unit

  /** Fase 8 (P13): lo strumento di valutazione delle persone esiste solo in debug. */
  @Suppress("UNUSED_PARAMETER")
  fun installPeople(graph: Any, people: Any) = Unit

  /** Fase 9 (P13): lo strumento di valutazione dei ricordi esiste solo in debug. */
  @Suppress("UNUSED_PARAMETER")
  fun installMemories(graph: Any) = Unit

  /** Fase 8 (DR-2, F109): nessuna ricetta forzata; la ricetta dei volti e' sempre quella di serie. */
  @Suppress("UNUSED_PARAMETER")
  fun facesRecipe(context: Context): Int? = null

  /** Fase 10 (P13, D31): la release (e le varianti di misura) si fida solo delle due chiavi di pubblicazione del manifest. */
  fun releaseTrust(): dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust = dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust.RELEASE

  /** Fase 10 (P13, D33): il canale dalla versione (`1.1.0-beta.N` = beta, il resto stabile); mai `DEBUG` fuori dalla build di sviluppo. */
  fun releaseChannel(versionName: String): dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel =
    if ("-beta" in versionName) dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel.BETA else dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel.STABLE
}
