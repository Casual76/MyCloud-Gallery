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
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
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
enum class StripAction {
  NONE, OPEN_TO_SHARE, OPEN_SUGGESTED, ADOPT_FOLDER_FILES, BRING_BACK_REJECTED, OPEN_PERMISSIONS, USE_NEW_FOLDER, RESTORE_REVOKED,

  /** Revisione B (SH-2, E-SH18): "Riprova" gli elementi non andati di una condivisione finita con dei problemi (un piano nuovo, con il foglio). */
  RETRY_SHARE,
}

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
   * un'informazione); [creatorName] il nome di chi ha creato l'album (per la riga dei permessi). [heldByRelease] (CA-1 della revisione funzionale della Fase 10): le
   * scritture le ferma il manifest firmato, e la condivisione in pausa dice "questa versione va aggiornata" invece di "riprendo da sola".
   */
  fun of(
    resources: Resources,
    ready: AlbumPageState.Ready,
    suggested: SuggestionsState,
    creatorName: String?,
    canAdopt: Boolean = true,
    canBringBack: Boolean = true,
    heldByRelease: Boolean = false,
  ): List<Strip> =
    buildList {
      for (banner in ready.banners) add(of(resources, banner, canAdopt, canBringBack, heldByRelease))
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

  private fun of(resources: Resources, banner: AlbumBanner, canAdopt: Boolean, canBringBack: Boolean, heldByRelease: Boolean): Strip = when (banner) {
    is AlbumBanner.Sharing -> sharing(resources, banner.progress, heldByRelease)
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
    is AlbumBanner.UseNewFolder -> Strip(
      "folder_changed", Icons.Rounded.FolderOpen, FluidTone.Warning, resources.getString(R.string.album_banner_folder_changed),
      resources.getString(R.string.album_banner_use_new_folder_detail, banner.folder), StripAction.USE_NEW_FOLDER,
    )
    is AlbumBanner.RevokedItems -> Strip(
      "revoked", Icons.Rounded.Restore, FluidTone.Info, resources.getQuantityString(R.plurals.album_banner_revoked, banner.count, banner.count),
      resources.getString(R.string.album_banner_revoked_detail), StripAction.RESTORE_REVOKED,
    )
    is AlbumBanner.NotAdded -> Strip(
      "not_added", Icons.Rounded.ErrorOutline, FluidTone.Warning, resources.getString(R.string.album_banner_not_added),
      resources.getQuantityString(R.plurals.album_banner_not_added_detail, banner.count, banner.count),
      if (canBringBack) StripAction.BRING_BACK_REJECTED else StripAction.NONE,
    )
    AlbumBanner.PeopleOff -> Strip(
      "people_off", Icons.Rounded.PersonOff, FluidTone.Info, resources.getString(R.string.album_banner_people_off), resources.getString(R.string.album_banner_people_off_detail),
    )
    is AlbumBanner.PeopleLost -> Strip(
      "people_lost", Icons.Rounded.ErrorOutline, FluidTone.Warning, resources.getQuantityString(R.plurals.album_banner_people_lost, banner.count, banner.count),
      resources.getString(R.string.album_banner_people_lost_detail),
    )
    AlbumBanner.Inherited -> Strip(
      "inherited", Icons.Rounded.Verified, FluidTone.Success, resources.getString(R.string.album_banner_inherited), resources.getString(R.string.album_banner_inherited_detail),
    )
  }

  private fun sharing(resources: Resources, progress: ShareOpState, heldByRelease: Boolean): Strip = when (progress) {
    is ShareOpState.Running -> Strip(
      "sharing", Icons.Rounded.CloudUpload, FluidTone.Primary, progressTitle(resources, progress.kind, progress.done, progress.total),
      resources.getString(R.string.album_banner_sharing_running_detail),
    )
    is ShareOpState.Paused -> {
      val why = resources.getString(
        when (progress.reason) {
          ShareWait.NO_SPACE -> R.string.album_banner_paused_space
          ShareWait.WRITES_BLOCKED -> if (heldByRelease) R.string.album_banner_paused_release else R.string.album_banner_paused_blocked
          ShareWait.LOCKED -> R.string.album_banner_paused_locked
          ShareWait.NAS_UNREACHABLE -> R.string.album_banner_paused_nas
          ShareWait.RULES -> R.string.album_banner_paused_rules
          ShareWait.CATALOG -> R.string.album_banner_paused_catalog
        },
      )
      // Revisione B (SH-2): chi non e' ancora partito riparte da solo solo per 10 minuti (la conferma); poi la striscia offre "Riprova".
      val detail = if (progress.notStarted > 0) resources.getString(R.string.album_banner_paused_not_started, why) else why
      Strip("sharing", Icons.Rounded.PauseCircle, FluidTone.Warning, resources.getString(R.string.album_banner_paused), detail)
    }
    is ShareOpState.PartlyDone -> Strip(
      "sharing", Icons.Rounded.ErrorOutline, FluidTone.Warning, resources.getString(R.string.album_banner_sharing_partly),
      // Revisione B (SH-2): il motivo principale e il gesto ("Tocca per riprovare"): prima la striscia non diceva perche' e non faceva niente.
      resources.getString(
        R.string.album_banner_sharing_partly_retry, GalleryNumbers.format(progress.done),
        GalleryNumbers.format(progress.failures.values.sum() + progress.cancelled), resources.getString(mainReason(progress)),
      ),
      StripAction.RETRY_SHARE,
    )
    is ShareOpState.Done -> Strip(
      "sharing", Icons.Rounded.Group, FluidTone.Success, resources.getString(R.string.album_banner_sharing_done, GalleryNumbers.format(progress.done)), "",
    )
  }

  /** Il motivo piu' frequente fra gli elementi non andati (a parita', il primo nell'ordine dei codici). */
  private fun mainReason(progress: ShareOpState.PartlyDone): Int {
    val top = progress.failures.entries.sortedWith(compareByDescending<Map.Entry<ShareFailure, Int>> { it.value }.thenBy { it.key.ordinal }).firstOrNull()?.key
    return when (top) {
      ShareFailure.SOURCE_CHANGED -> R.string.album_problem_source_changed
      ShareFailure.SOURCE_GONE -> R.string.album_problem_source_gone
      ShareFailure.NOT_ALLOWED -> R.string.album_problem_not_allowed
      ShareFailure.TRANSFER_FAILED -> R.string.album_problem_transfer_failed
      ShareFailure.NOT_STARTED -> R.string.album_problem_not_started
      null -> R.string.album_problem_cancelled
    }
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
