package dev.pampa.mycloudgallery.core.trust.data

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import org.junit.Test

/** La pulizia dei propri file (§2.3, M6): politica pura, file per file, con posto E prova. */
class OwnFilesPolicyTest {

  private val me = TrustIds.random()
  private val other = TrustIds.random()
  private val policy = OwnFilesPolicy(me)
  private val now = TestRecords.NOW
  private val day = 24L * 60 * 60 * 1000
  private val hash = Hashes.sha256Name("segmento".toByteArray())

  private fun headFile(version: Long, device: String = me) = OwnFile.classify(NasLayout.headFile(device, "index", version)) as OwnFile.Head

  private fun segFile(device: String = me) = OwnFile.classify(NasLayout.segmentFile(device, "index", hash)) as OwnFile.Segment

  private fun leaseFile(generation: Long) = OwnFile.classify(NasLayout.leaseFile("work/1/prep/2024-03/2", generation)) as OwnFile.LeaseGeneration

  private fun tempFile(device: String = me) = OwnFile.classify(NasLayout.ROOT.child("tmp").child("$device-op-abc")) as OwnFile.Temp

  @Test
  fun ilPostoSiRiconosceSoloConLaFormaEsatta() {
    assertThat(headFile(7).version).isEqualTo(7)
    assertThat(segFile().hash).isEqualTo(hash)
    assertThat(leaseFile(300).generation).isEqualTo(300)
    assertThat(tempFile().deviceId).isEqualTo(me)
    val notOwn = listOf(
      NasLayout.registryFile(1),
      NasLayout.trashItemFile(me, TrustIds.random()),
      NasLayout.ROOT.child("devices").child(me).child("index").child("head").child("7"),
      NasLayout.ROOT.child("devices").child(me).child("index").child("seg").child("non-un-hash"),
      NasLayout.ROOT.child("devices").child(me).child("index").child("altro").child(hash),
      NasLayout.ROOT.child("leases").child(NasLayout.resourceName("x")).child(NasLayout.versionName(1)).child(NasLayout.versionName(300)),
      NasLayout.ROOT.child("tmp").child("nessun-id"),
      SharePath.of("Foto", "a.jpg"),
    )
    notOwn.forEach { assertThat(OwnFile.classify(it)).isNull() }
  }

  @Test
  fun teste_siTengonoLeUltimeTreEMaiLaPiuAlta() {
    val mine = (1L..6L).toSet()
    fun decide(v: Long, signed: Boolean = true, versions: Set<Long> = mine, device: String = me) =
      policy.decide(OwnFileFacts.HeadFacts(headFile(v, device), signed, versions), now)

    assertThat(decide(6)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.RECENT_HEAD))
    assertThat(decide(4)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.RECENT_HEAD))
    assertThat(decide(3)).isEqualTo(OwnFileDecision.Remove)
    assertThat(decide(1)).isEqualTo(OwnFileDecision.Remove)
    assertThat(decide(9, versions = emptySet())).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.RECENT_HEAD))
    // Un file nella mia cartella firmato da altri, o una testa di un altro: non e' mio.
    assertThat(decide(1, signed = false)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_SIGNED_BY_ME))
    assertThat(decide(1, device = other)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))
  }

  @Test
  fun segmenti_soloNonElencati_nelDiario_eVecchiDiSetteGiorniDiOraDelNas() {
    fun decide(listed: Boolean = false, diary: Boolean = true, at: Long? = now - 8 * day, device: String = me) =
      policy.decide(OwnFileFacts.SegmentFacts(segFile(device), listed, diary, at), now)

    assertThat(decide()).isEqualTo(OwnFileDecision.Remove)
    assertThat(decide(listed = true)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.LISTED))
    assertThat(decide(diary = false)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_IN_DIARY))
    assertThat(decide(at = null)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.AGE_UNKNOWN))
    assertThat(decide(at = now - 6 * day)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.TOO_RECENT))
    assertThat(decide(at = now + TrustLimits.MAX_FUTURE_SKEW_MILLIS + 1)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.TIME_IN_FUTURE))
    assertThat(decide(device = other)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))
  }

  @Test
  fun prenotazioni_soloLeProprieSuperate_maiLaPiuAlta() {
    fun decide(generation: Long, signed: Boolean = true, highest: Long = 10) =
      policy.decide(OwnFileFacts.LeaseFacts(leaseFile(generation), signed, highest), now)

    assertThat(decide(3)).isEqualTo(OwnFileDecision.Remove)
    assertThat(decide(10)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.HIGHEST_GENERATION))
    assertThat(decide(3, signed = false)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_SIGNED_BY_ME))
  }

  @Test
  fun temporanei_soloIMieiVecchiENonCitatiDalDiario() {
    fun decide(device: String = me, at: Long? = now - 2 * day, cited: Boolean = false) =
      policy.decide(OwnFileFacts.TempFacts(tempFile(device), at, cited), now)

    assertThat(decide()).isEqualTo(OwnFileDecision.Remove)
    assertThat(decide(at = now - day / 2)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.TOO_RECENT))
    assertThat(decide(cited = true)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.CITED_BY_DIARY))
    assertThat(decide(device = other)).isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))
  }

  @Test
  fun provaDiChiChiamaCheNonCorrispondeAlPosto_nonBasta() {
    // Chi chiama dice "testa mia, versione 1" ma il percorso e' di un altro dispositivo.
    val forged = OwnFile.Head(NasLayout.headFile(other, "index", 1), me, HeadKind.INDEX, 1)
    assertThat(policy.decide(OwnFileFacts.HeadFacts(forged, signedByMe = true, ownVersions = setOf(1, 2, 3, 4, 5)), now))
      .isEqualTo(OwnFileDecision.Keep(OwnKeepReason.NOT_MINE))
  }

  @Test
  fun pianoDelGiro_tettoESospensione() {
    val facts = (1..600).map {
      val seg = OwnFile.classify(NasLayout.segmentFile(me, "index", Hashes.sha256Name(byteArrayOf(it.toByte(), (it shr 8).toByte())))) as OwnFile.Segment
      OwnFileFacts.SegmentFacts(seg, listedByRecentHeads = false, inDiary = true, modifiedAtNas = now - 30 * day)
    }

    val plan = policy.plan(facts, now, suspended = false)
    assertThat(plan.remove).hasSize(TrustLimits.MAX_OWN_FILES_PER_ROUND)
    assertThat(plan.kept[OwnKeepReason.SUSPENDED]).isEqualTo(100)
    assertThat(policy.plan(facts, now, suspended = true).remove).isEmpty()
  }
}
