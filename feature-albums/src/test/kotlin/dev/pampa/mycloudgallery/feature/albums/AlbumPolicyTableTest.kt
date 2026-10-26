package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.Album
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.AlbumRole
import dev.pampa.mycloudgallery.core.albums.contract.CoverRef
import dev.pampa.mycloudgallery.core.albums.contract.PermissionCondition
import dev.pampa.mycloudgallery.core.albums.contract.SharedState
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumAction
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPolicy
import dev.pampa.mycloudgallery.feature.albums.contract.ItemActions
import org.junit.Test

/**
 * "Azioni mostrate secondo `AlbumPermissions` per ogni livello: un test generato dalla tabella" (riga P10 del §4.4, C25). La tabella dei permessi del
 * §2.4 (`AlbumPermissions.rows`) e' l'unica fonte: per ogni **livello** e ogni **ruolo** si derivano da lei le operazioni permesse (la stessa regola
 * di `SharedAlbums.allowed`: i ruoli di chi agisce, la cella, la condizione) e si controlla che le schermate offrano **esattamente** le azioni di
 * quelle operazioni. Nessun elenco scritto a mano di "cosa vede chi": se la tabella cambia, il test cambia con lei.
 */
class AlbumPolicyTableTest {

  /** Chi agisce e cosa sa il registro firmato di lui (l'admin con il creatore uscito dalla famiglia puo' ereditare). */
  private data class Actor(val name: String, val roles: Set<AlbumRole>, val creatorLeft: Boolean = false)

  private val actors = listOf(
    Actor("creatore", setOf(AlbumRole.CREATOR)),
    Actor("altro", setOf(AlbumRole.OTHER)),
    Actor("altro admin, creatore ancora in famiglia", setOf(AlbumRole.OTHER, AlbumRole.ADMIN)),
    Actor("altro admin, creatore uscito", setOf(AlbumRole.OTHER, AlbumRole.ADMIN), creatorLeft = true),
  )

  /** Le operazioni che la tabella da' a chi ha [roles] a [level] (condizioni comprese): quello che `SharedAlbums.allowedOps` risponde. */
  private fun opsOf(level: ShareLevel, roles: Set<AlbumRole>, creatorLeft: Boolean = false, addedWhilePermitted: Boolean = true): Set<AlbumOp> =
    AlbumOp.entries.filterTo(LinkedHashSet()) { op ->
      AlbumPermissions.admits(op, level, roles) && when (AlbumPermissions.cell(op, level).condition) {
        PermissionCondition.NONE -> true
        PermissionCondition.ADDED_WHILE_PERMITTED -> addedWhilePermitted
        PermissionCondition.CREATOR_LEFT_FAMILY -> creatorLeft
      }
    }

  private fun shared(level: ShareLevel, mine: Boolean, state: SharedState = SharedState.ACTIVE, sort: AlbumSort = AlbumSort.NEWEST_FIRST) = Album.Shared(
    AlbumId("00000000-0000-4000-8000-000000000001"), "Prova", AlbumKind.MANUAL, CoverRef.None, 3, sort, 0, null, level, AccountId("test"), null, mine, state,
  )

  /** Le azioni della pagina che dipendono da un'operazione della tabella: tutte tranne `CHANGE_LEVEL`, che sta nel foglio "Permessi" e non nel menu. */
  private val tableBound = AlbumAction.entries.filter { it.op != null && it != AlbumAction.CHANGE_LEVEL }

  @Test
  fun laPaginaOffreEsattamenteLeAzioniCheLaTabellaDaAChiAgisce() {
    for (level in ShareLevel.entries) {
      for (actor in actors) {
        val ops = opsOf(level, actor.roles, actor.creatorLeft)
        val offered = AlbumPolicy.pageActions(shared(level, mine = AlbumRole.CREATOR in actor.roles), ops).toSet()
        val expected = tableBound.filterTo(LinkedHashSet()) { it.op in ops } + AlbumAction.SHOW_PERMISSIONS
        assertWithMessage("livello ${level.code}, ${actor.name}").that(offered).isEqualTo(expected)
      }
    }
  }

  @Test
  fun leAzioniDelCreatoreSonoDelSoloCreatoreEQuelleDegliAltriNonLeSostituiscono() {
    for (level in ShareLevel.entries) {
      val creator = AlbumPolicy.pageActions(shared(level, true), opsOf(level, setOf(AlbumRole.CREATOR))).toSet()
      val other = AlbumPolicy.pageActions(shared(level, false), opsOf(level, setOf(AlbumRole.OTHER))).toSet()
      // Solo il creatore cambia i permessi, chiude, elimina; solo gli altri "non mostrano piu'" (E-SH21).
      assertThat(creator).containsAtLeast(AlbumAction.STOP_SHARING, AlbumAction.DELETE_ALBUM)
      assertThat(creator).doesNotContain(AlbumAction.LEAVE)
      assertThat(other).containsNoneOf(AlbumAction.STOP_SHARING, AlbumAction.DELETE_ALBUM, AlbumAction.INHERIT)
      assertThat(other).contains(AlbumAction.LEAVE)
      // Il foglio dei permessi (e la dichiarazione) c'e' sempre, a ogni livello e per ogni ruolo.
      assertThat(creator).contains(AlbumAction.SHOW_PERMISSIONS)
      assertThat(other).contains(AlbumAction.SHOW_PERMISSIONS)
    }
  }

  @Test
  fun aGliAltriGuardanoGliAltriNonAggiungonoNeCambianoLAlbum() {
    val ops = opsOf(ShareLevel.VIEW, setOf(AlbumRole.OTHER))
    val offered = AlbumPolicy.pageActions(shared(ShareLevel.VIEW, false), ops)
    assertThat(offered).containsNoneOf(AlbumAction.ADD_MEDIA, AlbumAction.ADOPT_FOLDER_FILES, AlbumAction.RENAME, AlbumAction.SET_COVER, AlbumAction.SET_SORT)
  }

  @Test
  fun aGliAltriAggiungonoSiAggiungeMaNonSiRinomina() {
    val ops = opsOf(ShareLevel.ADD, setOf(AlbumRole.OTHER))
    val offered = AlbumPolicy.pageActions(shared(ShareLevel.ADD, false), ops)
    assertThat(offered).contains(AlbumAction.ADD_MEDIA)
    assertThat(offered).containsNoneOf(AlbumAction.RENAME, AlbumAction.SET_COVER, AlbumAction.SET_SORT)
  }

  @Test
  fun aTuttiPossonoTuttoOgniAltroFaLeStesseCoseDelCreatoreTranneChiusuraELivello() {
    val ops = opsOf(ShareLevel.ALL, setOf(AlbumRole.OTHER))
    val offered = AlbumPolicy.pageActions(shared(ShareLevel.ALL, false), ops)
    assertThat(offered).containsAtLeast(AlbumAction.ADD_MEDIA, AlbumAction.RENAME, AlbumAction.SET_COVER, AlbumAction.SET_SORT)
    assertThat(offered).containsNoneOf(AlbumAction.STOP_SHARING, AlbumAction.DELETE_ALBUM)
  }

  @Test
  fun unAlbumCondivisoChiusoNonOffreNiente() {
    for (state in listOf(SharedState.CLOSED, SharedState.DELETED)) {
      val ops = opsOf(ShareLevel.ALL, setOf(AlbumRole.CREATOR))
      assertThat(AlbumPolicy.pageActions(shared(ShareLevel.ALL, true, state), ops)).isEmpty()
      assertThat(AlbumPolicy.itemActions(shared(ShareLevel.ALL, true, state), ops, ops)).isEqualTo(ItemActions.NONE)
    }
  }

  @Test
  fun leAzioniSuUnElementoSegnoLaTabellaPerChiLHaAggiunto() {
    for (level in ShareLevel.entries) {
      for (actor in actors) {
        for (own in listOf(true, false)) {
          for (whilePermitted in listOf(true, false)) {
            val album = shared(level, mine = AlbumRole.CREATOR in actor.roles, sort = AlbumSort.CUSTOM)
            val albumOps = opsOf(level, actor.roles, actor.creatorLeft)
            val roles = if (own) actor.roles + AlbumRole.ADDER else actor.roles
            val itemOps = opsOf(level, roles, actor.creatorLeft, whilePermitted)
            val offered = AlbumPolicy.itemActions(album, albumOps, itemOps)
            val label = "livello ${level.code}, ${actor.name}, ${if (own) "suo" else "di un altro"}, ${if (whilePermitted) "aggiunto quando si poteva" else "dopo"}"
            assertWithMessage("togliere: $label").that(offered.removeFromAlbum).isEqualTo(AlbumOp.REMOVE_ITEM in itemOps)
            assertWithMessage("riportare: $label").that(offered.bringBack).isEqualTo(AlbumOp.BRING_BACK in itemOps)
            assertWithMessage("copertina: $label").that(offered.setAsCover).isEqualTo(AlbumOp.SET_COVER in albumOps)
            assertWithMessage("ordine: $label").that(offered.reorder).isEqualTo(AlbumOp.SET_SORT in albumOps)
          }
        }
      }
    }
  }

  @Test
  fun inUnAlbumSoloDaGuardareIlProprietarioPuoRiportareMaNonTogliereLeFotoDegliAltri() {
    // La tabella: "Togliere un elemento aggiunto da altri" e' del creatore a ogni livello diverso da "Tutti possono tutto"; "Riporta" del proprietario.
    val album = shared(ShareLevel.VIEW, mine = false)
    val own = AlbumPolicy.itemActions(album, opsOf(ShareLevel.VIEW, setOf(AlbumRole.OTHER)), opsOf(ShareLevel.VIEW, setOf(AlbumRole.OTHER, AlbumRole.ADDER)))
    assertThat(own.bringBack).isTrue()
    assertThat(own.removeFromAlbum).isFalse()
    val others = AlbumPolicy.itemActions(album, opsOf(ShareLevel.VIEW, setOf(AlbumRole.OTHER)), opsOf(ShareLevel.VIEW, setOf(AlbumRole.OTHER)))
    assertThat(others.any).isFalse()
  }

  @Test
  fun unAlbumPersonaleOffreSempreLeSueAzioniEMaiLeCondivise() {
    val manual = Album.Manual(AlbumId("00000000-0000-4000-8000-000000000002"), "Mio", CoverRef.None, 0, AlbumSort.NEWEST_FIRST, 0, null)
    val smart = Album.Smart(
      AlbumId("00000000-0000-4000-8000-000000000003"), "Oslo", CoverRef.None, 0, AlbumSort.OLDEST_FIRST, 0, null, SmartRules(null, null, "x"), frozen = false, suggested = 0, newSinceSeen = 0,
    )
    val personal = setOf(AlbumAction.ADD_MEDIA, AlbumAction.RENAME, AlbumAction.SET_COVER, AlbumAction.SET_SORT, AlbumAction.SHARE, AlbumAction.DELETE_PERSONAL)
    assertThat(AlbumPolicy.pageActions(manual, emptySet()).toSet()).isEqualTo(personal)
    assertThat(AlbumPolicy.pageActions(smart, emptySet()).toSet()).isEqualTo(personal + AlbumAction.EDIT_RULES + AlbumAction.FREEZE)
    assertThat(AlbumPolicy.pageActions(smart.copy(frozen = true), emptySet())).containsAtLeast(AlbumAction.EDIT_RULES, AlbumAction.UNFREEZE)
    assertThat(AlbumPolicy.pageActions(smart.copy(frozen = true), emptySet())).doesNotContain(AlbumAction.FREEZE)
    // Un album personale non riporta (non c'e' un'area comune) e ordina a mano solo se e' ordinato a mano.
    assertThat(AlbumPolicy.itemActions(manual, emptySet(), emptySet()).bringBack).isFalse()
    assertThat(AlbumPolicy.itemActions(manual, emptySet(), emptySet()).reorder).isFalse()
    assertThat(AlbumPolicy.itemActions(manual.copy(sort = AlbumSort.CUSTOM), emptySet(), emptySet()).reorder).isTrue()
  }

  @Test
  fun ogniOperazioneDellaTabellaHaUnaCasaNelleSchermate() {
    // Se la tabella ha una riga nuova, qualcuno deve decidere dove si vede: la pagina, il menu di un elemento, il foglio dei permessi, il viewer.
    val inThePage = AlbumAction.entries.mapNotNull { it.op }.toSet()
    val elsewhere = setOf(
      AlbumOp.VIEW, AlbumOp.DOWNLOAD, AlbumOp.SHARE_OUT, AlbumOp.FAVORITE_OR_HIDE, AlbumOp.ADD_TO_PERSONAL_ALBUM, // azioni del viewer e della selezione, sempre permesse
      AlbumOp.REMOVE_ITEM, AlbumOp.BRING_BACK, // il menu di un elemento
      AlbumOp.DELETE_FILE, AlbumOp.NEW_VERSION, // "Elimina" e le correzioni sono del viewer e di feature-backup
    )
    assertThat(AlbumOp.entries.toSet() - inThePage - elsewhere).isEmpty()
    assertThat(AlbumPermissions.rows.map { it.op }.toSet()).isEqualTo(AlbumOp.entries.toSet())
    assertThat(MediaId("x".let { "00000000-0000-4000-8000-000000000009" }).value).isNotEmpty()
  }
}
