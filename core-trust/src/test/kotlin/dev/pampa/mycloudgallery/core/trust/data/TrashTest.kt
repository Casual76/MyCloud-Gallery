package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.codec.json
import dev.pampa.mycloudgallery.core.trust.codec.jsonObject
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.rejectionOrNull
import dev.pampa.mycloudgallery.core.trust.testing.TestFamily
import dev.pampa.mycloudgallery.core.trust.testing.deviceId
import org.junit.Test

class TrashTest {

  private val family = TestFamily()
  private val phone = family.approveNewDevice(name = "Telefono", nasUser = "babbo")
  private val policy = TrashPurgePolicy(phone.deviceId)
  private val day = 24L * 3600 * 1000
  private val trashId = TrustIds.random()

  @Test
  fun recordFirmatoFaIlGiro_eIPercorsiOstiliSonoRifiutati() {
    val record = TrashRecord(family.familyId, phone.deviceId, "Public", trashId, SharePath.parse("Foto/2024/IMG_0001.jpg"), 1234, family.clock.now)
    val verified = TrashRecord.verify(record.sign(phone), family.state, "Public", phone.deviceId, trashId)
    assertThat(verified.acceptedOrNull()).isEqualTo(record)

    for (hostile in listOf("../../Privata-Mamma/x.jpg", "/etc/passwd", "\\\\server\\x", "C:\\x.jpg", ".mycloudgallery/family/registry/00000000000000000009", "CON", "a\u202Egpj.exe")) {
      val payload = jsonObject(
        "family" to family.familyId.json(),
        "device" to phone.deviceId.json(),
        "share" to "Public".json(),
        "trashId" to trashId.json(),
        "originalPath" to hostile.json(),
        "size" to 1.json(),
        "trashedAt" to family.clock.now.json(),
      )
      val bytes = Envelope.seal(DocType.TRASH_RECORD, payload, phone)
      assertThat(TrashRecord.verify(bytes, family.state, "Public", phone.deviceId, trashId).rejectionOrNull()).isEqualTo(RejectReason.HOSTILE_PATH)
    }
  }

  /** M5 (Fase 4B, P12): `reason`, `folder` e, solo per le cartelle, `count`; obbligatori e stretti. */
  @Test
  fun iCampiNuoviFannoIlGiroESonoStretti() {
    val folder = TrashRecord(
      family.familyId, phone.deviceId, "Public", trashId, SharePath.parse("Gita"), 4096, family.clock.now,
      reason = TrashRecordReason.DELETE, folder = true, count = 12,
    )
    assertThat(TrashRecord.verify(folder.sign(phone), family.state, "Public", phone.deviceId, trashId).acceptedOrNull()).isEqualTo(folder)
    val moved = TrashRecord(
      family.familyId, phone.deviceId, "Public", trashId, SharePath.parse("Foto/a.jpg"), 10, family.clock.now, reason = TrashRecordReason.MOVE,
    )
    assertThat(TrashRecord.verify(moved.sign(phone), family.state, "Public", phone.deviceId, trashId).acceptedOrNull()).isEqualTo(moved)

    fun payload(vararg extra: Pair<String, dev.pampa.mycloudgallery.core.trust.codec.JsonValue>) = Envelope.seal(
      DocType.TRASH_RECORD,
      jsonObject(
        "family" to family.familyId.json(),
        "device" to phone.deviceId.json(),
        "share" to "Public".json(),
        "trashId" to trashId.json(),
        "originalPath" to "Foto/a.jpg".json(),
        "size" to 1.json(),
        "trashedAt" to family.clock.now.json(),
        *extra,
      ),
      phone,
    )
    fun verdict(bytes: ByteArray) = TrashRecord.verify(bytes, family.state, "Public", phone.deviceId, trashId).rejectionOrNull()
    // Senza i campi nuovi, con un motivo sconosciuto, con count per un file o senza count per una cartella: rifiutato.
    assertThat(verdict(payload())).isEqualTo(RejectReason.MALFORMED)
    assertThat(verdict(payload("reason" to "delete".json()))).isEqualTo(RejectReason.MALFORMED)
    assertThat(verdict(payload("reason" to "purge".json(), "folder" to false.json()))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(verdict(payload("reason" to "delete".json(), "folder" to false.json(), "count" to 3.json()))).isEqualTo(RejectReason.MALFORMED)
    assertThat(verdict(payload("reason" to "delete".json(), "folder" to true.json()))).isEqualTo(RejectReason.MALFORMED)
    assertThat(verdict(payload("reason" to "move".json(), "folder" to true.json(), "count" to (-1).json()))).isEqualTo(RejectReason.INVALID_FIELD)
    assertThat(verdict(payload("reason" to "move".json(), "folder" to false.json()))).isNull()
  }

  @Test
  fun unRecordRemotoDaSoloNonFaEliminareNulla() {
    // Finto cestino: un record valido (anche firmato da un dispositivo approvato) non e' un'azione locale.
    val decision = policy.decide(entry = null, item = TrashItemInfo(10), nasNow = family.clock.now + 365 * day, round = PurgeRound())

    assertThat(decision).isEqualTo(PurgeDecision.Keep(KeepReason.NO_LOCAL_ACTION))
  }

  @Test
  fun siEliminaSoloDopo30GiorniSullOraDelNas_esoloNelProprioCestino() {
    val entry = LocalTrashEntry("Public", trashId, size = 500, trashedAtNas = family.clock.now)

    assertThat(policy.decide(entry, TrashItemInfo(500), family.clock.now + 30 * day - 1, PurgeRound())).isEqualTo(PurgeDecision.Keep(KeepReason.NOT_EXPIRED))
    val purge = policy.decide(entry, TrashItemInfo(500), family.clock.now + 30 * day, PurgeRound())
    assertThat(purge).isEqualTo(PurgeDecision.Purge(NasLayout.trashItemFile(phone.deviceId, trashId)))
    assertThat((purge as PurgeDecision.Purge).path.isWithin(NasLayout.ROOT.child("trash").child(phone.deviceId))).isTrue()
  }

  @Test
  fun fileCambiatoOGiaSparito_nienteEliminazione() {
    val entry = LocalTrashEntry("Public", trashId, size = 500, trashedAtNas = family.clock.now)
    val later = family.clock.now + 31 * day

    assertThat(policy.decide(entry, TrashItemInfo(501), later, PurgeRound())).isEqualTo(PurgeDecision.Keep(KeepReason.CHANGED))
    assertThat(policy.decide(entry, null, later, PurgeRound())).isEqualTo(PurgeDecision.AlreadyGone)
  }

  @Test
  fun diarioLocaleConDataNelFuturo_nienteEliminazione() {
    val entry = LocalTrashEntry("Public", trashId, size = 1, trashedAtNas = family.clock.now + 400 * day)

    assertThat(policy.decide(entry, TrashItemInfo(1), family.clock.now, PurgeRound())).isEqualTo(PurgeDecision.Keep(KeepReason.IMPLAUSIBLE_TIME))
  }

  @Test
  fun tettoPerGiro() {
    val round = PurgeRound()
    val later = family.clock.now + 31 * day
    val decisions = (1..TrustLimits.MAX_PURGE_ITEMS_PER_ROUND + 5).map {
      policy.decide(LocalTrashEntry("Public", TrustIds.random(), 1, family.clock.now), TrashItemInfo(1), later, round)
    }

    assertThat(decisions.count { it is PurgeDecision.Purge }).isEqualTo(TrustLimits.MAX_PURGE_ITEMS_PER_ROUND)
    assertThat(decisions.takeLast(5)).containsExactly(PurgeDecision.Defer, PurgeDecision.Defer, PurgeDecision.Defer, PurgeDecision.Defer, PurgeDecision.Defer)

    val bytesRound = PurgeRound()
    val big = TrustLimits.MAX_PURGE_BYTES_PER_ROUND / 2 + 1
    val first = policy.decide(LocalTrashEntry("Public", TrustIds.random(), big, family.clock.now), TrashItemInfo(big), later, bytesRound)
    val second = policy.decide(LocalTrashEntry("Public", TrustIds.random(), big, family.clock.now), TrashItemInfo(big), later, bytesRound)
    assertThat(first).isInstanceOf(PurgeDecision.Purge::class.java)
    assertThat(second).isEqualTo(PurgeDecision.Defer)
  }

  @Test
  fun idDelDiarioMalformato_nienteEliminazione() {
    val entry = LocalTrashEntry("Public", "../../x", size = 1, trashedAtNas = family.clock.now)

    assertThat(policy.decide(entry, TrashItemInfo(1), family.clock.now + 31 * day, PurgeRound())).isEqualTo(PurgeDecision.Keep(KeepReason.NO_LOCAL_ACTION))
  }
}
