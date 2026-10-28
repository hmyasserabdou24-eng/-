package org.darelihsan.parents

import java.net.URI

/**
 * Decides what the WebView may do with a URL. Pure Kotlin so it can be unit-tested on the JVM.
 *
 * - ALLOW    : exactly the parents' space page (same origin, same path). Query/hash are kept because
 *              invitation and password-recovery links from Supabase arrive that way.
 * - EXTERNAL : https links to other sites (WhatsApp, Facebook, Gmail, signed file URLs) and mailto/tel:
 *              opened outside the app.
 * - BLOCK    : everything else, including any other page on the same host (public site, admin panel,
 *              manage.html, admin.html, index.html, qr.html ...), cleartext http, file:, intent:, javascript:.
 */
object UrlPolicy {
    enum class Decision { ALLOW, EXTERNAL, BLOCK }

    fun decide(startUrl: String, target: String?): Decision {
        if (target.isNullOrBlank() || target.length > 4096) return Decision.BLOCK
        val start = parse(startUrl) ?: return Decision.BLOCK
        val uri = parse(target) ?: return Decision.BLOCK
        val scheme = uri.scheme?.lowercase() ?: return Decision.BLOCK

        if (scheme == "mailto" || scheme == "tel") return Decision.EXTERNAL
        if (scheme != "https") return Decision.BLOCK

        val host = uri.host?.lowercase() ?: return Decision.BLOCK
        if (uri.userInfo != null) return Decision.BLOCK

        if (host == start.host?.lowercase()) {
            val sameOrigin = effectivePort(uri) == effectivePort(start)
            return if (sameOrigin && normalizePath(uri.rawPath) == normalizePath(start.rawPath)) Decision.ALLOW else Decision.BLOCK
        }
        return Decision.EXTERNAL
    }

    fun origin(url: String): String? {
        val u = parse(url) ?: return null
        if (u.scheme?.lowercase() != "https") return null
        val port = if (u.port == -1 || u.port == 443) "" else ":" + u.port
        return "https://" + u.host.lowercase() + port
    }

    private fun parse(s: String): URI? = try { URI(s.trim()) } catch (e: Exception) { null }
    private fun effectivePort(u: URI) = if (u.port == -1) 443 else u.port
    private fun normalizePath(p: String?): String = (p ?: "").ifEmpty { "/" }
}
