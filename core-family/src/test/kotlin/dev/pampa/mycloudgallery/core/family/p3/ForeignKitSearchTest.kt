package dev.pampa.mycloudgallery.core.family.p3

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OldAdminFate
import dev.pampa.mycloudgallery.core.family.contract.RecoveryOutcome
import dev.pampa.mycloudgallery.core.family.lifecycle.ForeignShareAccess
import dev.pampa.mycloudgallery.core.family.lifecycle.KitAdminRecovery
import dev.pampa.mycloudgallery.core.nas.contract.NasAccount
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P9, contratto `AdminRecovery.findKits(account, area, password)`: un familiare diventa secondo admin dalle
 * Impostazioni. L'area privata dell'admin si apre solo con l'account dell'admin: i kit si leggono con UNA sessione usa e
 * getta ([ForeignShareAccess]) e la password digitata, che si azzera; il recupero usa poi il kit gia' letto, senza
 * chiedere di nuovo la password e senza passare dalla sessione del dispositivo per l'area privata.
 */
class ForeignKitSearchTest {

  private val world = FamilyWorld()
  private val adminAccount = NasAccount(NasAddress("192.168.178.58"), "admin2", "slot", "0f8fad5b-d9cb-469f-a165-70867728950e")

  /** Una sessione "dell'admin" finta: legge il NAS di prova e ricorda con che account e password e' stata aperta. */
  private class RecordingForeign(private val world: FamilyWorld) : ForeignShareAccess {
    val calls = mutableListOf<Pair<String, String>>()
    var passwordLeftInCaller: CharArray? = null
    var error: NasError? = null

    override suspend fun <T> withShare(account: NasAccount, password: CharArray, share: String, block: (NasFileSystem) -> T): NasResult<T> {
      calls += account.user to String(password)
      passwordLeftInCaller = password
      error?.let { return NasResult.Err(it) }
      return NasResult.Ok(block(world.server.share(share)))
    }
  }

  @Test
  fun ilSecondoAdminDaUnFamiliareLeggeIKitConLAccountDellAdmin() = runTest {
    world.admin.engine.ensureLoaded()
    val member = world.joinApproved("Tablet di Mamma", "mamma", privateShare = "mamma")
    val foreign = RecordingForeign(world)
    val recovery = KitAdminRecovery(member.engine, member.sessions, member.keys, member.vault, world.clock, foreign)

    val password = "password-dell-admin".toCharArray()
    val kits = (recovery.findKits(adminAccount, "admin2", password) as NasResult.Ok).value
    assertThat(kits).hasSize(1)
    assertThat(foreign.calls).containsExactly("admin2" to "password-dell-admin")
    assertThat(password.all { it == '\u0000' }).isTrue() // azzerata dopo l'uso
    // La sessione del dispositivo (account "mamma") non ha toccato l'area privata dell'admin.
    assertThat(member.sessions.calls.map { it.first }).doesNotContain("admin2")

    val outcome = recovery.recover(kits.single(), world.recoveryCode.toDisplayString().toCharArray(), null, OldAdminFate.KEEP_APPROVED)
    assertThat(outcome).isEqualTo(RecoveryOutcome.Recovered)
    assertThat(member.sessions.calls.map { it.first }).doesNotContain("admin2")
    assertThat(foreign.calls).hasSize(1) // la password non serve una seconda volta
    val state = member.engine.state!!
    assertThat(state.registry.device(member.id)!!.admin).isTrue()
    assertThat(state.statusOf(world.admin.id)).isEqualTo(DeviceStatus.APPROVED)
  }

  @Test
  fun unaPasswordSbagliataDaUnErroreEUnaNuovaRicercaDimenticaIKitPrecedenti() = runTest {
    val member = world.joinApproved("Tablet di Mamma", "mamma", privateShare = "mamma")
    val foreign = RecordingForeign(world)
    val recovery = KitAdminRecovery(member.engine, member.sessions, member.keys, member.vault, world.clock, foreign)

    foreign.error = NasError.Auth(dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason.AUTH_FAILED)
    val wrong = "sbagliata".toCharArray()
    assertThat(recovery.findKits(adminAccount, "admin2", wrong)).isInstanceOf(NasResult.Err::class.java)
    assertThat(wrong.all { it == '\u0000' }).isTrue()
  }

  @Test
  fun senzaPasswordSiUsaLaSessioneDelDispositivo() = runTest {
    val phone = Device(world, "Telefono nuovo di Admin", "admin2", privateShare = "admin2")
    val foreign = RecordingForeign(world)
    val recovery = KitAdminRecovery(phone.engine, phone.sessions, phone.keys, phone.vault, world.clock, foreign)
    assertThat((recovery.findKits(adminAccount, "admin2") as NasResult.Ok).value).hasSize(1)
    assertThat(foreign.calls).isEmpty()
    assertThat(phone.sessions.calls.map { it.first }).contains("admin2")
  }
}
