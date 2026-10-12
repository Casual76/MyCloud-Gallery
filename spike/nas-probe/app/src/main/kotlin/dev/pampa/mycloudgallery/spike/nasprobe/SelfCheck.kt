package dev.pampa.mycloudgallery.spike.nasprobe

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.security.Cipher

/** Versione di smbj usata (tenerla allineata a app/build.gradle.kts). */
const val SMBJ_VERSION = "0.15.0"

/**
 * Controllo locale, senza rete, che smbj e BouncyCastle funzionino su questo Android: carica le classi,
 * istanzia le primitive crittografiche che servono a NTLMv2, firma, cifratura SMB3 e KDF, e verifica
 * MD4("") con il valore noto. Non apre alcuna connessione.
 */
fun smbjSelfCheck(): Pair<Boolean, String> {
    val failures = mutableListOf<String>()
    fun probe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failures += "$name: ${t.javaClass.simpleName}"
        }
    }

    val sp = try {
        SmbConfig.createDefaultConfig().securityProvider
    } catch (t: Throwable) {
        return false to "smbj $SMBJ_VERSION: configurazione non creata (${t.javaClass.simpleName})"
    }
    probe("SMBClient") { SMBClient(buildSmbConfig(encrypt = true, socketFactory = null)).close() }
    probe("MD4") {
        val d = sp.getDigest("MD4")
        val hex = d.digest().joinToString("") { "%02x".format(it) }
        check(hex == "31d6cfe0d16ae931b73c59d7e0c089c0") { "valore errato" }
    }
    probe("MD5") { sp.getDigest("MD5").digest() }
    probe("SHA-512") { sp.getDigest("SHA-512").digest() }
    probe("SHA256") { sp.getDigest("SHA256").digest() }
    probe("HMACT64") { sp.getMac("HMACT64").init(ByteArray(16)) }
    probe("HmacSHA256") { sp.getMac("HmacSHA256").init(ByteArray(16)) }
    probe("AesCmac") { sp.getMac("AesCmac").init(ByteArray(16)) }
    probe("RC4") { sp.getCipher("RC4").init(Cipher.CryptMode.ENCRYPT, ByteArray(16)) }
    probe("DES") { sp.getCipher("DES/ECB/NoPadding") }
    probe("AES-CCM") { sp.getAEADBlockCipher("AES/CCM/NoPadding") }
    probe("AES-GCM") { sp.getAEADBlockCipher("AES/GCM/NoPadding") }
    probe("KDF") { sp.getDerivationFunction("KDF/Counter/HMACSHA256") }

    return if (failures.isEmpty()) true to "smbj $SMBJ_VERSION: controllo locale OK (classi e crittografia caricate)"
    else false to "smbj $SMBJ_VERSION: PROBLEMI nel controllo locale: ${failures.joinToString(", ")}"
}
