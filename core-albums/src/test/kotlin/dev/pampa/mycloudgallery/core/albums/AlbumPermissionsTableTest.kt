package dev.pampa.mycloudgallery.core.albums

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumRole
import dev.pampa.mycloudgallery.core.albums.contract.Enforcement
import dev.pampa.mycloudgallery.core.albums.contract.PermissionCondition
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import org.junit.Test

/**
 * La tabella dei permessi come dati (docs/fase7-progetto.md §2.4, P0): la si confronta, riga per riga, con il §2.4 scritto a mano qui
 * sotto, nei casi che il progetto nomina (creatore, chi ha aggiunto, un altro familiare, un admin). La logica sulle voci lette (livello
 * all'ora `at`, storia, voce precedente) e' di P2.
 */
class AlbumPermissionsTableTest {

  private val alice = AccountId("alice")
  private val bruno = AccountId("bruno")
  private val carla = AccountId("carla")

  /** Chi puo' fare [op] a [level]: "C" il creatore, "A" chi ha aggiunto l'elemento (non creatore), "O" un altro familiare. */
  private fun who(op: AlbumOp, level: ShareLevel): String = buildString {
    // L'elemento l'ha aggiunto bruno; il creatore e' alice.
    if (AlbumPermissions.admits(op, level, AlbumPermissions.rolesOf(alice, alice, null, bruno, false))) append('C')
    if (AlbumPermissions.admits(op, level, AlbumPermissions.rolesOf(bruno, alice, null, bruno, false))) append('A')
    if (AlbumPermissions.admits(op, level, AlbumPermissions.rolesOf(carla, alice, null, bruno, false))) append('O')
  }

  @Test
  fun laTabellaEQuellaDelProgetto() {
    // Operazione -> (Tutti possono tutto, Gli altri aggiungono, Gli altri guardano), come nel §2.4.
    val expected = mapOf(
      AlbumOp.ADD_MEDIA to Triple("CAO", "CAO", "C"),
      AlbumOp.REMOVE_ITEM to Triple("CAO", "CA", "C"),
      AlbumOp.DELETE_FILE to Triple("CAO", "CA", "C"),
      AlbumOp.NEW_VERSION to Triple("CAO", "CA", "C"),
      AlbumOp.RENAME to Triple("CAO", "C", "C"),
      AlbumOp.SET_COVER to Triple("CAO", "C", "C"),
      AlbumOp.SET_SORT to Triple("CAO", "C", "C"),
      AlbumOp.BRING_BACK to Triple("A", "A", "A"),
      AlbumOp.ADOPT_FOLDER_FILES to Triple("CAO", "CAO", "C"),
      AlbumOp.CHANGE_LEVEL to Triple("C", "C", "C"),
      AlbumOp.STOP_SHARING to Triple("C", "C", "C"),
      AlbumOp.DELETE_ALBUM to Triple("C", "C", "C"),
      AlbumOp.LEAVE to Triple("AO", "AO", "AO"),
      AlbumOp.VIEW to Triple("CAO", "CAO", "CAO"),
      AlbumOp.DOWNLOAD to Triple("CAO", "CAO", "CAO"),
      AlbumOp.SHARE_OUT to Triple("CAO", "CAO", "CAO"),
      AlbumOp.FAVORITE_OR_HIDE to Triple("CAO", "CAO", "CAO"),
      AlbumOp.ADD_TO_PERSONAL_ALBUM to Triple("CAO", "CAO", "CAO"),
      AlbumOp.INHERIT to Triple("", "", ""),
    )
    assertThat(expected.keys).containsExactlyElementsIn(AlbumOp.entries)
    for ((op, cells) in expected) {
      assertWithMessage("${op.code} a Tutti").that(who(op, ShareLevel.ALL)).isEqualTo(cells.first)
      assertWithMessage("${op.code} a Aggiungono").that(who(op, ShareLevel.ADD)).isEqualTo(cells.second)
      assertWithMessage("${op.code} a Guardano").that(who(op, ShareLevel.VIEW)).isEqualTo(cells.third)
    }
  }

  /** Il creatore che ha aggiunto lui stesso l'elemento lo puo' riportare; non puo' riportare quello di un altro. */
  @Test
  fun riportaSoloIlProprioMediaAncheIlCreatore() {
    assertThat(AlbumPermissions.admits(AlbumOp.BRING_BACK, ShareLevel.VIEW, AlbumPermissions.rolesOf(alice, alice, null, alice, false))).isTrue()
    assertThat(AlbumPermissions.admits(AlbumOp.BRING_BACK, ShareLevel.ALL, AlbumPermissions.rolesOf(alice, alice, null, bruno, false))).isFalse()
    assertThat(AlbumPermissions.cell(AlbumOp.BRING_BACK, ShareLevel.VIEW).condition).isEqualTo(PermissionCondition.ADDED_WHILE_PERMITTED)
  }

  /** D11: l'erede ha i diritti del creatore; l'eredita' e' dell'admin, con la condizione del registro. */
  @Test
  fun lEredeEIlCreatoreELEreditaEDellAdmin() {
    val heirRoles = AlbumPermissions.rolesOf(carla, alice, carla, null, false)
    assertThat(heirRoles).contains(AlbumRole.CREATOR)
    assertThat(AlbumPermissions.admits(AlbumOp.CHANGE_LEVEL, ShareLevel.VIEW, heirRoles)).isTrue()
    assertThat(AlbumPermissions.admits(AlbumOp.INHERIT, ShareLevel.ADD, AlbumPermissions.rolesOf(carla, alice, null, null, true))).isTrue()
    assertThat(AlbumPermissions.cell(AlbumOp.INHERIT, ShareLevel.ADD).condition).isEqualTo(PermissionCondition.CREATOR_LEFT_FAMILY)
  }

  /** Chi controlla: eliminare il file e la versione nuova solo chi scrive (L57); le azioni personali nessuno; il resto entrambi. */
  @Test
  fun chiControllaOgniRiga() {
    val writerOnly = AlbumPermissions.rows.filter { it.enforcement == Enforcement.WRITER_ONLY }.map { it.op }
    assertThat(writerOnly).containsExactly(AlbumOp.DELETE_FILE, AlbumOp.NEW_VERSION)
    val personal = AlbumPermissions.rows.filter { it.enforcement == Enforcement.PERSONAL }.map { it.op }
    assertThat(personal).containsExactly(AlbumOp.VIEW, AlbumOp.DOWNLOAD, AlbumOp.SHARE_OUT, AlbumOp.FAVORITE_OR_HIDE, AlbumOp.ADD_TO_PERSONAL_ALBUM, AlbumOp.LEAVE)
    for (row in AlbumPermissions.rows.filter { it.enforcement == Enforcement.WRITER_AND_READER }) {
      assertWithMessage(row.op.code).that(row.readerField).isNotNull()
    }
  }
}
