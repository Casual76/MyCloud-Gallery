package dev.pampa.mycloudgallery.core.security.testing

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.security.faces.FacesKeys

/**
 * La SUITE DI CONTRATTO delle chiavi dei volti (docs/fase8-progetto.md §5.2, `FacesKeysContract`): la stessa gira sul fake (JVM) e sul
 * Keystore vero (strumentato, P6). Sta in `testing/` di src/main perche' la usano due insiemi di test; mai nella release. Lancia
 * `AssertionError` in italiano. Usa due generazioni sue e le distrugge alla fine.
 *
 * Controlli: creare e' idempotente e non cambia la chiave (i dati restano leggibili); cifrare e decifrare tornano uguali; un altro `aad`,
 * un byte cambiato o la chiave di un'altra generazione non decifrano (null, mai un'eccezione); due cifrature dello stesso dato sono
 * diverse (nonce nuovo); distruggere rende impossibile riaprire e quindi rileggere i dati gia' cifrati; distruggere due volte va bene;
 * `destroyAll` toglie tutte le generazioni; una chiave chiusa non cifra piu'.
 */
object FacesKeysContract {

  suspend fun verify(keys: FacesKeys) {
    val g1 = FaceGeneration.random()
    val g2 = FaceGeneration.random()
    val plain = "vettore e nome di prova".toByteArray()
    val aad = "faces/vector/1".toByteArray()

    check(keys.create(g1), "create: la chiave non si crea")
    val sealed = keys.open(g1)?.use { it.seal(plain, aad) } ?: fail("open: chiave appena creata e non apribile")
    check(keys.create(g1), "create due volte: deve riuscire")
    val again = keys.open(g1) ?: fail("open dopo il secondo create")
    again.use { key ->
      check(key.open(sealed, aad)?.contentEquals(plain) == true, "create due volte ha cambiato la chiave: dati illeggibili")
      check(key.open(sealed, "faces/vector/2".toByteArray()) == null, "un aad diverso decifra")
      val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
      check(key.open(tampered, aad) == null, "un byte cambiato decifra")
      check(key.open(ByteArray(3), aad) == null, "un dato troppo corto decifra")
      check(!key.seal(plain, aad).contentEquals(key.seal(plain, aad)), "due cifrature uguali: il nonce non cambia")
    }

    check(keys.create(g2), "create: seconda generazione")
    keys.open(g2)?.use { other -> check(other.open(sealed, aad) == null, "la chiave di un'altra generazione decifra") } ?: fail("open g2")

    val closed = keys.open(g1) ?: fail("open g1")
    closed.close()
    check(runCatching { closed.seal(plain, aad) }.isFailure, "una chiave chiusa cifra ancora")

    check(keys.destroy(g1), "destroy")
    check(keys.open(g1) == null, "dopo destroy la chiave si apre ancora")
    check(keys.destroy(g1), "destroy due volte")
    check(keys.create(g1), "create dopo destroy")
    keys.open(g1)?.use { fresh -> check(fresh.open(sealed, aad) == null, "dopo destroy i dati vecchi si leggono con la chiave nuova") }
      ?: fail("open dopo il nuovo create")

    check(keys.destroyAll(), "destroyAll")
    check(keys.open(g1) == null && keys.open(g2) == null, "destroyAll lascia chiavi")
  }

  private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
  }

  private fun fail(message: String): Nothing = throw AssertionError(message)
}
