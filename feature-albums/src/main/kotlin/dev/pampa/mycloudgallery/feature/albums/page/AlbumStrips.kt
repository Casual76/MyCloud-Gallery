package dev.pampa.mycloudgallery.feature.albums.page

import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.SuggestionsState
import dev.pampa.mycloudgallery.feature.albums.ui.AlbumText

// LE STRISCE IN TESTA ALLA PAGINA (Fase 7, P10; docs/fase7-progetto.md §1.5 `AlbumBanner`, `ShareProgress`, `PermissionInfo`; D9, D17, D39; E-SH13,
// E-SH16): cio' che la pagina dice prima delle foto. Una funzione pura sopra `Resources`: la schermata ne disegna le righe in un gruppo solo
// (incolonnate, mai sovrapposte), i test ne guardano l'ordine e le parole. Prima cio' che e' in corso, poi cio' che chiede un gesto, poi le
// informazioni; i permessi chiudono il gruppo.

/** Cosa succede toccando una striscia. */
enum class StripAction { NONE, OPEN_TO_SHARE, OPEN_SUGGESTED, ADOPT_FOLDER_FILES, BRING_BACK_REJECTED, OPEN_PERMISSIONS }

/** Una riga di striscia: icona e tono (il colore sta sulla piastrella), parole, e cosa fa al tocco. */
data class Strip(
  val id: String,
  val icon: ImageVector,
  val tone: FluidTone,
  val title: String,
  val detail: String,
  val action: StripAction = StripAction.NONE,
)

internal object AlbumStrips {

  /**
   * Le strisce di una pagina pronta. [canAdopt] e [canBringBack] dicono se la porta ha dei media da proporre (altrimenti la striscia e' solo
   * un'informazione); [creatorName] il nome di chi ha creato l'album (per la riga dei permessi).
   */
  fun of(resources: Resources, ready: AlbumPageState.Ready, suggested: SuggestionsState, creatorName: String?, canAdopt: Boolean = true, canBringBack: Boolean = true): List<Strip> =
    buildList {
      for (banner in ready.banners) add(of(resources, banner, canAdopt, canBringBack))
      (suggested as? SuggestionsState.Some)?.let {
        add(
          Strip(
            "suggested", Icons.Rounded.AutoAwesome, FluidTone.Primary, resources.getString(R.string.album_suggested_row),
            resources.getQuantityString(R.plurals.album_suggested_row_detail, it.count, it.count), StripAction.OPEN_SUGGESTED,
          ),
        )
      }
      val permissions = ready.permissions
      val album = ready.content.album as? Album.Shared
      if (permissions != null && album != null) {
        val who = if (permissions.mine) resources.getString(R.string.album_permissions_creator_you) else resources.getString(R.string.album_permissions_creator, creatorName.orEmpty())
        add(Strip("permissions", Icons.Rounded.Lock, FluidTone.Info, resources.getString(AlbumText.level(permissions.level)), who, StripAction.OPEN_PERMISSIONS))
      }
    }

  private fun of(resources: Resources, banner: AlbumBanner, canAdopt: Boolean, canBringBack: Boolean): Strip = when (banner) {
    is AlbumBanner.Sharing -> sharing(resources, banner.progress)
    is AlbumBanner.Incoming -> Strip(
      "incoming", Icons.Rounded.CloudSync, FluidTone.Info, resources.getQuantityString(R.plurals.album_banner_incoming, banner.count, banner.count),
      resources.getString(R.string.album_banner_incoming_detail),
    )
    is AlbumBanner.ToShare -> Strip(
      "to_share", Icons.Rounded.Share, FluidTone.Primary, resources.getQuantityString(R.plurals.album_banner_to_share, banner.count, banner.count),
      resources.getString(R.string.album_banner_to_share_detail), StripAction.OPEN_TO_SHARE,
    )
    is AlbumBanner.NewItems -> Strip(
      "new", Icons.Rounded.NewReleases, FluidTone.Primary, resources.getQuantityString(R.plurals.album_banner_new, banner.count, banner.count),
      resources.getString(R.string.album_banner_new_detail),
    )
    is AlbumBanner.FilesFromPc -> Strip(
      "files_from_pc", Icons.Rounded.FolderOpen, FluidTone.Info, resources.getQuantityString(R.plurals.album_banner_files_from_pc, banner.count, banner.count),
      resources.getString(R.string.album_banner_files_from_pc_detail), if (canAdopt) StripAction.ADOPT_FOLDER_FILES else StripAction.NONE,
    )
    AlbumBanner.FolderChanged -> Strip(
      "folder_changed", Icons.Rounded.FolderOpen, FluidTone.Warning, resources.getString(R.string.album_banner_folder_changed),
      resources.getString(R.string.album_banner_folder_changed_detail),
    )
    is AlbumBanner.NotAdded -> Strip(
      "not_added", Icons.Rounded.ErrorOutline, FluidTone.Warning, resources.getString(R.string.album_banner_not_added),
      resources.getQuantityString(R.plurals.album_banner_not_added_detail, banner.count, banner.count),
      if (canBringBack) StripAction.BRING_BACK_REJECTED else StripAction.NONE,
    )
    AlbumBanner.Inherited -> Strip(
      "inherited", Icons.Rounded.Verified, FluidTone.Success, resources.getString(R.string.album_banner_inherited), resources.getString(R.string.album_banner_inherited_detail),
    )
  }

  private fun sharing(resources: Resources, progress: ShareOpState): Strip = when (progress) {
    is ShareOpState.Running -> Strip(
      "sharing", Icons.Rounded.CloudUpload, FluidTone.Primary, progressTitle(resources, progress.kind, progress.done, progress.total),
      resources.getString(R.string.album_banner_sharing_running_detail),
    )
    is ShareOpState.Paused -> Strip(
      "sharing", Icons.Rounded.PauseCircle, FluidTone.Warning, resources.getString(R.string.album_banner_paused),
      resources.getString(
        when (progress.reason) {
          ShareWait.NO_SPACE -> R.string.album_banner_paused_space
          ShareWait.WRITES_BLOCKED -> R.string.album_banner_paused_blocked
          ShareWait.LOCKED -> R.string.album_banner_paused_locked
          ShareWait.NAS_UNREACHABLE -> R.string.album_banner_paused_nas
          ShareWait.RULES -> R.string.album_banner_paused_rules
        },
      ),
    )
    is ShareOpState.PartlyDone -> Strip(
      "sharing", Icons.Rounded.ErrorOutline, FluidTone.Warning, resources.getString(R.string.album_banner_sharing_partly),
      resources.getString(
        R.string.album_banner_sharing_partly_detail, GalleryNumbers.format(progress.done), GalleryNumbers.format(progress.failures.values.sum() + progress.cancelled),
      ),
    )
    is ShareOpState.Done -> Strip(
      "sharing", Icons.Rounded.Group, FluidTone.Success, resources.getString(R.string.album_banner_sharing_done, GalleryNumbers.format(progress.done)), "",
    )
  }

  private fun progressTitle(resources: Resources, kind: SharePlanKind, done: Int, total: Int): String = resources.getString(
    when (kind) {
      SharePlanKind.SHARE -> R.string.album_banner_sharing_running
      SharePlanKind.BRING_BACK -> R.string.album_banner_bring_back
      SharePlanKind.ADOPT -> R.string.album_banner_adopt
    },
    GalleryNumbers.format(done),
    GalleryNumbers.format(total),
  )
}
