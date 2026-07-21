package com.tvassist.data.web

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Builds an [SSLContext] backed by a self-signed certificate for the on-demand HTTPS setup console.
 * The cert lists the TV's current LAN IP (+ localhost) as SubjectAltNames so a browser opened at
 * https://<ip> gets a name match (still an untrusted click-through — it's self-signed, and there's
 * no public CA for a private IP).
 *
 * The keystore is **persisted** to app-private storage and reused across app restarts, so trusting
 * the cert once in a browser sticks. It's only regenerated when the IP changes or the cert expires.
 * The file must be excluded from backup (it holds a private key) — see the backup rules.
 */
object SelfSignedTls {
    private val PW = "tvassist".toCharArray() // protects the app-private keystore file
    @Volatile private var cached: Pair<String, SSLContext>? = null

    fun contextFor(ip: String?, store: File?): SSLContext {
        val key = ip.orEmpty()
        cached?.let { if (it.first == key) return it.second }

        // Reuse a persisted keystore if it still matches this IP and hasn't expired.
        val reusable = store?.let { loadKeyStore(it) }?.takeIf { certMatches(it, ip) }
        val ks = reusable ?: generate(ip).also { fresh ->
            store?.let { f -> runCatching { f.outputStream().use { fresh.store(it, PW) } } }
        }
        return sslFrom(ks).also { cached = key to it }
    }

    private fun loadKeyStore(f: File): KeyStore? =
        if (!f.exists()) null
        else runCatching { KeyStore.getInstance("PKCS12").apply { f.inputStream().use { load(it, PW) } } }.getOrNull()

    private fun certMatches(ks: KeyStore, ip: String?): Boolean {
        val cert = runCatching { ks.getCertificate("tv") as? X509Certificate }.getOrNull() ?: return false
        if (cert.notAfter.before(Date())) return false
        if (ip.isNullOrBlank()) return true
        val sans = runCatching { cert.subjectAlternativeNames }.getOrNull() ?: return false
        return sans.any { it.size >= 2 && it[0] == 7 && it[1] == ip } // type 7 = iPAddress
    }

    private fun sslFrom(ks: KeyStore): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PW) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun generate(ip: String?): KeyStore {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)          // backdate for clock skew
        val notAfter = Date(now + 3650L * 24 * 3600 * 1000)    // ~10 years
        val subject = X500Name("CN=TV Assist Setup")
        val provider = BouncyCastleProvider() // used directly — not registered globally (avoids clash)

        val sans = buildList {
            if (!ip.isNullOrBlank()) add(GeneralName(GeneralName.iPAddress, ip))
            add(GeneralName(GeneralName.iPAddress, "127.0.0.1"))
            add(GeneralName(GeneralName.dNSName, "localhost"))
        }.toTypedArray()

        val certBuilder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), notBefore, notAfter, subject, keyPair.public,
        ).addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans))

        val signer = JcaContentSignerBuilder("SHA256WithRSA").setProvider(provider).build(keyPair.private)
        val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(certBuilder.build(signer))

        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("tv", keyPair.private, PW, arrayOf(cert))
        }
    }
}
