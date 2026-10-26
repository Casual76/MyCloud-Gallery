package dev.pampa.mycloudgallery.feature.albums.page

import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumRole
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.PermissionInfo

// COSA POSSO FARE IN QUESTO ALBUM (Fase 7, P10; docs/fase7-progetto.md §2.4, D10, C25): il foglio "Permessi" elenca le cose che contano di un
// album condiviso e dice, per ognuna, se posso farla io. **Non ha una tabella sua**: ogni riga e' una cella della tabella dei permessi
// (`AlbumPermissions`, la stessa di chi legge), letta con i ruoli che ho. `PermissionSummaryTest` la rigenera dalla tabella per ogni livello e
// ogni ruolo e confronta.

/** Le cose di cui il foglio parla, nell'ordine in cui le mostra. */
enum class Ability(@StringRes val label: Int) {
  ADD(R.string.album_permission_add),
  REMOVE_OWN(R.string.album_permission_remove_own),
  REMOVE_OTHERS(R.string.album_permission_remove_others),
  RENAME(R.string.album_permission_rename),
  COVER_AND_SORT(R.string.album_permission_cover_sort),
  CHANGE_LEVEL(R.string.album_permission_level),
  CLOSE(R.string.album_permission_close),
}

/** Una riga del foglio: [allowed] se posso farla io. Se no, e' del creatore (la tabella non da' mai altro a un altro account). */
data class AbilityRow(val ability: Ability, val allowed: Boolean)

object PermissionSummary {

  /** Le righe per [info]: dalle operazioni che la tabella da' a me ([PermissionInfo.allowed]) e dalla cella di "togliere" per un mio elemento. */
  fun of(info: PermissionInfo): List<AbilityRow> {
    val roles = if (info.mine) setOf(AlbumRole.CREATOR) else setOf(AlbumRole.OTHER)
    val ownRoles = roles + AlbumRole.ADDER
    val ops = info.allowed
    return listOf(
      AbilityRow(Ability.ADD, AlbumOp.ADD_MEDIA in ops),
      // "Togliere un elemento aggiunto da me": la cella con anche il ruolo di chi l'ha aggiunto (§2.4).
      AbilityRow(Ability.REMOVE_OWN, AlbumPermissions.admits(AlbumOp.REMOVE_ITEM, info.level, ownRoles)),
      AbilityRow(Ability.REMOVE_OTHERS, AlbumOp.REMOVE_ITEM in ops),
      AbilityRow(Ability.RENAME, AlbumOp.RENAME in ops),
      AbilityRow(Ability.COVER_AND_SORT, AlbumOp.SET_COVER in ops && AlbumOp.SET_SORT in ops),
      AbilityRow(Ability.CHANGE_LEVEL, AlbumOp.CHANGE_LEVEL in ops),
      AbilityRow(Ability.CLOSE, AlbumOp.STOP_SHARING in ops && AlbumOp.DELETE_ALBUM in ops),
    )
  }

  /** Il livello si cambia da questo foglio solo se la tabella lo da' a me (solo il creatore o l'erede). */
  fun canChangeLevel(info: PermissionInfo): Boolean = AlbumOp.CHANGE_LEVEL in info.allowed
}
