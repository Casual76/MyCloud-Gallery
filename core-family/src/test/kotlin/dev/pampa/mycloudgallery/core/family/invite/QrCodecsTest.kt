package dev.pampa.mycloudgallery.core.family.invite

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.family.contract.DeviceKeyQr
import dev.pampa.mycloudgallery.core.family.contract.FamilyInvite
import dev.pampa.mycloudgallery.core.family.contract.FamilyStateQr
import dev.pampa.mycloudgallery.core.family.contract.QrPayloads
import dev.pampa.mycloudgallery.core.family.support.NAS_NOON
import dev.pampa.mycloudgallery.core.family.support.TEST_GUID
import dev.pampa.mycloudgallery.core.family.support.TEST_ULA
import dev.pampa.mycloudgallery.core.family.support.TestFamily
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasFileSystem
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.codec.CanonicalJson
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.FamilyKey
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import java.util.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * I tre QR dell'app (`MCG1:`, `MCGK1:`, `MCGS1:`) con il testo scansionato trattato da ostile: prefisso
 * sbagliato, troppo lungo, campi sconosciuti, base32 non valida, JSON non canonico, valori pericolosi. Nessun
 * input fa lanciare il codec: o un invito valido, o un rifiuto con il suo motivo.
 */
class QrCodecsTest {

  private val family = TestFamily(InMemoryNasFileSystem("NAS"))
  private val invite: FamilyInvite = family.invite()

  private fun rejection(text: String) = InviteCodec.decode(text).rejectionOrNull()

  // --- Invito --------------------------------------------------------------------------------------

  @Test
  fun unInvitoFaAndataERitornoEStaNeiSeicentoCaratteri() {
    val text = InviteCodec.encode(invite)
    assertThat(InviteCodec.decode(text).acceptedOrNull()).isEqualTo(invite)
    assertThat(text).startsWith(QrPayloads.INVITE_PREFIX)
    assertThat(text.length).isAtMost(QrPayloads.MAX_CHARS)
    // Tutto maiuscolo e cifre: la modalita' alfanumerica del QR, che lo rende leggibile a schermo.
    assertThat(text.removePrefix(QrPayloads.INVITE_PREFIX)).matches("[0-9A-Z]+")
  }

  @Test
  fun lePasswordNonEsistonoNelFormato() {
    val body = InviteCodec.encode(invite).removePrefix(QrPayloads.INVITE_PREFIX)
    val json = String(Base32.decode(body.lowercase()))
    val keys = (CanonicalJson.parse(json.toByteArray()) as dev.pampa.mycloudgallery.core.trust.codec.JsonObject).members.keys
    assertThat(keys).containsExactly("fam", "fp", "min", "ip", "nm", "gd", "ula", "cs", "ps", "usr", "dev", "pre", "exp")
    assertThat(json.lowercase()).doesNotContain("pass")
  }

  @Test
  fun lAndataERitornoVaAnchePerUnIPv6EUnInvitoSenzaNomeNePreset() {
    val v6 = invite.copy(nasIp = "fd12:3456:789a::58", nasName = null, presetName = null, homeUla = listOf(TEST_ULA, "fdd1:2345:6789::/48"))
    assertThat(InviteCodec.decode(InviteCodec.encode(v6)).acceptedOrNull()).isEqualTo(v6)
  }

  @Test
  fun unInvitoOltreSeicentoCaratteriNonSiScriveNeSiLegge() {
    val huge = invite.copy(
      nasName = "n".repeat(64),
      nasUser = "u".repeat(64),
      deviceName = "d".repeat(64),
      commonShare = "c".repeat(80),
      privateShare = "p".repeat(80),
      nasIp = "fd12:3456:789a:0:0:0:0:58",
      homeUla = listOf(TEST_ULA, "fdd1:2345:6789::/48", "fdd2:2345:6789::/48", "fdd3:2345:6789::/48"),
    )
    assertThrows(IllegalArgumentException::class.java) { InviteCodec.encode(huge) }
    val longText = QrPayloads.INVITE_PREFIX + "A".repeat(QrPayloads.MAX_CHARS)
    assertThat(rejection(longText)).isEqualTo(RejectReason.TOO_LARGE)
  }

  @Test
  fun unPrefissoSbagliatoOMancanteESempreRifiutato() {
    val text = InviteCodec.encode(invite)
    val body = text.removePrefix(QrPayloads.INVITE_PREFIX)
    assertThat(rejection("")).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection(body)).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection("mcg1:$body")).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection("MCG2:$body")).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection(" MCG1:$body")).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection("MCG1:")).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection("MCG1 $body")).isEqualTo(RejectReason.MALFORMED)
    // Il QR di WireGuard (una password) e il QR di un altro tipo non sono inviti.
    assertThat(rejection("[Interface]\nPrivateKey = abc")).isEqualTo(RejectReason.MALFORMED)
    val keyQr = DeviceKeyCodec.encode(DeviceKeyQr(family.familyId, TrustIds.deviceIdOf(family.adminDevice.publicKey), family.adminDevice.publicKey.encoded))
    assertThat(rejection(keyQr)).isEqualTo(RejectReason.WRONG_TYPE)
  }

  @Test
  fun laBase32NonValidaESempreRifiutata() {
    val body = InviteCodec.encode(invite).removePrefix(QrPayloads.INVITE_PREFIX)
    assertThat(rejection("MCG1:${body.dropLast(1)}")).isNotNull() // lunghezza impossibile
    assertThat(rejection("MCG1:$body!")).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection("MCG1:${body.take(10)}-${body.drop(11)}")).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection("MCG1:${"I".repeat(16)}")).isEqualTo(RejectReason.INVALID_FIELD) // fuori dall'alfabeto di Crockford
    assertThat(rejection("MCG1:${body.take(5)}U${body.drop(6)}")).isEqualTo(RejectReason.INVALID_FIELD)
    // Bit di coda non nulli: l'ultimo carattere cambiato in un altro che lascia la stessa lunghezza.
    val last = body.last()
    val other = if (last == 'A') 'B' else 'A'
    assertThat(rejection("MCG1:${body.dropLast(1)}$other")).isNotNull()
    // Le minuscole sono lo stesso testo digitato a mano: valide.
    assertThat(InviteCodec.decode("MCG1:${body.lowercase()}").acceptedOrNull()).isEqualTo(invite)
  }

  private fun wrap(vararg extra: Pair<String, dev.pampa.mycloudgallery.core.trust.codec.JsonValue?>, drop: Set<String> = emptySet()): String {
    val base = mapOf(
      "fam" to invite.familyId.json(),
      "fp" to invite.familyKeyFingerprint.json(),
      "min" to invite.minRegistryVersion.json(),
      "ip" to invite.nasIp.json(),
      "gd" to invite.nasGuid.json(),
      "ula" to invite.homeUla.map { it.json() }.json(),
      "cs" to invite.commonShare.json(),
      "ps" to invite.privateShare.json(),
      "usr" to invite.nasUser.json(),
      "dev" to invite.deviceName.json(),
      "exp" to invite.expiresAtNas.json(),
    )
    val merged = (base - drop).toMutableMap<String, dev.pampa.mycloudgallery.core.trust.codec.JsonValue?>()
    extra.forEach { merged[it.first] = it.second }
    return QrText.wrap(QrPayloads.INVITE_PREFIX, jsonObject(*merged.map { it.key to it.value }.toTypedArray()))
  }

  @Test
  fun unCampoSconosciutoOMancanteERifiutato() {
    assertThat(InviteCodec.decode(wrap()).acceptedOrNull()).isNotNull() // la base costruita a mano e' valida
    assertThat(rejection(wrap("zzz" to "x".json()))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection(wrap(drop = setOf("gd")))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection(wrap(drop = setOf("exp")))).isEqualTo(RejectReason.MALFORMED)
    assertThat(rejection(wrap("min" to "7".json()))).isEqualTo(RejectReason.MALFORMED) // tipo sbagliato
    assertThat(rejection(wrap("ula" to "x".json()))).isEqualTo(RejectReason.MALFORMED)
  }

  @Test
  fun unJsonNonCanonicoERifiutato() {
    val spaced = """{"fam": "${invite.familyId}"}""".toByteArray()
    val text = QrPayloads.INVITE_PREFIX + Base32.encode(spaced).uppercase()
    assertThat(rejection(text)).isNotNull()
    val notJson = QrPayloads.INVITE_PREFIX + Base32.encode("ciao".toByteArray()).uppercase()
    assertThat(rejection(notJson)).isEqualTo(RejectReason.MALFORMED)
    val deep = QrPayloads.INVITE_PREFIX + Base32.encode("[[[[[[[[[[1]]]]]]]]]]".toByteArray()).uppercase()
    assertThat(rejection(deep)).isNotNull()
  }

  @Test
  fun iValoriPericolosiSonoRifiutati() {
    val none = emptyList<dev.pampa.mycloudgallery.core.trust.codec.JsonValue>()
    val hostile = listOf(
      Triple("id di famiglia corto", "fam", "abc".json()),
      Triple("impronta maiuscola", "fp", invite.familyKeyFingerprint.uppercase().json()),
      Triple("impronta corta", "fp", invite.familyKeyFingerprint.take(10).json()),
      Triple("versione negativa", "min", (-1L).json()),
      Triple("versione fuori scala", "min", 2_000_000_000L.json()),
      Triple("indirizzo che e' un nome", "ip", "mycloud.fritz.box".json()),
      Triple("indirizzo con porta", "ip", "192.168.178.58:445".json()),
      Triple("indirizzo con zero iniziale", "ip", "192.168.178.058".json()),
      Triple("guid con spazi", "gd", "ab cd".json()),
      Triple("guid vuoto", "gd", "".json()),
      Triple("nessun ULA", "ula", none.json()),
      Triple("troppi ULA", "ula", List(5) { "fd0$it:2345:6789::/48".json() }.json()),
      Triple("prefisso che non e' ULA", "ula", listOf("2001:db8::/48".json()).json()),
      Triple("prefisso /64", "ula", listOf("fd12:3456:789a::/64".json()).json()),
      Triple("prefisso fc00::/8 (non assegnato)", "ula", listOf("fc00:3456:789a::/48".json()).json()),
      Triple("ULA non nella forma del formato", "ula", listOf("FD12:3456:789A:0:0:0:0:0/48".json()).json()),
      Triple("ULA ripetuto", "ula", listOf(TEST_ULA.json(), TEST_ULA.json()).json()),
      Triple("share col punto punto", "cs", "..".json()),
      Triple("share riservata", "ps", "CON".json()),
      Triple("share con barra", "cs", "a/b".json()),
      Triple("utente admin", "usr", "admin".json()),
      Triple("utente ADMIN", "usr", "ADMIN".json()),
      Triple("utente non ascii", "usr", "mammà".json()),
      Triple("nome con controllo", "dev", "Tablet".json()),
      Triple("nome vuoto", "dev", "".json()),
      Triple("scadenza nel 1970", "exp", 1L.json()),
      Triple("scadenza nel 3000", "exp", 32_503_680_000_000L.json()),
      Triple("preset minuscolo", "pre", "balanced".json()),
    )
    hostile.forEach { (label, key, value) ->
      assertWithMessage(label).that(rejection(wrap(key to value))).isNotNull()
    }
  }

  @Test
  fun unLetturaCasualeNonLanciaMai() {
    val random = Random(42)
    val alphabet = "MCG1:KS0123456789ABCDEFGHJKMNPQRSTVWXYZabcdefghjkmnpqrstvwxyz-_ \n[]"
    repeat(2000) {
      val text = (QrPayloads.INVITE_PREFIX.takeIf { random.nextBoolean() } ?: "") +
        String(CharArray(random.nextInt(700)) { alphabet[random.nextInt(alphabet.length)] })
      InviteCodec.decode(text)
      DeviceKeyCodec.decode(text)
      FamilyStateCodec.decode(text)
    }
  }

  @Test
  fun ognunoDeiCaratteriCambiatiDaUnRifiutoOUnAltroInvitoMaNonLanciaMai() {
    val text = InviteCodec.encode(invite)
    val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    for (index in QrPayloads.INVITE_PREFIX.length until text.length) {
      for (c in listOf(alphabet[(alphabet.indexOf(text[index]) + 1) % alphabet.length], 'I', '!')) {
        val mutated = text.substring(0, index) + c + text.substring(index + 1)
        when (val verdict = InviteCodec.decode(mutated)) {
          is Verdict.Accepted -> assertThat(InviteCodec.encode(verdict.value)).isEqualTo(mutated)
          is Verdict.Rejected -> Unit
        }
      }
    }
  }

  // --- Scadenza e scanner ---------------------------------------------------------------------------

  @Test
  fun laScadenzaSiGiudicaConLOraDelNas() {
    val expires = invite.expiresAtNas
    assertThat(InviteExpiry.status(invite, expires - 1)).isEqualTo(InviteExpiry.Status.VALID)
    assertThat(InviteExpiry.status(invite, expires)).isEqualTo(InviteExpiry.Status.VALID)
    assertThat(InviteExpiry.status(invite, expires + 1)).isEqualTo(InviteExpiry.Status.EXPIRED)
    // Una scadenza piu' lontana di 24 ore (piu' lo scarto d'orologio) non l'ha scritta la nostra app.
    val far = invite.copy(expiresAtNas = NAS_NOON + InviteExpiry.MAX_VALIDITY_MILLIS + InviteExpiry.SKEW_MILLIS + 1)
    assertThat(InviteExpiry.status(far, NAS_NOON)).isEqualTo(InviteExpiry.Status.TOO_FAR)
    val limit = invite.copy(expiresAtNas = NAS_NOON + InviteExpiry.MAX_VALIDITY_MILLIS + InviteExpiry.SKEW_MILLIS)
    assertThat(InviteExpiry.status(limit, NAS_NOON)).isEqualTo(InviteExpiry.Status.VALID)
  }

  @Test
  fun loScannerDiceSubitoPerchePiaceONo() {
    val text = InviteCodec.encode(invite)
    assertThat(InviteScanCheck.check(text, NAS_NOON)).isEqualTo(InviteScan.Valid(invite))
    // Scaduto, con la tolleranza di un orologio sfasato.
    assertThat(InviteScanCheck.check(text, invite.expiresAtNas + InviteExpiry.SCAN_TOLERANCE_MILLIS)).isInstanceOf(InviteScan.Valid::class.java)
    assertThat(InviteScanCheck.check(text, invite.expiresAtNas + InviteExpiry.SCAN_TOLERANCE_MILLIS + 1))
      .isEqualTo(InviteScan.Rejected(InviteScanProblem.EXPIRED))
    assertThat(InviteScanCheck.check(text, NAS_NOON, expectedFamilyId = TrustIds.random()))
      .isEqualTo(InviteScan.Rejected(InviteScanProblem.OTHER_FAMILY))
    assertThat(InviteScanCheck.check(text, NAS_NOON, expectedFamilyId = invite.familyId)).isInstanceOf(InviteScan.Valid::class.java)
    assertThat(InviteScanCheck.check("[Interface]\nPrivateKey = x", NAS_NOON)).isEqualTo(InviteScan.Rejected(InviteScanProblem.WIREGUARD_SECRET))
    assertThat(InviteScanCheck.check("https://example.org", NAS_NOON)).isEqualTo(InviteScan.Rejected(InviteScanProblem.NOT_OURS))
    assertThat(InviteScanCheck.check("MCG1:ZZZZ", NAS_NOON)).isEqualTo(InviteScan.Rejected(InviteScanProblem.MALFORMED))
    val keyQr = DeviceKeyCodec.encode(DeviceKeyQr(family.familyId, TrustIds.deviceIdOf(family.adminDevice.publicKey), family.adminDevice.publicKey.encoded))
    assertThat(InviteScanCheck.check(keyQr, NAS_NOON)).isEqualTo(InviteScan.Rejected(InviteScanProblem.NOT_OURS))
    assertThat(QrClassifier.kindOf(keyQr)).isEqualTo(QrKind.DEVICE_KEY)
  }

  // --- Chiave del dispositivo ------------------------------------------------------------------------

  @Test
  fun laChiaveDelDispositivoFaAndataERitornoELaStessaChiaveSiRiconosce() {
    val key = FamilyKey.generate().publicKey
    val qr = DeviceKeyQr(family.familyId, TrustIds.deviceIdOf(key), key.encoded)
    val text = DeviceKeyCodec.encode(qr)
    assertThat(text).startsWith(QrPayloads.DEVICE_KEY_PREFIX)
    assertThat(text.length).isAtMost(QrPayloads.MAX_CHARS)
    val decoded = DeviceKeyCodec.decode(text).acceptedOrNull()!!
    assertThat(decoded).isEqualTo(qr)
    assertThat(decoded.toPublicKey()).isEqualTo(key)
  }

  @Test
  fun laChiaveDelDispositivoRifiutaIdFalsiEChiaviFuoriCurva() {
    val key = FamilyKey.generate().publicKey
    // L'id e' auto-certificante: un id preso da un'altra chiave non passa.
    val wrongId = DeviceKeyQr(family.familyId, TrustIds.deviceIdOf(FamilyKey.generate().publicKey), key.encoded)
    assertThrows(IllegalArgumentException::class.java) { DeviceKeyCodec.encode(wrongId) }
    // Un punto fuori dalla curva: un byte cambiato nella coordinata y.
    val bad = key.encoded.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
    val forged = QrText.wrap(
      QrPayloads.DEVICE_KEY_PREFIX,
      jsonObject(
        "fam" to family.familyId.json(),
        "dev" to TrustIds.deviceIdOf(key).json(),
        "key" to Base32.encode(bad).json(),
      ),
    )
    assertThat(DeviceKeyCodec.decode(forged).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    // Un id che non corrisponde, scritto a mano nel QR.
    val mismatched = QrText.wrap(
      QrPayloads.DEVICE_KEY_PREFIX,
      jsonObject(
        "fam" to family.familyId.json(),
        "dev" to TrustIds.random().json(),
        "key" to key.toText().json(),
      ),
    )
    assertThat(DeviceKeyCodec.decode(mismatched).rejectionOrNull()).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(DeviceKeyCodec.decode(InviteCodec.encode(invite)).rejectionOrNull()).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(DeviceKeyCodec.decode(QrPayloads.DEVICE_KEY_PREFIX + "A".repeat(700)).rejectionOrNull()).isEqualTo(RejectReason.TOO_LARGE)
  }

  // --- Stato della famiglia -------------------------------------------------------------------------------

  @Test
  fun loStatoDellaFamigliaFaAndataERitornoEDiventaUnAncora() {
    val qr = FamilyStateQrs.of(family.state)!!
    val text = FamilyStateCodec.encode(qr)
    assertThat(text).startsWith(QrPayloads.FAMILY_STATE_PREFIX)
    assertThat(FamilyStateCodec.decode(text).acceptedOrNull()).isEqualTo(qr)
    val anchor = FamilyStateQrs.anchorOf(qr)!!
    assertThat(anchor.familyId).isEqualTo(family.familyId)
    assertThat(anchor.familyKeyFingerprint).isEqualTo(family.fingerprint)
    assertThat(anchor.minRegistryVersion).isEqualTo(family.state.registry.version)
    assertThat(qr.registryHash).isEqualTo(family.state.contentHash)
  }

  @Test
  fun loStatoDellaFamigliaRifiutaValoriFalsi() {
    val good = FamilyStateQrs.of(family.state)!!
    fun rejected(qr: FamilyStateQr) = assertThrows(IllegalArgumentException::class.java) { FamilyStateCodec.encode(qr) }
    rejected(good.copy(registryVersion = 0))
    rejected(good.copy(registryHash = "abc"))
    rejected(good.copy(familyKeyFingerprint = good.familyKeyFingerprint.uppercase()))
    rejected(good.copy(familyId = "x"))
    assertThat(FamilyStateCodec.decode(InviteCodec.encode(invite)).rejectionOrNull()).isEqualTo(RejectReason.WRONG_TYPE)
    assertThat(FamilyStateCodec.decode("MCGS1:").rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
    val extra = QrText.wrap(
      QrPayloads.FAMILY_STATE_PREFIX,
      jsonObject(
        "fam" to good.familyId.json(),
        "fp" to good.familyKeyFingerprint.json(),
        "ver" to good.registryVersion.json(),
        "hash" to good.registryHash.json(),
        "extra" to 1L.json(),
      ),
    )
    assertThat(FamilyStateCodec.decode(extra).rejectionOrNull()).isEqualTo(RejectReason.MALFORMED)
  }

  // --- Ingresso senza QR -------------------------------------------------------------------------------------

  @Test
  fun unInvitoManualeNormalizzaCioCheSiDigitaERifiutaCioCheNonVa() {
    val typedId = family.familyId.uppercase().chunked(5).joinToString(" ")
    val typedFingerprint = family.fingerprint.uppercase().chunked(4).joinToString("-")
    val manual = ManualInvite.create(typedId, typedFingerprint, " 192.168.178.58 ", "mamma", "NAS", "mamma", "Tablet").acceptedOrNull()!!
    assertThat(manual.isManual).isTrue()
    assertThat(manual.familyId).isEqualTo(family.familyId)
    assertThat(manual.familyKeyFingerprint).isEqualTo(family.fingerprint)
    assertThat(manual.minRegistryVersion).isEqualTo(0L)
    assertThat(manual.homeUla).isEmpty()
    assertThat(manual.nasGuid).isEmpty()
    assertThat(invite.isManual).isFalse()

    fun bad(id: String = family.familyId, fp: String = family.fingerprint, ip: String = "192.168.178.58", user: String = "mamma", common: String = "NAS") =
      ManualInvite.create(id, fp, ip, user, common, "mamma", "Tablet").rejectionOrNull()
    assertThat(bad(id = "troppo-corto")).isNotNull()
    assertThat(bad(fp = "abc")).isNotNull()
    assertThat(bad(ip = "nas.fritz.box")).isNotNull()
    assertThat(bad(user = "admin")).isNotNull()
    assertThat(bad(user = "mam ma")).isNotNull()
    assertThat(bad(common = "..")).isNotNull()
    // Un invito manuale non e' un QR: non si codifica.
    assertThrows(IllegalArgumentException::class.java) { InviteCodec.encode(manual) }
  }

  @Test
  fun iPrefissiUlaSiConfrontanoPerValoreENonPerTesto() {
    assertThat(HomeUla.canonicalOf("fd12:3456:789a::/48")).isEqualTo("fd12:3456:789a::/48")
    assertThat(HomeUla.canonicalOf("FD12:3456:789A:0:0:0:0:0/48")).isEqualTo("fd12:3456:789a::/48")
    assertThat(HomeUla.canonicalOf("fd12:3456:789a:0::/48")).isEqualTo("fd12:3456:789a::/48")
    assertThat(HomeUla.canonicalOf("fd12:3456:789a::/48")).isNotEqualTo(HomeUla.canonicalOf("fd12:3456:789b::/48"))
    for (bad in listOf(
      "fc00::/48", "fe80::/48", "2001:db8::/48", "fd12:3456:789a::/64", "fd12:3456:789a::", "fd12:3456:789a:1::/48",
      "::ffff:252.1.1.1/48", "192.168.1.0/48", "", "/48", "fd12::/48/48",
    )) {
      assertWithMessage(bad).that(HomeUla.canonicalOf(bad)).isNull()
    }
    assertThat(HomeUla.overlap(listOf("fd12:3456:789a::/48"), listOf("x", "FD12:3456:789A::/48"))).isTrue()
    assertThat(HomeUla.overlap(listOf("fd12:3456:789a::/48"), listOf("fd99:3456:789a::/48"))).isFalse()
    assertThat(HomeUla.overlap(emptyList(), listOf(TEST_ULA))).isFalse()
    assertThat(HomeUla.overlap(listOf("non-un-prefisso"), listOf("non-un-prefisso"))).isFalse()
    assertThat(TEST_GUID).isNotEmpty()
  }
}
