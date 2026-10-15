package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalMerge
import dev.pampa.mycloudgallery.core.personal.contract.PersonalRules
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValidity
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalVerdict
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import kotlin.random.Random
import org.junit.Test

/**
 * La fusione (D26) alla prova: l'ordine fra due voci della stessa chiave e' totale (ora del NAS, poi autore minore, poi valore, poi
 * impronta), quindi qualunque ordine di arrivo e qualunque raggruppamento danno lo stesso stato; e un orologio sfasato cambia chi
 * vince ma mai la convergenza.
 */
class PersonalMergeTest {

  private fun device(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  private val d1 = device(1)
  private val d2 = device(2)
  private val d3 = device(3)
  private val key = flagKey(mediaId(1), PersonalFields.FAVORITE)

  private fun fp(size: Long, c: Char) = PersonalFingerprint(size, QuickHash(c.toString().repeat(64)))

  @Test
  fun aParitaDiOraEDiAutoreDecideIlValoreEPoiLImpronta() {
    val off = PersonalEntry(key, 10, d1, PersonalValue.Flag(false))
    val on = PersonalEntry(key, 10, d1, PersonalValue.Flag(true))
    // Nel dubbio vince "acceso": un nascosto non si perde per un pareggio (E-H1).
    assertThat(PersonalMerge.winner(off, on)).isEqualTo(on)
    assertThat(PersonalMerge.winner(on, off)).isEqualTo(on)

    val small = PersonalEntry(key, 10, d1, PersonalValue.Flag(true), fp(1, 'a'))
    val big = PersonalEntry(key, 10, d1, PersonalValue.Flag(true), fp(2, 'a'))
    val none = PersonalEntry(key, 10, d1, PersonalValue.Flag(true), null)
    assertThat(PersonalMerge.winner(small, big)).isEqualTo(big)
    assertThat(PersonalMerge.winner(big, small)).isEqualTo(big)
    // Senza impronta perde: non e' commutativa se non si dice come, e qui si dice.
    assertThat(PersonalMerge.winner(none, small)).isEqualTo(small)
    assertThat(PersonalMerge.winner(small, none)).isEqualTo(small)
    val sameSizeOtherHash = PersonalEntry(key, 10, d1, PersonalValue.Flag(true), fp(1, 'b'))
    assertThat(PersonalMerge.winner(small, sameSizeOtherHash)).isEqualTo(sameSizeOtherHash)
    assertThat(PersonalMerge.winner(sameSizeOtherHash, small)).isEqualTo(sameSizeOtherHash)
  }

  @Test
  fun iNumeriNegativiEITestiHannoUnOrdineTotale() {
    val values = listOf(
      PersonalValue.Number(-10),
      PersonalValue.Number(-5),
      PersonalValue.Number(0),
      PersonalValue.Number(5),
      PersonalValue.Flag(false),
      PersonalValue.Flag(true),
      PersonalValue.Removed,
      PersonalValue.Text(""),
      PersonalValue.Text("a"),
      PersonalValue.Text("b"),
    )
    for (a in values) {
      for (b in values) {
        val ab = PersonalMerge.compareValues(a, b)
        val ba = PersonalMerge.compareValues(b, a)
        assertThat(Integer.signum(ab)).isEqualTo(-Integer.signum(ba))
        assertThat(ab == 0).isEqualTo(a == b)
      }
    }
    assertThat(PersonalMerge.compareValues(PersonalValue.Number(-10), PersonalValue.Number(-5))).isLessThan(0)
    assertThat(PersonalMerge.compareValues(PersonalValue.Number(-5), PersonalValue.Number(0))).isLessThan(0)
  }

  @Test
  fun qualunqueOrdineEQualunqueRaggruppamentoDannoLoStessoStato() {
    val random = Random(26)
    val keys = (1..6).map { flagKey(mediaId(it), if (it % 2 == 0) PersonalFields.FAVORITE else PersonalFields.HIDDEN) } +
      PersonalKey(PersonalKind.ALBUM, "album-1", PersonalFields.ALBUM_DEFINITION)
    val authors = listOf(d1, d2, d3)
    repeat(300) {
      // Pochi valori possibili di proposito: tanti pareggi di ora, di autore e di valore.
      val entries = List(random.nextInt(2, 40)) {
        val k = keys.random(random)
        val value = when (random.nextInt(5)) {
          0 -> PersonalValue.Flag(random.nextBoolean())
          1 -> PersonalValue.Number(random.nextLong(-3, 3))
          2 -> PersonalValue.Text(listOf("x", "y", "z").random(random))
          3 -> PersonalValue.Removed
          else -> PersonalValue.Flag(true)
        }
        val fingerprint = if (random.nextInt(3) == 0) fp(random.nextLong(0, 3), listOf('a', 'b').random(random)) else null
        PersonalEntry(k, random.nextLong(0, 4), authors.random(random), value, fingerprint)
      }
      val reference = PersonalMerge.merge(emptyMap(), entries)
      // Ordine qualunque.
      assertThat(PersonalMerge.merge(emptyMap(), entries.shuffled(random))).isEqualTo(reference)
      // Idempotenza: rifondere le stesse voci non cambia niente.
      assertThat(PersonalMerge.merge(reference, entries)).isEqualTo(reference)
      // Associativita': (A ∪ B) ∪ C == A ∪ (B ∪ C), con i gruppi presi a caso.
      val cut1 = random.nextInt(entries.size + 1)
      val cut2 = random.nextInt(cut1, entries.size + 1)
      val a = entries.subList(0, cut1)
      val b = entries.subList(cut1, cut2)
      val c = entries.subList(cut2, entries.size)
      val left = PersonalMerge.merge(PersonalMerge.merge(PersonalMerge.merge(emptyMap(), a), b), c)
      val right = PersonalMerge.merge(PersonalMerge.merge(emptyMap(), a), PersonalMerge.merge(PersonalMerge.merge(emptyMap(), b), c).values)
      assertThat(left).isEqualTo(reference)
      assertThat(right).isEqualTo(reference)
      // Commutativita' fra due stati (due dispositivi che si scambiano lo stato unito).
      val x = PersonalMerge.merge(emptyMap(), a + b)
      val y = PersonalMerge.merge(emptyMap(), c)
      assertThat(PersonalMerge.merge(x, y.values)).isEqualTo(PersonalMerge.merge(y, x.values))
    }
  }

  @Test
  fun conGliOrologiSfasatiVinceChiHaLOraPiuAltaMaTuttiConvergono() {
    // A e' avanti di 58 s (come il NAS e' avanti al telefono), B e' indietro: B modifica DOPO A ma la sua ora e' piu' bassa.
    val realAtA = 1_000_000L
    val a = PersonalEntry(key, realAtA + 58_000, d2, PersonalValue.Flag(false))
    val b = PersonalEntry(key, realAtA + 30_000 - 58_000, d1, PersonalValue.Flag(true))
    val forward = PersonalMerge.merge(emptyMap(), listOf(a, b))
    val backward = PersonalMerge.merge(emptyMap(), listOf(b, a))
    assertThat(forward).isEqualTo(backward)
    // Vince l'ora piu' alta (A), anche se B e' piu' recente nella realta': e' il limite dichiarato dell'ora del NAS per dispositivo.
    assertThat(forward.getValue(key)).isEqualTo(a)
    // Stessa ora esatta e autori diversi: l'autore minore, in qualunque ordine.
    val tieLow = PersonalEntry(key, 77, d1, PersonalValue.Flag(false))
    val tieHigh = PersonalEntry(key, 77, d3, PersonalValue.Flag(true))
    assertThat(PersonalMerge.merge(emptyMap(), listOf(tieLow, tieHigh)).getValue(key)).isEqualTo(tieLow)
    assertThat(PersonalMerge.merge(emptyMap(), listOf(tieHigh, tieLow)).getValue(key)).isEqualTo(tieLow)
  }

  @Test
  fun lapideEValoreSiFondonoComeOgniAltraVoce() {
    val item = PersonalKey(PersonalKind.ALBUM, "album", PersonalFields.albumItem(mediaId(3)))
    val added = PersonalEntry(item, 10, d1, PersonalValue.Number(1))
    val removed = PersonalEntry(item, 11, d2, PersonalValue.Removed)
    val readded = PersonalEntry(item, 12, d1, PersonalValue.Number(2))
    assertThat(PersonalMerge.merge(emptyMap(), listOf(readded, added, removed)).getValue(item)).isEqualTo(readded)
    assertThat(PersonalMerge.merge(emptyMap(), listOf(removed, added)).getValue(item).value).isEqualTo(PersonalValue.Removed)
  }

  // --- Validita' -----------------------------------------------------------------------------------------------------------------

  private val now = NAS_NOW
  private val rules = PersonalRules(now, approvedAuthors = setOf(d1, d2), revokedAuthors = setOf(d3))

  @Test
  fun ilVerdettoSegueUnOrdineFissoDiMotivi() {
    val ok = entry(key, now - 5_000, d1)
    assertThat(PersonalValidity.check(ok, rules, inDeclaredPrivateArea = true)).isEqualTo(PersonalVerdict.VALID)

    // L'area comune vince su tutto: anche una voce perfetta di un dispositivo perfetto non vale li' (C19).
    assertThat(PersonalValidity.check(ok, rules, inDeclaredPrivateArea = false)).isEqualTo(PersonalVerdict.NOT_IN_PRIVATE_AREA)
    // Un tipo riservato si rifiuta anche se l'autore e' ammesso (E-F5).
    val reserved = PersonalEntry(PersonalKey(PersonalKind.PERSON, "p", "name"), now - 1, d1, PersonalValue.Text("Mario"))
    assertThat(PersonalValidity.check(reserved, rules, true)).isEqualTo(PersonalVerdict.RESERVED_KIND)
    // Revocato e nel futuro: il motivo e' la revoca (e' il piu' grave).
    val revokedFuture = entry(key, now + 3_600_000, d3)
    assertThat(PersonalValidity.check(revokedFuture, rules, true)).isEqualTo(PersonalVerdict.AUTHOR_REVOKED)
    // Sconosciuto e nel futuro: l'autore non ammesso.
    val strangerFuture = entry(key, now + 3_600_000, device(9))
    assertThat(PersonalValidity.check(strangerFuture, rules, true)).isEqualTo(PersonalVerdict.AUTHOR_NOT_ALLOWED)
    // Il bordo esatto dei 2 minuti vale, un millisecondo oltre no.
    assertThat(PersonalValidity.check(entry(key, now + TrustLimits.MAX_FUTURE_SKEW_MILLIS, d1), rules, true)).isEqualTo(PersonalVerdict.VALID)
    assertThat(PersonalValidity.check(entry(key, now + TrustLimits.MAX_FUTURE_SKEW_MILLIS + 1, d1), rules, true)).isEqualTo(PersonalVerdict.FROM_FUTURE)
  }

  @Test
  fun unaVoceVecchiaDiUnOrologioIndietroNonEMaiFuturo() {
    // Il NAS e' avanti al telefono: una voce dell'altro ieri, o dell'anno scorso, vale.
    assertThat(PersonalValidity.check(entry(key, 1_600_000_000_000L, d1), rules, true)).isEqualTo(PersonalVerdict.VALID)
    assertThat(PersonalValidity.check(entry(key, 0, d1), rules, true)).isEqualTo(PersonalVerdict.VALID)
  }
}
