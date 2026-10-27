package dev.pampa.mycloudgallery.core.albums.shared

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AlbumOp
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPermissions
import dev.pampa.mycloudgallery.core.albums.contract.Enforcement
import dev.pampa.mycloudgallery.core.albums.shared.Paper.T0
import dev.pampa.mycloudgallery.core.albums.shared.Paper.alice
import dev.pampa.mycloudgallery.core.albums.shared.Paper.bruno
import dev.pampa.mycloudgallery.core.albums.shared.Paper.carla
import dev.pampa.mycloudgallery.core.albums.shared.Paper.dora
import dev.pampa.mycloudgallery.core.albums.shared.Paper.entry
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.core.trust.data.AlbumDefinitionState
import dev.pampa.mycloudgallery.core.trust.data.AlbumEntry
import dev.pampa.mycloudgallery.core.trust.data.AlbumField
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import dev.pampa.mycloudgallery.core.trust.data.LevelChange
import org.junit.Test

/**
 * La logica dei permessi (P2, "Fatto quando": tabella dei permessi generata in test, per ogni riga e livello, lato scrittore e lato lettore,
 * con lo stesso esito; storia dei livelli e voci retrodatate al §2.4; E-SH11, E-SH12, E-SH13; Q5). Funzioni pure: la fusione di contributi
 * di carta e la regola di chi scrive sullo stesso stato fuso.
 */
class AlbumPermissionsLogicTest {

  private val item1 = AlbumField.Item(Paper.media(1))

  /** Un album di alice: aperto a "Tutti" quando bruno aggiunge l'elemento 1, poi al livello [level] da T0+5. */
  private fun base(level: ShareLevel): List<AlbumEntry> = listOf(
    entry(AlbumField.Definition, Paper.definition(), alice, T0),
    entry(AlbumField.Level, Paper.levels(T0 to ShareLevel.ALL, T0 + 5 to level), alice, T0 + 5),
    entry(item1, Paper.item(1, bruno), bruno, T0 + 1),
  )

  /** La voce che l'operazione [op] di [actor] produce all'ora [at] su [merged]. */
  private fun produce(op: AlbumOp, actor: AccountId, at: Long, merged: MergedAlbum, device: Int): AlbumEntry? = when (op) {
    AlbumOp.ADD_MEDIA, AlbumOp.ADOPT_FOLDER_FILES -> entry(AlbumField.Item(Paper.media(9)), Paper.item(9, actor), actor, at, device)
    AlbumOp.REMOVE_ITEM, AlbumOp.BRING_BACK -> entry(item1, null, actor, at, device)
    AlbumOp.RENAME -> entry(AlbumField.Definition, Paper.definition(title = "Altro"), actor, at, device)
    AlbumOp.SET_COVER -> entry(AlbumField.Cover, AlbumValue.Cover(Paper.media(1), Paper.key(1)), actor, at, device)
    AlbumOp.SET_SORT -> entry(AlbumField.Sort, AlbumValue.Sort(AlbumSort.CUSTOM), actor, at, device)
    AlbumOp.CHANGE_LEVEL -> entry(AlbumField.Level, AlbumValue.Levels(merged.levels.changes + LevelChange(at, ShareLevel.ALL)), actor, at, device)
    AlbumOp.STOP_SHARING -> entry(AlbumField.Definition, Paper.definition(AlbumDefinitionState.CLOSED), actor, at, device)
    AlbumOp.DELETE_ALBUM -> entry(AlbumField.Definition, Paper.definition(AlbumDefinitionState.DELETED, afterClose = "keep_copy"), actor, at, device)
    AlbumOp.INHERIT -> entry(AlbumField.Heir, AlbumValue.Heir(actor, 10), actor, at, device)
    else -> null
  }

  /** Chi legge accetta [written]? E' la vincitrice della sua chiave (per l'eredita': un erede in piu' nella linea del tempo). */
  private fun accepted(written: AlbumEntry, before: List<AlbumEntry>, registry: Paper.Registry): Boolean {
    val merged = Paper.merge(before + written, registry) ?: return false
    return if (written.field == AlbumField.Heir) merged.heirs.any { it.account == written.account && it.since == written.at } else merged.winners[written.key] == written
  }

  @Test
  fun laTabellaGenerata_chiScriveEChiLeggeHannoLoStessoEsitoPerOgniRigaELivello() {
    var checked = 0
    for (row in AlbumPermissions.rows) {
      for (level in ShareLevel.entries) {
        for (creatorGone in listOf(false, true)) {
          // Gli attori: il creatore, chi ha aggiunto l'elemento 1, un altro familiare, un admin (dispositivo admin nel registro).
          for (actor in listOf(alice, bruno, carla, dora)) {
            val registry = Paper.Registry().apply { if (creatorGone) gone += alice }
            val before = base(level)
            val merged = Paper.merge(before, registry)!!
            val at = T0 + 100
            val item = Paper.media(1).takeIf { row.op in ITEM_OPS }
            val isAdmin = actor == dora
            val writer = SharedAlbumRules.allows(merged, actor, isAdmin, row.op, item, at, creatorGone)
            val label = "${row.op.code} a ${level.code} da ${actor.value}${if (creatorGone) " (creatore uscito)" else ""}"
            // Chi scrive applica la tabella com'e' (le condizioni comprese).
            val roles = AlbumPermissions.rolesOf(actor, alice, null, if (item != null) bruno else null, isAdmin)
            val cell = AlbumPermissions.cell(row.op, level)
            assertWithMessage("$label: chi scrive e' la tabella").that(writer).isEqualTo(
              AlbumPermissions.admits(row.op, level, roles) &&
                (cell.condition != dev.pampa.mycloudgallery.core.albums.contract.PermissionCondition.CREATOR_LEFT_FAMILY || creatorGone),
            )
            if (row.enforcement != Enforcement.WRITER_AND_READER) continue
            val written = produce(row.op, actor, at, merged, device = 2) ?: continue
            val reader = accepted(written, before, registry)
            // La stessa lapide la producono "Rimuovi" e "Riporta": chi legge la accetta se una delle due e' offerta.
            val expected = if (written.field is AlbumField.Item && written.value == null) {
              SharedAlbumRules.allows(merged, actor, isAdmin, AlbumOp.REMOVE_ITEM, item, at, creatorGone) ||
                SharedAlbumRules.allows(merged, actor, isAdmin, AlbumOp.BRING_BACK, item, at, creatorGone)
            } else {
              writer
            }
            assertWithMessage("$label: chi legge come chi scrive").that(reader).isEqualTo(expected)
            checked++
          }
        }
      }
    }
    assertThat(checked).isEqualTo(AlbumPermissions.rows.count { it.enforcement == Enforcement.WRITER_AND_READER } * 3 * 2 * 4)
  }

  /** §2.4, E-SH13: ogni voce si giudica con il livello in vigore alla sua ora; prima del primo cambio vale "Gli altri guardano". */
  @Test
  fun laStoriaDeiLivelliGiudicaOgniVoceAllaSuaOra() {
    val entries = listOf(
      entry(AlbumField.Definition, Paper.definition(), alice, T0),
      entry(AlbumField.Level, Paper.levels(T0 to ShareLevel.ADD, T0 + 10 to ShareLevel.VIEW), alice, T0 + 10),
      entry(AlbumField.Item(Paper.media(1)), Paper.item(1, bruno), bruno, T0 + 5),
      entry(AlbumField.Item(Paper.media(2)), Paper.item(2, bruno), bruno, T0 + 15),
      entry(AlbumField.Item(Paper.media(3)), Paper.item(3, carla), carla, T0 - 1),
      entry(AlbumField.Level, Paper.levels(T0 to ShareLevel.ALL), bruno, T0 + 3, device = 2),
    )
    val merged = Paper.merge(entries)!!
    assertThat(merged.items.keys).containsExactly(Paper.media(1))
    assertThat(merged.levels.at(T0 + 12)).isEqualTo(ShareLevel.VIEW)
    val denied = merged.denied.associate { it.entry.field to it.reason }
    assertThat(denied[AlbumField.Item(Paper.media(2))]).isEqualTo(DenyReason.NOT_PERMITTED)
    assertThat(denied[AlbumField.Item(Paper.media(3))]).isEqualTo(DenyReason.NOT_PERMITTED)
    assertThat(denied[AlbumField.Level]).isEqualTo(DenyReason.NOT_PERMITTED)
    // A "Gli altri guardano" bruno toglie il suo elemento solo come "Riporta" (aggiunto quando il livello lo permetteva).
    val back = entry(AlbumField.Item(Paper.media(1)), null, bruno, T0 + 20, device = 2)
    assertThat(Paper.merge(entries + back)!!.items).isEmpty()
    // Carla non lo toglie; il creatore si'.
    assertThat(Paper.merge(entries + entry(AlbumField.Item(Paper.media(1)), null, carla, T0 + 20))!!.items).hasSize(1)
    assertThat(Paper.merge(entries + entry(AlbumField.Item(Paper.media(1)), null, alice, T0 + 20, device = 2))!!.items).isEmpty()
    // Il creatore che riscrive la storia (L59: solo nei suoi poteri) cambia l'esito delle voci di allora.
    val rewritten = entries.map { if (it.field == AlbumField.Level && it.account == alice) it.copy(value = Paper.levels(T0 to ShareLevel.VIEW), at = T0 + 30) else it }
    assertThat(Paper.merge(rewritten)!!.items).isEmpty()
  }

  /** E-SH12: lo stesso media aggiunto da due familiari insieme: una voce sola vince, sempre la stessa; nessuno si appropria dell'altra. */
  @Test
  fun eSh12UnaVoceSolaVinceENessunoSiAppropriaDiQuellaDiUnAltro() {
    for (level in listOf(ShareLevel.ALL, ShareLevel.ADD)) {
      val base = listOf(entry(AlbumField.Definition, Paper.definition(), alice, T0), entry(AlbumField.Level, Paper.levels(T0 to level), alice, T0))
      val byBruno = entry(item1, Paper.item(1, bruno), bruno, T0 + 7)
      val byCarla = entry(item1, Paper.item(1, carla), carla, T0 + 7)
      val one = Paper.merge(base + byBruno + byCarla)!!
      val other = Paper.merge(base + byCarla + byBruno)!!
      assertThat(one).isEqualTo(other)
      assertThat(one.items).hasSize(1)
      assertThat(one.denied.map { it.reason }).containsExactly(DenyReason.OWNERSHIP)
      val owner = one.items.values.single().addedBy
      val stranger = if (owner == bruno) carla else bruno
      // Riaggiungere l'elemento di un altro (per poi toglierlo da "proprietario") non passa, a nessun livello.
      val takeover = entry(item1, Paper.item(1, stranger), stranger, T0 + 9, device = 2)
      assertThat(Paper.merge(base + byBruno + byCarla + takeover)!!.items.values.single().addedBy).isEqualTo(owner)
      // Togliere l'elemento di un altro: a "Tutti" si', a "Gli altri aggiungono" no.
      val removal = entry(item1, null, stranger, T0 + 9, device = 2)
      assertThat(Paper.merge(base + byBruno + byCarla + removal)!!.items.isEmpty()).isEqualTo(level == ShareLevel.ALL)
    }
  }

  /** D11, E-SH11: l'eredita' e' di un dispositivo admin, per il suo account, solo con il creatore uscito; vale dall'ora della sua voce. */
  @Test
  fun eSh11LEreditaSoloDellAdminConIlCreatoreUscito() {
    val base = listOf(
      entry(AlbumField.Definition, Paper.definition(), alice, T0),
      entry(AlbumField.Level, Paper.levels(T0 to ShareLevel.VIEW), alice, T0),
      entry(AlbumField.Item(Paper.media(1)), Paper.item(1, alice), alice, T0 + 1),
    )
    val heir = entry(AlbumField.Heir, AlbumValue.Heir(dora, 10), dora, T0 + 50)
    val renameBefore = entry(AlbumField.Definition, Paper.definition(title = "Prima"), dora, T0 + 40, device = 2)
    val renameAfter = entry(AlbumField.Definition, Paper.definition(title = "Dopo"), dora, T0 + 60, device = 3)
    val inFamily = Paper.Registry()
    assertThat(Paper.merge(base + heir, inFamily)!!.heirs).isEmpty()
    val left = Paper.Registry().apply { gone += alice }
    val merged = Paper.merge(base + heir + renameBefore + renameAfter, left)!!
    assertThat(merged.heirAt(T0 + 49)).isNull()
    assertThat(merged.heirAt(T0 + 50)).isEqualTo(dora)
    assertThat(merged.title).isEqualTo("Dopo")
    assertThat(merged.denied.map { it.entry }).contains(renameBefore)
    // Non admin, a nome di un altro, o con un registro che chi legge non conosce: niente.
    assertThat(Paper.merge(base + entry(AlbumField.Heir, AlbumValue.Heir(carla, 10), carla, T0 + 50), left)!!.heirs).isEmpty()
    assertThat(Paper.merge(base + entry(AlbumField.Heir, AlbumValue.Heir(carla, 10), dora, T0 + 50), left)!!.denied.single().reason).isEqualTo(DenyReason.OWNERSHIP)
    assertThat(Paper.merge(base + entry(AlbumField.Heir, AlbumValue.Heir(dora, 11), dora, T0 + 50), left)!!.denied.single().reason).isEqualTo(DenyReason.REGISTRY_AHEAD)
    // Due admin: una linea del tempo (l'eredita' dopo non cancella quella di prima).
    val erik = AccountId("erik")
    val second = Paper.Registry(admins = mutableSetOf(Paper.device(dora), Paper.device(erik))).apply { gone += alice }
    val both = Paper.merge(base + heir + entry(AlbumField.Heir, AlbumValue.Heir(erik, 10), erik, T0 + 70), second)!!
    assertThat(both.heirAt(T0 + 60)).isEqualTo(dora)
    assertThat(both.heirAt(T0 + 80)).isEqualTo(erik)
    // Il creatore ricostituito dall'erede (A7: i contributi di alice non valgono piu'): stessa identita', la storia di prima, alice resta creatore.
    val rebuilt = listOf(
      heir,
      entry(AlbumField.Definition, Paper.definition(), dora, T0 + 50, device = 1),
      entry(AlbumField.Level, Paper.levels(T0 to ShareLevel.VIEW), dora, T0 + 50, device = 1),
      entry(AlbumField.Item(Paper.media(2)), Paper.item(2, bruno), bruno, T0 + 2),
    )
    assertThat(Paper.merge(rebuilt, inFamily)).isNull()
    val alive = Paper.merge(rebuilt, left)!!
    assertThat(alive.creator).isEqualTo(alice)
    assertThat(alive.heir).isEqualTo(dora)
    assertThat(alive.items).isEmpty() // bruno aggiunse a "Gli altri guardano": scartato anche prima
  }

  /** D31 e Q5: chiuso o eliminato e' definitivo; `afterClose` firmato con la definizione; uno sconosciuto vale `remove`. */
  @Test
  fun chiusoEDefinitivoEAfterCloseViaggiaFirmato() {
    val base = listOf(
      entry(AlbumField.Definition, Paper.definition(), alice, T0),
      entry(AlbumField.Level, Paper.levels(T0 to ShareLevel.ALL), alice, T0, device = 2),
      entry(AlbumField.Item(Paper.media(1)), Paper.item(1, bruno), bruno, T0 + 1),
    )
    val close = entry(AlbumField.Definition, Paper.definition(AlbumDefinitionState.CLOSED, afterClose = "keep_copy"), alice, T0 + 10)
    val after = listOf(
      entry(AlbumField.Item(Paper.media(2)), Paper.item(2, bruno), bruno, T0 + 12),
      entry(AlbumField.Definition, Paper.definition(), alice, T0 + 20, device = 3),
      entry(AlbumField.Level, Paper.levels(T0 to ShareLevel.ALL, T0 + 25 to ShareLevel.VIEW), alice, T0 + 25, device = 4),
    )
    // La creazione e' stata sostituita dalla chiusura nello stesso contributo: l'album c'e', chiuso.
    val merged = Paper.merge(base.filter { it.field != AlbumField.Definition } + close + after)!!
    assertThat(merged.state).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.SharedState.CLOSED)
    assertThat(merged.afterClose).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose.KEEP_COPY)
    assertThat(merged.items.keys).containsExactly(Paper.media(1))
    assertThat(merged.denied.map { it.reason }.toSet()).containsExactly(DenyReason.CLOSED)
    assertThat(merged.levels.changes).hasSize(1)
    // Chi non e' il creatore non chiude; un codice sconosciuto vale `remove`.
    assertThat(Paper.merge(base + entry(AlbumField.Definition, Paper.definition(AlbumDefinitionState.DELETED), bruno, T0 + 10, device = 2))!!.state)
      .isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.SharedState.ACTIVE)
    val unknown = Paper.merge(base.filter { it.field != AlbumField.Definition } + entry(AlbumField.Definition, Paper.definition(AlbumDefinitionState.DELETED, afterClose = "zzz"), alice, T0 + 10))!!
    assertThat(unknown.afterClose).isEqualTo(dev.pampa.mycloudgallery.core.albums.contract.OthersAfterClose.REMOVE)
    assertThat(unknown.afterCloseCode).isEqualTo("zzz")
  }

  /** La definizione di un altro account (un "furto" dell'album, E-SH14) non crea niente; quella con un id che non torna non entra. */
  @Test
  fun unaDefinizioneDiUnAltroNonCreaLAlbum() {
    assertThat(Paper.merge(listOf(entry(AlbumField.Definition, Paper.definition(), bruno, T0)))).isNull()
    val forged = AlbumValue.Definition("Mio", Paper.definition().folder, AlbumDefinitionState.ACTIVE, bruno, Paper.nonceHex, null)
    assertThat(Paper.merge(listOf(entry(AlbumField.Definition, forged, bruno, T0)))).isNull()
    // Con la creazione di alice, la "rinomina" di bruno a "Gli altri aggiungono" e' scartata.
    val merged = Paper.merge(
      listOf(
        entry(AlbumField.Definition, Paper.definition(), alice, T0),
        entry(AlbumField.Level, Paper.levels(T0 to ShareLevel.ADD), alice, T0),
        entry(AlbumField.Definition, Paper.definition(title = "Rubato"), bruno, T0 + 1),
      ),
    )!!
    assertThat(merged.title).isEqualTo("Gita")
  }

  /**
   * R14 (revisione di sicurezza della Fase 7): chi legge non accetta la riscrittura dell'elemento di un altro con un contenuto diverso, nemmeno
   * dal creatore a "Tutti possono tutto" (prima passava come "togliere l'elemento di un altro": tutti vedevano un'altra foto "aggiunta da" bruno).
   * Il posto nell'ordine, invece, cambia con lo stesso permesso di prima; il proprio elemento si riscrive come un'aggiunta.
   */
  @Test
  fun r14LElementoDiUnAltroNonCambiaContenuto() {
    val base = base(ShareLevel.ALL)
    val original = Paper.merge(base)!!.items.getValue(Paper.media(1))
    for (field in listOf("chiave", "percorso", "versione", "hash")) {
      val forged = when (field) {
        "chiave" -> Paper.item(1, bruno).copy(contentKey = Paper.key(7))
        "percorso" -> Paper.item(1, bruno).copy(path = dev.pampa.mycloudgallery.core.trust.nas.SharePath.parseMediaPath("Album condivisi/Gita/altra.jpg"))
        "versione" -> Paper.item(1, bruno).copy(version = dev.pampa.mycloudgallery.core.model.VersionId(Paper.media(7).value))
        else -> Paper.item(1, bruno).copy(sha256 = dev.pampa.mycloudgallery.core.model.Sha256("ab".repeat(32)))
      }
      val rewrite = entry(item1, forged, alice, T0 + 20, device = 2)
      val merged = Paper.merge(base + rewrite)!!
      assertWithMessage(field).that(merged.items.getValue(Paper.media(1))).isEqualTo(original)
      assertWithMessage(field).that(merged.denied.single { it.entry == rewrite }.reason).isEqualTo(DenyReason.OWNERSHIP)
    }
    // Solo il posto: passa (a "Tutti" il creatore puo' togliere l'elemento di bruno, quindi anche spostarlo).
    val moved = entry(item1, Paper.item(1, bruno, position = 99_000L), alice, T0 + 20, device = 2)
    assertThat(Paper.merge(base + moved)!!.items.getValue(Paper.media(1)).position).isEqualTo(99_000L)
    // Il proprio elemento si riscrive (come un'aggiunta): bruno cambia il suo.
    val own = entry(item1, Paper.item(1, bruno).copy(contentKey = Paper.key(8)), bruno, T0 + 20, device = 2)
    assertThat(Paper.merge(base + own)!!.items.getValue(Paper.media(1)).contentKey).isEqualTo(Paper.key(8))
  }

  private companion object {
    val ITEM_OPS = setOf(AlbumOp.REMOVE_ITEM, AlbumOp.BRING_BACK, AlbumOp.DELETE_FILE, AlbumOp.NEW_VERSION)
  }
}
