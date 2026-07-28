package com.tvassist.data.ha

import android.util.Log
import okhttp3.OkHttpClient
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Opt-in relaxation of TLS verification, for a Home Assistant reached over `https://` with a
 * self-signed or private-CA certificate (the usual homelab setup, where no public CA will ever
 * issue for a LAN name).
 *
 * This trades **authentication** for **confidentiality**: an active man-in-the-middle on the LAN can
 * still impersonate HA and capture the long-lived token. It exists because the app already permits
 * plain `http://`, which is strictly worse — there the token is readable by passive sniffing — so
 * users with a self-signed cert would otherwise be pushed onto the less safe option.
 *
 * Deliberately constrained, so the toggle can't quietly become a hole:
 *  - **Private hosts only.** [isPrivateHost] requires *every* address the host resolves to be
 *    loopback / link-local / RFC1918 / RFC4193. Pointing the app at a public hostname re-enables
 *    full verification no matter how the toggle is set.
 *  - **Resolved addresses, not name patterns.** A LAN can legitimately use real domain names via
 *    split-horizon DNS, so matching on the hostname string would lock those users out.
 *  - **Home Assistant clients only.** Icon downloads and map tiles from the public internet keep
 *    strict verification — see [HaRepository.clientFor] and [com.tvassist.ui.IconStore].
 */
object InsecureTls {
    private const val TAG = "HaTls"

    /** Accepts any chain. Only ever installed on a client that passed the [isPrivateHost] gate. */
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Installs the trust-all manager and bypasses hostname verification on [builder].
     *
     * The hostname bypass is not optional in practice: homelab certs are typically issued for a name
     * the user never types (they connect by IP, or by a split-horizon name the cert doesn't list),
     * so trusting the chain alone would still fail verification.
     */
    fun relax(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), SecureRandom())
        }
        return builder
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
    }

    /**
     * True if [url] is `https://` and its host resolves **entirely** to private address space.
     *
     * Blocking DNS — call from [kotlinx.coroutines.Dispatchers.IO], never the main thread. Returns
     * false if the name doesn't resolve, so a lookup failure fails closed (strict verification).
     */
    fun isPrivateHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return false
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (addrs.isNullOrEmpty()) {
            Log.w(TAG, "cannot resolve $host — keeping strict TLS")
            return false
        }
        // Every address must be private: a host that also resolves to a public address is not a
        // LAN-only host, and relaxing for it would expose the token off-network.
        val allPrivate = addrs.all { isPrivateAddress(it) }
        if (!allPrivate) Log.w(TAG, "$host resolves outside private space — keeping strict TLS")
        return allPrivate
    }

    private fun isPrivateAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress) return true
        // Covers IPv4 RFC1918 (10/8, 172.16/12, 192.168/16); for IPv6 it only covers the deprecated
        // fec0::/10, so unique-local fc00::/7 is checked explicitly below.
        if (a.isSiteLocalAddress) return true
        if (a is Inet6Address) return (a.address[0].toInt() and 0xFE) == 0xFC
        return false
    }
}
