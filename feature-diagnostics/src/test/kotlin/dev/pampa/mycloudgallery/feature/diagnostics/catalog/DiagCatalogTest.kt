package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.rules.CapKind
import dev.pampa.mycloudgallery.core.rules.ConfirmationKind
import dev.pampa.mycloudgallery.core.rules.DeviceRole
import dev.pampa.mycloudgallery.core.rules.ForegroundBudget
import dev.pampa.mycloudgallery.core.rules.NetworkClass
import dev.pampa.mycloudgallery.core.rules.ThermalStatus
import dev.pampa.mycloudgallery.core.rules.TimeWindow
import dev.pampa.mycloudgallery.core.rules.WaitReason
import java.io.File
import java.lang.reflect.Modifier
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Test

/**
 * Il catalogo dei `DiagCode` (docs/fase2-progetto.md §2.6): **ogni edge case di Fase 2 che produce un avviso ha il
 * suo codice**. E' il «fatto quando» del piano 2.7 («ogni avviso ha una voce corrispondente in Diagnostica»).
 *
 * Come non si dimentica un edge case: gli identificativi citati nei §2 e §3 del progetto vengono **letti dal
 * documento** e ciascuno deve essere coperto da un codice oppure stare nell'elenco di quelli che non producono un
 * avviso, con il motivo. Un edge case nuovo nel documento fa fallire questo test finche' non lo si classifica.
 */
class DiagCatalogTest {

  // --- Il catalogo in se' -----------------------------------------------------------------------------------

  @Test
  fun ogniCodiceDichiaratoHaUnaVoceEViceversa() {
    val declared = DiagCodes::class.java.declaredFields
      .filter { Modifier.isStatic(it.modifiers) && it.type == DiagCode::class.java }
      .map { (it.apply { isAccessible = true }.get(null) as DiagCode).id }
    val catalogued = DiagCatalog.entries.map { it.code.id }

    assertThat(declared).isNotEmpty()
    assertThat(catalogued).containsNoDuplicates()
    assertThat(catalogued).containsExactlyElementsIn(declared)
  }

  @Test
  fun ogniAvvisoEOgniErroreHaIlCosaFare() {
    val senzaGuida = DiagCatalog.entries.filter { it.severity >= DiagSeverity.WARNING && it.guideRes == 0 }.map { it.code.id }

    assertWithMessage("Avvisi senza «cosa fare»: $senzaGuida").that(senzaGuida).isEmpty()
  }

  @Test
  fun ogniVoceHaUnTitoloEUnaSezioneEUnPrefissoCoerente() {
    DiagCatalog.entries.forEach { entry ->
      assertWithMessage(entry.code.id).that(entry.titleRes).isNotEqualTo(0)
      assertWithMessage(entry.code.id).that(DiagCatalog.entry(entry.code)).isSameInstanceAs(entry)
    }
  }

  @Test
  fun leAzioniSuggeriteSonoQuelleChePossiamoNominare() {
    val noteAdAltri = DiagCatalog.entries.mapNotNull { it.action }.toSet()
    // Tutte hanno un'etichetta (un'azione senza etichetta non avrebbe tasto).
    noteAdAltri.forEach { action ->
      assertWithMessage(action.id).that(dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions.labelRes(action))
        .isNotEqualTo(0)
    }
  }

  // --- Gli edge case di Fase 2 ---------------------------------------------------------------------------------

  /**
   * Gli edge case di Fase 2 (progetto §2-§4 e piano §6) che **producono un avviso** in Diagnostica. Ognuno ha
   * almeno un codice che lo dichiara.
   */
  private val warningEdgeCases = setOf(
    // Rete e VPN
    "E-R1", "E-R3", "E-R4", "E-R5", "E-R6", "E-R7", "E-R8", "E-R9", "E-R10", "E-R11", "E-R12", "E-R13", "E-R14",
    "E-R16", "E-R17", "L12", "E-S10", "E-S16", "T-19",
    // Sicurezza della sessione
    "C3", "C5", "E-S12", "E-S9",
    // Onboarding
    "E-O1", "E-O2", "E-O3", "E-O4", "E-O5", "E-O6", "E-O7", "E-O8",
    // NAS: contenuti
    "E-N9", "E-N13",
    // Telefono e profili
    "E-P1", "E-P2", "E-P3", "E-P4", "E-P8", "E-P9", "E-P10", "E-P13", "E-P14", "E-P15", "E-P16", "E-P19", "E-P20",
    // Attivita'
    "E-A5", "E-A6", "E-B10",
    // Fiducia e dispositivi
    "E-S1", "E-S2", "E-S3", "E-S8", "E-S13", "E-S14", "C15", "B9", "M1", "M4", "M7", "E-I3",
  )

  @Test
  fun ogniEdgeCaseDiFase2CheProduceUnAvvisoHaIlSuoCodice() {
    val scoperti = warningEdgeCases.filterNot { it in DiagCatalog.coveredEdgeCases }

    assertWithMessage("Edge case senza un DiagCode: $scoperti").that(scoperti).isEmpty()
    warningEdgeCases.forEach { edgeCase ->
      assertWithMessage(edgeCase).that(DiagCatalog.codesCovering(edgeCase)).isNotEmpty()
    }
  }

  /**
   * Gli identificativi citati nei §2-§3 del progetto che NON producono un avviso in Diagnostica, col motivo. Sono
   * flussi (l'onboarding, l'approvazione), vincoli di progetto, misure da fare sul campo, o avvisi che vivono in
   * un'altra schermata.
   */
  private val notADiagnosticWarning = mapOf(
    "B6" to "l'admin conferma l'account dichiarato dal richiedente: e' un passo dell'approvazione",
    "C13" to "avviso permanente sul backup esterno: sta in Impostazioni (P7), non in Diagnostica",
    "E-A4" to "consiglio al passo 7 dell'onboarding (nessuna NPU), non un avviso a regime",
    "E-B3" to "«esiste gia'» con gli stessi byte vale come successo: non e' un errore",
    "E-N14" to "proposta di esclusione al passo 5 dell'onboarding",
    "E-N15" to "proposta di esclusione al passo 5 dell'onboarding",
    "E-O9" to "ripresa dell'onboarding dopo un cambio di app: e' il comportamento normale, non un avviso",
    "E-S15" to "guida del passo VPN (il QR di WireGuard e' una password), non uno stato",
    "L20" to "limite dichiarato (revisione 2, F2): la VPN a tunnel limitato si spegne su una rete che imita casa; non e' uno stato",
    "L24" to "limite dichiarato (revisione 2, F9): al massimo 2 tentativi; l'interruttore aperto ha gia' il suo avviso (C5)",
    "M11" to "misura sul campo (rotte della VPN di casa)",
    "T-17" to "prova sul campo (rotte della VPN di casa)",
    "T-20" to "prova sul campo (profilo in primo piano)",
  )

  @Test
  fun leEccezioniHannoUnMotivoENonSonoGiaCoperte() {
    notADiagnosticWarning.forEach { (id, reason) ->
      assertWithMessage(id).that(reason).isNotEmpty()
      assertWithMessage("$id e' coperto da un codice: toglilo dall'elenco delle eccezioni")
        .that(id in DiagCatalog.coveredEdgeCases).isFalse()
    }
  }

  @Test
  fun ogniIdentificativoCitatoNeiFlussiEDiSessioneEClassificato() {
    val doc = File("../docs/fase2-progetto.md")
    assertWithMessage("docs/fase2-progetto.md non trovato dalla cartella ${File(".").absolutePath}").that(doc.exists()).isTrue()
    val text = doc.readText(Charsets.UTF_8)
    val from = text.indexOf("## 2. Flussi")
    val to = text.indexOf("## 4. Pacchetti di lavoro")
    assertWithMessage("§2-§3 non trovati nel progetto").that(from >= 0 && to > from).isTrue()

    val cited = Regex("""\b(E-[A-Z]{1,2}[0-9]{1,3}|[BCLM][0-9]{1,3}|T-[0-9]{1,3})\b""")
      .findAll(text.substring(from, to))
      .map { it.value }
      .toSortedSet()
    assertThat(cited).isNotEmpty()

    val unclassified = cited.filterNot { it in DiagCatalog.coveredEdgeCases || it in notADiagnosticWarning }
    assertWithMessage(
      "Identificativi citati in §2-§3 senza un DiagCode e non classificati come «non e' un avviso»: $unclassified",
    ).that(unclassified).isEmpty()
  }

  // --- I contratti dei moduli --------------------------------------------------------------------------------

  @Test
  fun ogniMotivoDiNegazioneDelPercorsoHaIlSuoCodice() {
    val expected = mapOf(
      DenyReason.OFFLINE to DiagCodes.NET_OFFLINE,
      DenyReason.CAPTIVE_PORTAL to DiagCodes.NET_CAPTIVE_PORTAL,
      DenyReason.UNTRUSTED_NETWORK to DiagCodes.NET_UNTRUSTED,
      DenyReason.VPN_OFF_AWAY to DiagCodes.NET_VPN_OFF_AWAY,
      DenyReason.FOREIGN_VPN to DiagCodes.NET_FOREIGN_VPN,
      DenyReason.LOCAL_NETWORK_DENIED to DiagCodes.NET_LOCAL_NETWORK_DENIED,
      DenyReason.NOT_CONFIGURED to DiagCodes.NET_NOT_CONFIGURED,
    )
    expected.forEach { (reason, code) ->
      assertThat(DiagMapping.code(reason)).isEqualTo(code)
      assertThat(code in DiagCatalog).isTrue()
    }
    // Un valore che i moduli in parallelo aggiungessero domani non rompe la mappa: va nel codice generico.
    DenyReason.entries.forEach { assertThat(DiagMapping.code(it) in DiagCatalog).isTrue() }
  }

  @Test
  fun ogniErroreDiAutenticazioneEDiSicurezzaHaIlSuoCodiceEIlSuoMessaggio() {
    val auth = mapOf(
      AuthBlockReason.AUTH_FAILED to DiagCodes.NAS_AUTH_FAILED,
      AuthBlockReason.ACCOUNT_LOCKED to DiagCodes.NAS_ACCOUNT_LOCKED,
      AuthBlockReason.ACCOUNT_DISABLED to DiagCodes.NAS_ACCOUNT_DISABLED,
      AuthBlockReason.PASSWORD_EXPIRED to DiagCodes.NAS_PASSWORD_EXPIRED,
      AuthBlockReason.ACCOUNT_RESTRICTED to DiagCodes.NAS_ACCOUNT_RESTRICTED,
    )
    assertThat(AuthBlockReason.entries).containsExactlyElementsIn(auth.keys)
    auth.forEach { (reason, code) ->
      assertThat(DiagMapping.code(NasError.Auth(reason))).isEqualTo(code)
      assertThat(DiagMapping.message(NasError.Auth(reason)).resId).isNotEqualTo(0)
      // Il codice dell'interruttore aperto e' uno solo, con il motivo nell'esportazione.
      assertThat(DiagMapping.code(NasError.AuthBlocked(reason))).isEqualTo(DiagCodes.NAS_AUTH_BLOCKED)
    }
    val insecure = mapOf(
      InsecureKind.GUEST to DiagCodes.NAS_GUEST_REFUSED,
      InsecureKind.ANONYMOUS to DiagCodes.NAS_GUEST_REFUSED,
      InsecureKind.NO_ENCRYPTION to DiagCodes.NAS_NOT_ENCRYPTED,
      InsecureKind.DIALECT to DiagCodes.NAS_DIALECT_REFUSED,
      InsecureKind.SIGNING_NOT_REQUIRED to DiagCodes.NAS_SIGNING_MISSING,
    )
    assertThat(InsecureKind.entries).containsExactlyElementsIn(insecure.keys)
    insecure.forEach { (kind, code) -> assertThat(DiagMapping.code(NasError.InsecureSession(kind))).isEqualTo(code) }
  }

  @Test
  fun laTabellaDelParagrafo34HaUnCodiceEUnMessaggioPerOgniErrore() {
    val errors = listOf(
      NasError.IdentityMismatch,
      NasError.PermissionDenied,
      NasError.ShareMissing("una-share"),
      NasError.QuotaExceeded,
      NasError.NasFull,
      NasError.FileBusy,
      NasError.Exists,
      NasError.NotFound,
      NasError.SessionExpired,
      NasError.ServiceDown,
      NasError.Timeout,
      NasError.LocalNetworkBlocked,
      NasError.Stalled,
      NasError.CredentialsMissing,
      NasError.Unexpected(0xC0000001L),
      NasError.Unexpected(null),
    ) + DenyReason.entries.map { NasError.NotAllowedHere(it) }

    errors.forEach { error ->
      assertWithMessage("$error").that(DiagMapping.code(error) in DiagCatalog).isTrue()
      assertWithMessage("$error").that(DiagMapping.message(error).resId).isNotEqualTo(0)
    }
    // Il nome della share e' un dato dell'utente: sullo schermo si', ma nel testo di toString no (§4.2).
    assertThat(NasError.ShareMissing("segreto").toString()).doesNotContain("segreto")
  }

  @Test
  fun ogniMotivoDiAttesaDelleRegoleHaUnCodiceDelCatalogo() {
    val window = TimeWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))
    val reasons = listOf(
      WaitReason.ProfileInactive,
      WaitReason.RoleDisabled(DeviceRole.INDEXER),
      WaitReason.ActivityDisabled,
      WaitReason.AppNotVisible,
      WaitReason.Roaming,
      WaitReason.Offline,
      WaitReason.NetworkNotTrusted,
      WaitReason.PoorNetwork,
      WaitReason.OutsideWindow(window, LocalDateTime.of(2026, 10, 13, 23, 0)),
      WaitReason.NeedsPower,
      WaitReason.BatteryLow(19, 20),
      WaitReason.PowerSaver,
      WaitReason.Thermal(ThermalStatus.SEVERE),
      WaitReason.NetworkNotAllowed(NetworkClass.MOBILE),
      WaitReason.HeavyNeedsHomeWifi,
      WaitReason.CapReached(CapKind.MOBILE, 1_200_000_000L, 1_000_000_000L),
      WaitReason.AndroidLimit(ForegroundBudget.DATA_SYNC),
      WaitReason.NasUnreachable,
      WaitReason.ConfirmationRequired(setOf(ConfirmationKind.MOBILE_DATA)),
    )
    val codes = reasons.map { ActivityReason.of(it) }

    // Una per ogni sottotipo, tutte diverse, tutte nel catalogo con una sezione «Attivita'».
    assertThat(codes.toSet()).hasSize(reasons.size)
    codes.forEach { assertThat(it.code in DiagCatalog).isTrue() }
    assertThat(ActivityReason.entries).containsExactlyElementsIn(codes)
  }

  // --- Le sezioni -------------------------------------------------------------------------------------------------

  @Test
  fun ogniSezioneHaAlmenoUnaVoceNelCatalogo() {
    dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection.entries.forEach { section ->
      assertWithMessage(section.name).that(DiagCatalog.entriesOf(section)).isNotEmpty()
    }
  }

  @Test
  fun unCodiceSconosciutoNonRompeNienteEVieneSegnalato() {
    val unknown = DiagCode("SOMETHING_NEW")
    assertThat(unknown in DiagCatalog).isFalse()
    assertThat(DiagCatalog.titleOf(unknown)).isEqualTo(0)
    assertThat(DiagCatalog.guideOf(unknown)).isEqualTo(0)
    assertThat(DiagCatalog.unknownCodes(listOf(dev.pampa.mycloudgallery.feature.diagnostics.item(unknown), dev.pampa.mycloudgallery.feature.diagnostics.item())))
      .containsExactly(unknown)
  }
}
