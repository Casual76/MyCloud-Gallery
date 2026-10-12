package dev.pampa.mycloudgallery.core.nas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.session.FailureKind
import dev.pampa.mycloudgallery.core.nas.session.NasSessionConfig
import dev.pampa.mycloudgallery.core.nas.session.SmbErrors
import dev.pampa.mycloudgallery.core.nas.smb.NtStatus
import dev.pampa.mycloudgallery.core.nas.smb.SmbStatusException
import dev.pampa.mycloudgallery.core.nas.testing.GuestBehaviour
import dev.pampa.mycloudgallery.core.nas.testing.ManualMonotonicClock
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.nas.testing.TcpBehaviour
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * La tabella del §3.4 riga per riga: dalla condizione SMB (simulata) all'errore, al messaggio in italiano delle
 * risorse e all'effetto sull'interruttore.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorTableTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

  private class Row(
    val name: String,
    val expected: NasError,
    val message: String?,
    val opensBreaker: Boolean,
    val scenario: suspend TestScope.(Harness) -> NasError,
  )

  private suspend fun shareError(h: Harness, share: String = SHARE): NasError =
    checkNotNull(h.manager.withShare(share, Purpose.INTERACTIVE) { }.errorOrNull())

  private suspend fun listError(h: Harness, failWith: Long): NasError {
    h.backend.beforeOp = { op, _ -> if (op == SimOp.OPEN_DIRECTORY) throw SmbStatusException(failWith) }
    return checkNotNull(h.manager.browser(SHARE).list(SharePath.of("foto"), 10).errorOrNull())
  }

  private fun ioError(block: () -> Unit): NasError = try {
    block()
    throw AssertionError("attesa una NasIoException")
  } catch (e: NasIoException) {
    e.error
  }

  private fun loginRow(name: String, status: Long, reason: AuthBlockReason, message: String) =
    Row(name, NasError.Auth(reason), message, opensBreaker = true) { h ->
      h.backend.setLoginStatus(USER, status)
      shareError(h)
    }

  private val authFailed = "La password del NAS non è più valida (forse è stata cambiata). Inseriscila di nuovo."
  private val restricted = "Il NAS non permette l'accesso a questo account adesso."
  private val guest = "Il NAS ha offerto un accesso ospite: rifiutato."
  private val noEncryption = "Il NAS non ha concesso una connessione cifrata: nessun dato inviato."
  private val busy = "Il file è in uso dal PC: riprovo più tardi."

  private fun rows(): List<Row> = listOf(
    Row("0xC000006D password sbagliata", NasError.Auth(AuthBlockReason.AUTH_FAILED), authFailed, true) { h ->
      h.password = "password-sbagliata"
      shareError(h)
    },
    loginRow("0xC000006A", NtStatus.WRONG_PASSWORD, AuthBlockReason.AUTH_FAILED, authFailed),
    loginRow(
      "0xC0000234 bloccato",
      NtStatus.ACCOUNT_LOCKED_OUT,
      AuthBlockReason.ACCOUNT_LOCKED,
      "L'account del NAS è bloccato: va sbloccato dalla dashboard del NAS.",
    ),
    loginRow("0xC0000072 disattivato", NtStatus.ACCOUNT_DISABLED, AuthBlockReason.ACCOUNT_DISABLED, "Questo account è disattivato sul NAS."),
    loginRow("0xC0000071 scaduta", NtStatus.PASSWORD_EXPIRED, AuthBlockReason.PASSWORD_EXPIRED, "La password è scaduta: cambiala dal NAS."),
    loginRow("0xC0000224 da cambiare", NtStatus.PASSWORD_MUST_CHANGE, AuthBlockReason.PASSWORD_EXPIRED, "La password è scaduta: cambiala dal NAS."),
    loginRow("0xC000006E", NtStatus.ACCOUNT_RESTRICTION, AuthBlockReason.ACCOUNT_RESTRICTED, restricted),
    loginRow("0xC000006F", NtStatus.INVALID_LOGON_HOURS, AuthBlockReason.ACCOUNT_RESTRICTED, restricted),
    loginRow("0xC0000070", NtStatus.INVALID_WORKSTATION, AuthBlockReason.ACCOUNT_RESTRICTED, restricted),
    Row("guest (SMB2GuestSigningRequiredException)", NasError.InsecureSession(InsecureKind.GUEST), guest, false) { h ->
      h.backend.guest = GuestBehaviour.REJECTED_BY_LIBRARY
      shareError(h)
    },
    Row("guest (flag di sessione)", NasError.InsecureSession(InsecureKind.GUEST), guest, false) { h ->
      h.backend.guest = GuestBehaviour.SESSION_FLAG
      shareError(h)
    },
    Row("anonimo", NasError.InsecureSession(InsecureKind.ANONYMOUS), guest, false) { h ->
      h.backend.anonymous = true
      shareError(h)
    },
    Row("cifratura non offerta", NasError.InsecureSession(InsecureKind.NO_ENCRYPTION), noEncryption, false) { h ->
      h.backend.encryptionOffered = false
      shareError(h)
    },
    Row("cifratura non attiva dopo il login", NasError.InsecureSession(InsecureKind.NO_ENCRYPTION), noEncryption, false) { h ->
      h.backend.sessionEncrypted = false
      shareError(h)
    },
    Row("dialetto 3.0.2", NasError.InsecureSession(InsecureKind.DIALECT), noEncryption, false) { h ->
      h.backend.dialect = SmbDialect.SMB_3_0_2
      shareError(h)
    },
    Row("nessun dialetto comune", NasError.InsecureSession(InsecureKind.DIALECT), noEncryption, false) { h ->
      h.backend.negotiationFails = true
      shareError(h)
    },
    Row(
      "GUID diverso",
      NasError.IdentityMismatch,
      "A questo indirizzo risponde un NAS diverso da quello configurato. Nessuna password inviata.",
      false,
    ) { h ->
      h.backend.serverGuid = "11111111-2222-3333-4444-555555555555"
      shareError(h)
    },
    Row("0xC0000022 sulla share", NasError.PermissionDenied, "Non hai il permesso per questa cartella.", false) { h ->
      shareError(h, FORBIDDEN_SHARE)
    },
    Row("0xC0000022 su un file", NasError.PermissionDenied, "Non hai il permesso per questa cartella.", false) { h ->
      ioError { h.manager.fileSystem(READ_ONLY_SHARE).publishExclusive(SharePath.of("x"), byteArrayOf(1)) }
    },
    Row("0xC00000CC", NasError.ShareMissing("Foto vecchie"), "La cartella condivisa «Foto vecchie» non esiste più sul NAS.", false) { h ->
      shareError(h, "Foto vecchie")
    },
    Row(
      "0xC000007F con spazio sul volume (quota)",
      NasError.QuotaExceeded,
      "Hai esaurito lo spazio del tuo account (quota), il NAS ha ancora spazio.",
      false,
    ) { h ->
      h.backend.addShare("quota", mapOf(USER to SimAccess.READ_WRITE), quotaBytes = 10)
      ioError { h.manager.fileSystem("quota").publishExclusive(SharePath.of("a"), ByteArray(100)) }
    },
    Row("0xC000007F con NAS pieno", NasError.NasFull, "Il NAS è pieno: backup in pausa, la consultazione continua.", false) { h ->
      h.backend.volumeFreeBytes = 0
      ioError { h.manager.fileSystem(SHARE).publishExclusive(SharePath.of("a"), ByteArray(100)) }
    },
    Row("0xC0000043", NasError.FileBusy, busy, false) { h -> listError(h, NtStatus.SHARING_VIOLATION) },
    Row("0xC0000054", NasError.FileBusy, busy, false) { h -> listError(h, NtStatus.FILE_LOCK_CONFLICT) },
    Row("0xC0000035 (non e' un errore)", NasError.Exists, null, false) { h -> listError(h, NtStatus.OBJECT_NAME_COLLISION) },
    Row("0xC0000034", NasError.NotFound, null, false) { h -> listError(h, NtStatus.OBJECT_NAME_NOT_FOUND) },
    Row("0xC000003A", NasError.NotFound, null, false) { h -> listError(h, NtStatus.OBJECT_PATH_NOT_FOUND) },
    Row("TCP rifiutata", NasError.ServiceDown, "Il NAS è acceso ma la condivisione file non risponde.", false) { h ->
      h.backend.tcp = TcpBehaviour.REFUSE
      shareError(h)
    },
    Row("timeout TCP", NasError.Timeout, "Il NAS non risponde (forse si sta risvegliando)…", false) { h ->
      h.backend.tcp = TcpBehaviour.TIMEOUT
      shareError(h)
    },
    Row("fuori casa, VPN spenta", NasError.NotAllowedHere(DenyReason.VPN_OFF_AWAY), "Fuori casa: accendi la VPN", false) { h ->
      h.gate.deny(DenyReason.VPN_OFF_AWAY)
      shareError(h)
    },
    Row(
      "rete non riconosciuta",
      NasError.NotAllowedHere(DenyReason.UNTRUSTED_NETWORK),
      "Rete non riconosciuta: nessuna connessione al NAS",
      false,
    ) { h ->
      h.gate.deny(DenyReason.UNTRUSTED_NETWORK)
      shareError(h)
    },
    Row("VPN di terzi", NasError.NotAllowedHere(DenyReason.FOREIGN_VPN), "VPN non di casa attiva", false) { h ->
      h.gate.deny(DenyReason.FOREIGN_VPN)
      shareError(h)
    },
    Row(
      "rete locale negata",
      NasError.LocalNetworkBlocked,
      "Android non permette all'app di usare la rete locale.",
      false,
    ) { h ->
      h.gate.deny(DenyReason.LOCAL_NETWORK_DENIED)
      shareError(h)
    },
    Row("codice non previsto", NasError.Unexpected(0xC0000999L), "Errore imprevisto del NAS (codice 0xC0000999).", false) { h ->
      listError(h, 0xC0000999L)
    },
  )

  @Test
  fun ogniRigaDellaTabella() = runTest {
    for (row in rows()) {
      val h = Harness(backgroundScope, virtualClock())
      val error = row.scenario(this, h)
      assertWithMessage(row.name).that(error).isEqualTo(row.expected)
      assertWithMessage(row.name).that(NasErrorMessages.format(resources, error)).isEqualTo(row.message)
      assertWithMessage(row.name).that(error.opensBreaker).isEqualTo(row.opensBreaker)
      val open = h.breaker.current() is BreakerState.Open
      assertWithMessage("${row.name}: interruttore").that(open).isEqualTo(row.opensBreaker)
      if (row.opensBreaker) {
        // Un secondo tentativo non arriva al NAS: interruttore aperto, stesso messaggio.
        val again = shareError(h)
        assertWithMessage(row.name).that(again).isEqualTo(NasError.AuthBlocked((row.expected as NasError.Auth).reason))
        assertWithMessage(row.name).that(NasErrorMessages.format(resources, again)).isEqualTo(row.message)
        assertWithMessage(row.name).that(h.backend.authenticateCalls.get()).isEqualTo(1)
      }
    }
  }

  @Test
  fun sessioneScadutaRiconnessioneSilenziosa() = runTest {
    assertThat(SmbErrors.classify(SmbStatusException(NtStatus.USER_SESSION_DELETED), SHARE).let { it.error to it.kind })
      .isEqualTo(NasError.SessionExpired to FailureKind.RECONNECT)
    assertThat(SmbErrors.classify(SmbStatusException(NtStatus.NETWORK_SESSION_EXPIRED), SHARE).kind).isEqualTo(FailureKind.RECONNECT)
    assertThat(NasErrorMessages.messageRes(NasError.SessionExpired)).isNull()

    val h = Harness(backgroundScope, virtualClock())
    h.backend.put(SHARE, "foto/a.jpg", byteArrayOf(1))
    h.manager.browser(SHARE).list(SharePath.of("foto"), 10).value()
    h.backend.expireSessions()
    val page = h.manager.browser(SHARE).list(SharePath.of("foto"), 10).value()
    assertThat(page.entries.map { it.name }).containsExactly("a.jpg")
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(2)
    assertThat(h.breaker.current()).isInstanceOf(BreakerState.Closed::class.java)
  }

  @Test
  fun creditiEsauritiSiRiprova() = runTest {
    val h = Harness(backgroundScope, virtualClock())
    h.backend.put(SHARE, "foto/a.jpg", byteArrayOf(1))
    h.manager.browser(SHARE).list(SharePath.of("foto"), 10).value()
    h.backend.creditFailures.set(1)
    h.manager.browser(SHARE).list(SharePath.of("foto"), 10).value()
    assertThat(h.backoffs).hasSize(1)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
  }

  @Test
  fun bloccoDelWatchdog() = runTest {
    val clock = ManualMonotonicClock()
    val h = Harness(backgroundScope, clock, NasSessionConfig())
    h.backend.put(SHARE, "video.mp4", ByteArray(100 * 1024))
    h.backend.maxBytesPerRead = 1024
    h.backend.beforeOp = { op, _ -> if (op == SimOp.READ) clock.advance(1000) }
    val error = ioError { h.manager.fileSystem(SHARE).read(SharePath.of("video.mp4"), 1024 * 1024) }
    assertThat(error).isEqualTo(NasError.Stalled)
    assertThat(NasErrorMessages.format(resources, error)).isEqualTo("Trasferimento bloccato: rete lenta, riprendo più tardi.")
    assertThat(error.opensBreaker).isFalse()
  }

  @Test
  fun altriPercorsiNegatiHannoUnMessaggio() {
    val expected = mapOf(
      DenyReason.OFFLINE to "Offline: nessuna connessione al NAS",
      DenyReason.CAPTIVE_PORTAL to "Questa rete chiede un accesso dal browser: nessuna connessione al NAS",
      DenyReason.NOT_CONFIGURED to "Il NAS non è ancora configurato.",
    )
    expected.forEach { (reason, text) -> assertThat(NasErrorMessages.format(resources, NasError.NotAllowedHere(reason))).isEqualTo(text) }
    assertThat(NasErrorMessages.format(resources, NasError.CredentialsMissing)).isEqualTo("Serve la password del NAS.")
    assertThat(NasErrorMessages.format(resources, NasError.Unexpected(null))).isEqualTo("Errore imprevisto del NAS.")
    assertThat(NasErrorMessages.format(resources, NasError.InsecureSession(InsecureKind.SIGNING_NOT_REQUIRED))).isEqualTo(noEncryption)
  }
}
