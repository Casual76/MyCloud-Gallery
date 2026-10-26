package dev.pampa.mycloudgallery.core.albums.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbumState
import dev.pampa.mycloudgallery.core.personal.settings.PersonalChoice
import dev.pampa.mycloudgallery.core.personal.settings.PersonalItem
import dev.pampa.mycloudgallery.core.personal.settings.PersonalMediaRef
import java.util.UUID
import kotlin.random.Random
import org.junit.Test

/**
 * Dallo stato di un album alle righe (D5, E-I18), senza Room: la regola sta qui. Un elemento "in attesa" non ha riga e si conta; due voci che
 * indicano lo stesso media sono un elemento; vince la piu' recente; le righe sono le stesse in qualunque ordine arrivino le voci.
 */
class AlbumDerivationTest {

  private fun media(n: Int) = MediaId(UUID(0x7000L, n.toLong()).toString())

  private fun fp(n: Int) = PersonalFingerprint(1_000L + n, QuickHash("%064x".format(n + 1)))

  private fun item(subject: MediaId, position: Long?, at: Long, author: String = "A", fp: PersonalFingerprint? = null) =
    PersonalItem(subject.value, position, fp, at, author)

  private fun state(
    items: List<PersonalItem> = emptyList(),
    title: String? = "Gita",
    kind: String? = null,
    cover: PersonalMediaRef? = null,
    frozen: Boolean = false,
    excluded: List<PersonalChoice> = emptyList(),
    accepted: List<PersonalChoice> = emptyList(),
    examples: List<PersonalChoice> = emptyList(),
    smart: Map<String, String> = emptyMap(),
    last: Long = 50_000,
    undated: Boolean = false,
  ) = PersonalAlbumState(
    id = "00000000-0000-4000-8000-0000000000a1", title = title, kind = kind, sort = null, frozen = frozen, order = null, cover = cover, sharedId = null, copyOf = null,
    smart = smart, items = items, excluded = excluded, accepted = accepted, examples = examples, firstAtNas = 1_000, lastAtNas = last,
    hasUndated = undated,
  )

  /** Come si sono risolti i media: id -> media vero (null = non c'e' qui). Chi non e' nella mappa non si risolve. */
  private fun resolution(vararg pairs: Pair<MediaId, MediaId?>): Map<MediaId, MediaId?> = pairs.toMap()

  private fun identity(ids: Iterable<MediaId>): Map<MediaId, MediaId?> = ids.associateWith { it }

  /** Le righe di [state] con questa risoluzione (le voci con impronta cercano lo stesso id: l'impronta non cambia l'esito di qui). */
  private fun derive(state: PersonalAlbumState, resolved: Map<MediaId, MediaId?>): DerivedAlbum? =
    AlbumDerivation.derive(
      state,
      MediaResolution(AlbumDerivation.refsOf(state).associateWith { ref -> runCatching { MediaId.parse(ref.subject) }.getOrNull()?.let { resolved[it] } }),
    )

  @Test
  fun unAlbumManualeDiventaUnaRigaPerElementoConPosizioneEOrigine() {
    val (a, b) = media(1) to media(2)
    val derived = derive(state(listOf(item(a, 2_000, 10_000, fp = fp(1)), item(b, 1_000, 11_000))), identity(listOf(a, b)))!!
    assertThat(derived.album.title).isEqualTo("Gita")
    assertThat(derived.album.kind).isEqualTo("manual")
    assertThat(derived.album.sort).isEqualTo("newest")
    assertThat(derived.album.state).isNull()
    assertThat(derived.album.createdAt).isEqualTo(1_000)
    assertThat(derived.album.updatedAt).isEqualTo(50_000)
    assertThat(derived.items.map { it.mediaId to it.position }).containsExactly(a.value to 2_000L, b.value to 1_000L)
    assertThat(derived.items.map { it.origin }.toSet()).containsExactly("manual")
    assertThat(derived.items.first { it.mediaId == a.value }.fp).isEqualTo("${"%064x".format(2)}:1001")
    assertThat(derived.items.first { it.mediaId == b.value }.fp).isNull()
    assertThat(derived.pending).isEqualTo(0)
  }

  @Test
  fun unElementoNonRisoltoEInAttesaENonHaRiga() {
    val (a, b, gone) = Triple(media(1), media(2), media(3))
    val state = state(listOf(item(a, 1_000, 1), item(b, 2_000, 1), item(gone, null, 1), PersonalItem("non-un-id", 3_000, null, 1, "A")))
    val derived = derive(state, resolution(a to a, b to null, gone to null))!!
    assertThat(derived.items.map { it.mediaId }).containsExactly(a.value)
    // In attesa: solo un elemento vivo con un id vero; una lapide e un testo che non e' un id non contano.
    assertThat(derived.pending).isEqualTo(1)
  }

  @Test
  fun dueIdPerLoStessoMediaSonoUnElementoEVinceLaVocePiuRecente() {
    val (old, survivor) = media(5) to media(2)
    val resolution = resolution(old to survivor, survivor to survivor)
    val newerOld = derive(state(listOf(item(survivor, 1_000, 100), item(old, 7_000, 200))), resolution)!!
    assertThat(newerOld.items.map { it.mediaId to it.position }).containsExactly(survivor.value to 7_000L)
    val newerSurvivor = derive(state(listOf(item(survivor, 1_000, 300), item(old, 7_000, 200))), resolution)!!
    assertThat(newerSurvivor.items.map { it.mediaId to it.position }).containsExactly(survivor.value to 1_000L)
  }

  @Test
  fun aParitaDiOraVinceLaVoceScrittaConLIdCheIlCatalogoHaAdesso() {
    val (old, survivor) = media(5) to media(2)
    val resolution = resolution(old to survivor, survivor to survivor)
    for (order in listOf(listOf(0, 1), listOf(1, 0))) {
      val items = listOf(item(survivor, 1_000, 500, "A"), item(old, 9_000, 500, "A"))
      val derived = derive(state(order.map { items[it] }), resolution)!!
      assertThat(derived.items.single().position).isEqualTo(1_000L)
    }
  }

  @Test
  fun unaRimozionePiuRecenteTogliLElementoComunqueLoSiChiami_UnaPiuVecchiaNo() {
    val (old, survivor) = media(5) to media(2)
    val resolution = resolution(old to survivor, survivor to survivor)
    val removedLater = derive(state(listOf(item(survivor, 1_000, 100), item(old, null, 200))), resolution)!!
    assertThat(removedLater.items).isEmpty()
    val removedBefore = derive(state(listOf(item(survivor, 1_000, 300), item(old, null, 200))), resolution)!!
    assertThat(removedBefore.items.map { it.mediaId }).containsExactly(survivor.value)
    // Riaggiunto dopo la rimozione: torna, anche a pari ora (la voce sull'id attuale batte la lapide sull'altro).
    val readded = derive(state(listOf(item(survivor, 1_000, 200), item(old, null, 200))), resolution)!!
    assertThat(readded.items.map { it.mediaId }).containsExactly(survivor.value)
  }

  @Test
  fun leRigheNonDipendonoDallOrdineDelleVoci() {
    val random = Random(5)
    val ids = (1..6).map { media(it) }
    val survivors = mapOf(ids[0] to ids[0], ids[1] to ids[0], ids[2] to ids[2], ids[3] to ids[2], ids[4] to ids[4], ids[5] to null)
    val resolution = survivors
    repeat(200) {
      val items = ids.flatMap { id ->
        (0 until random.nextInt(1, 4)).map { item(id, if (random.nextInt(3) == 0) null else random.nextLong(1, 9) * 1_000, random.nextLong(1, 5), if (random.nextBoolean()) "A" else "B") }
      }
      val reference = derive(state(items), resolution)!!
      repeat(5) {
        val shuffled = derive(state(items.shuffled(random)), resolution)!!
        assertThat(shuffled.items.sortedBy { it.mediaId }).isEqualTo(reference.items.sortedBy { it.mediaId })
        assertThat(shuffled.pending).isEqualTo(reference.pending)
      }
    }
  }

  @Test
  fun laCopertinaSiRisolveEUnaNonRisoltaVaAVuoto() {
    val a = media(1)
    val chosen = derive(state(cover = PersonalMediaRef(a.value, null)), identity(listOf(a)))!!
    assertThat(chosen.album.coverMediaId).isEqualTo(a.value)
    val missing = derive(state(cover = PersonalMediaRef(media(9).value, null)), identity(listOf(a)))!!
    assertThat(missing.album.coverMediaId).isNull()
  }

  @Test
  fun unAlbumCancellatoMaiDefinitoOdiUnTipoSconosciutoNonHaRighe() {
    val a = media(1)
    assertThat(derive(state(listOf(item(a, 1_000, 1)), title = null), identity(listOf(a)))).isNull()
    assertThat(derive(state(listOf(item(a, 1_000, 1)), kind = "magic"), identity(listOf(a)))).isNull()
    // Un id che non e' un id d'album (una voce ostile): nessuna riga.
    assertThat(derive(state(listOf(item(a, 1_000, 1))).copy(id = "non-un-uuid"), identity(listOf(a)))).isNull()
  }

  @Test
  fun unaModificaDaDatareValeSubitoEPrendeIlTempoDopoLUltimoNoto() {
    val a = media(1)
    val derived = derive(state(listOf(item(a, 1_000, Long.MAX_VALUE)), last = 40_000, undated = true), identity(listOf(a)))!!
    assertThat(derived.items.single().addedAt).isEqualTo(40_001)
    assertThat(derived.album.updatedAt).isEqualTo(40_001)
  }

  // --- Album smart ------------------------------------------------------------------------------------------------------------

  private fun choice(media: MediaId, on: Boolean = true, at: Long = 100) = PersonalChoice(media.value, on, null, at, "A")

  @Test
  fun unoSmartHaLeRegoleLeEsclusioniEGliAccettatiDopoGliElementiAMano() {
    val (a, b, c, d) = listOf(media(1), media(2), media(3), media(4))
    val state = state(
      items = listOf(item(a, 5_000, 10)),
      kind = "smart",
      accepted = listOf(choice(c, at = 300), choice(b, at = 200), choice(a, at = 400)),
      excluded = listOf(choice(d)),
      smart = mapOf("smart:text" to "neve", "smart:period" to "r:2026-03-01..2026-03-07"),
      examples = listOf(choice(b, at = 30), choice(c, at = 20)),
    )
    val derived = derive(state, identity(listOf(a, b, c, d)))!!
    assertThat(derived.album.kind).isEqualTo("smart")
    assertThat(derived.album.sort).isEqualTo("oldest")
    // a e' a mano (vince sull'accettazione); b e c accettati dopo, nell'ordine in cui sono stati accettati.
    assertThat(derived.items.associate { it.mediaId to (it.origin to it.position) }).containsExactly(
      a.value, "manual" to 5_000L, b.value, "accepted" to 6_000L, c.value, "accepted" to 7_000L,
    )
    assertThat(derived.rules.map { it.rule to it.value }).containsExactly("period" to "r:2026-03-01..2026-03-07", "text" to "neve", "example" to "", "example" to "").inOrder()
    assertThat(derived.rules.filter { it.rule == "example" }.map { it.mediaId to it.position }).containsExactly(c.value to 0, b.value to 1).inOrder()
    assertThat(derived.exclusions.map { it.mediaId }).containsExactly(d.value)
    assertThat(derived.hiddenFromAuto).containsExactly(d.value)
    assertThat(derived.dropsAuto).isFalse()
  }

  @Test
  fun unEsclusoNonStaNeglIElementiANonCongelatoMaStaSeCongelato() {
    val (a, b) = media(1) to media(2)
    val base = state(items = listOf(item(a, 1_000, 10), item(b, 2_000, 10)), kind = "smart", excluded = listOf(choice(a)))
    val live = derive(base, identity(listOf(a, b)))!!
    assertThat(live.items.map { it.mediaId }).containsExactly(b.value)
    val frozen = derive(base.copy(frozen = true), identity(listOf(a, b)))!!
    assertThat(frozen.items.map { it.mediaId }).containsExactly(a.value, b.value)
    assertThat(frozen.dropsAuto).isTrue()
    assertThat(frozen.hiddenFromAuto).isEmpty()
    assertThat(frozen.album.frozen).isTrue()
  }

  @Test
  fun lePrimeSeiFotoDEsempioEUnManualeNonHaRegoleNeEsclusioni() {
    val ids = (1..9).map { media(it) }
    val smart = state(kind = "smart", examples = ids.mapIndexed { i, m -> choice(m, at = 100L + i) }, smart = mapOf("smart:text" to "x"))
    val derived = derive(smart, identity(ids))!!
    assertThat(derived.rules.count { it.rule == "example" }).isEqualTo(6)
    val manual = state(items = listOf(item(ids[0], 1_000, 1)), excluded = listOf(choice(ids[0])), smart = mapOf("smart:text" to "x"))
    val plain = derive(manual, identity(ids))!!
    assertThat(plain.rules).isEmpty()
    assertThat(plain.exclusions).isEmpty()
    assertThat(plain.items.map { it.mediaId }).containsExactly(ids[0].value)
  }
}
