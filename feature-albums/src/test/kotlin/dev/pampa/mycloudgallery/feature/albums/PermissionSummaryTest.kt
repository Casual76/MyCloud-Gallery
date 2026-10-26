package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumRole
import dev.pampa.mycloudgallery.core.albums.contract.PermissionCondition
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.PermissionInfo
import dev.pampa.mycloudgallery.feature.albums.page.Ability
import dev.pampa.mycloudgallery.feature.albums.page.PermissionSummary
import org.junit.Test

/**
 * Il foglio "Permessi" (Fase 7, P10; §2.4, D10, C25) **non ha una tabella sua**: ogni riga e' una cella di `AlbumPermissions`. Qui la tabella si rilegge
 * per ogni livello e ogni ruolo, scritta da capo con le righe del §2.4 (non con `PermissionSummary`), e il riepilogo deve coincidere riga per riga.
 */
class PermissionSummaryTest {

  private fun roles(mine: Boolean): Set<AlbumRole> = if (mine) setOf(AlbumRole.CREATOR) else setOf(AlbumRole.OTHER)

  /** Le operazioni che la tabella da' a chi ha [roles] a [level] (la condizione "aggiunto quando era permesso" vale per l'elemento di cui si parla). */
  private fun allowed(level: ShareLevel, roles: Set<AlbumRole>): Set<AlbumOp> = AlbumOp.entries.filterTo(LinkedHashSet()) { op ->
    val cell = AlbumPermissions.cell(op, level)
    cell.roles.any { it in roles } && cell.condition != PermissionCondition.CREATOR_LEFT_FAMILY
  }

  private fun info(level: ShareLevel, mine: Boolean) = PermissionInfo(level, AccountId("test"), mine, allowed(level, roles(mine)))

  private fun cellAllows(op: AlbumOp, level: ShareLevel, roles: Set<AlbumRole>): Boolean = AlbumPermissions.cell(op, level).roles.any { it in roles }

  @Test
  fun ogniRigaDelRiepilogoESuaCellaDellaTabellaPerOgniLivelloEOgniRuolo() {
    for (level in ShareLevel.entries) {
      for (mine in listOf(true, false)) {
        val rows = PermissionSummary.of(info(level, mine)).associate { it.ability to it.allowed }
        val base = roles(mine)
        val expected = mapOf(
          Ability.ADD to cellAllows(AlbumOp.ADD_MEDIA, level, base),
          // Un elemento aggiunto da me: io sono anche ADDER (§2.4: «aggiunto da se'»).
          Ability.REMOVE_OWN to cellAllows(AlbumOp.REMOVE_ITEM, level, base + AlbumRole.ADDER),
          Ability.REMOVE_OTHERS to cellAllows(AlbumOp.REMOVE_ITEM, level, base),
          Ability.RENAME to cellAllows(AlbumOp.RENAME, level, base),
          Ability.COVER_AND_SORT to (cellAllows(AlbumOp.SET_COVER, level, base) && cellAllows(AlbumOp.SET_SORT, level, base)),
          Ability.CHANGE_LEVEL to cellAllows(AlbumOp.CHANGE_LEVEL, level, base),
          Ability.CLOSE to (cellAllows(AlbumOp.STOP_SHARING, level, base) && cellAllows(AlbumOp.DELETE_ALBUM, level, base)),
        )
        assertWithMessage("livello ${level.code}, ${if (mine) "creatore" else "altro"}").that(rows).isEqualTo(expected)
      }
    }
  }

  @Test
  fun ilRiepilogoHaUnaRigaPerOgniCosaEMaiDuePerLaStessa() {
    val rows = PermissionSummary.of(info(ShareLevel.ADD, mine = false))
    assertThat(rows.map { it.ability }).containsExactlyElementsIn(Ability.entries).inOrder()
  }

  @Test
  fun alLivelloGliAltriGuardanoSoloIlCreatoreFaQualcosa() {
    val others = PermissionSummary.of(info(ShareLevel.VIEW, mine = false))
    assertThat(others.none { it.allowed }).isTrue()
    val creator = PermissionSummary.of(info(ShareLevel.VIEW, mine = true))
    assertThat(creator.all { it.allowed }).isTrue()
  }

  @Test
  fun alLivelloGliAltriAggiungonoUnoPuoTogliereIlSuoMaNonQuelloDegliAltri() {
    val rows = PermissionSummary.of(info(ShareLevel.ADD, mine = false)).associate { it.ability to it.allowed }
    assertThat(rows.getValue(Ability.ADD)).isTrue()
    assertThat(rows.getValue(Ability.REMOVE_OWN)).isTrue()
    assertThat(rows.getValue(Ability.REMOVE_OTHERS)).isFalse()
    assertThat(rows.getValue(Ability.RENAME)).isFalse()
  }

  @Test
  fun soloIlCreatoreCambiaIlLivelloEIlFoglioLoOffreSoloAlui() {
    for (level in ShareLevel.entries) {
      assertThat(PermissionSummary.canChangeLevel(info(level, mine = true))).isTrue()
      assertThat(PermissionSummary.canChangeLevel(info(level, mine = false))).isFalse()
    }
  }

  @Test
  fun laDichiarazioneDeiPermessiSiMostraSempre() {
    for (level in ShareLevel.entries) for (mine in listOf(true, false)) assertThat(info(level, mine).showDeclaration).isTrue()
  }
}
