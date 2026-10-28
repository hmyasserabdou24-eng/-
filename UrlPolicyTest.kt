package org.darelihsan.parents

import org.junit.Assert.assertEquals
import org.junit.Test
import org.darelihsan.parents.UrlPolicy.Decision.*

class UrlPolicyTest {
    private val start = "https://example.github.io/DarElIhsan3/parent.html"
    private fun d(u: String?) = UrlPolicy.decide(start, u)

    @Test fun allowsParentPageWithQueryAndHash() {
        assertEquals(ALLOW, d(start))
        assertEquals(ALLOW, d("$start?u=abc#access_token=x&type=recovery"))
    }
    @Test fun blocksPublicSiteAndAdmin() {
        for (p in listOf("index.html", "manage.html", "admin.html", "site.html", "qr.html", "404.html", ""))
            assertEquals(p, BLOCK, d("https://example.github.io/DarElIhsan3/$p"))
        assertEquals(BLOCK, d("https://example.github.io/"))
        assertEquals(BLOCK, d("https://example.github.io/DarElIhsan3/parent.html/../manage.html"))
        assertEquals(BLOCK, d("https://example.github.io/DarElIhsan3/PARENT.html"))
    }
    @Test fun blocksDangerousSchemes() {
        for (u in listOf("http://example.github.io/DarElIhsan3/parent.html", "javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "content://x", "data:text/html,hi", null, ""))
            assertEquals(u, BLOCK, d(u))
    }
    @Test fun otherHostsAreExternalNotInApp() {
        assertEquals(EXTERNAL, d("https://wa.me/213668034147"))
        assertEquals(EXTERNAL, d("mailto:a@b.dz"))
        assertEquals(BLOCK, d("https://user:pw@example.github.io/DarElIhsan3/parent.html"))
    }
    @Test fun lookalikeHostIsNotSameOrigin() {
        assertEquals(EXTERNAL, d("https://example.github.io.evil.com/DarElIhsan3/parent.html"))
        assertEquals(BLOCK, d("https://example.github.io:8443/DarElIhsan3/parent.html"))
    }
    @Test fun originOnlyForHttps() {
        assertEquals("https://example.github.io", UrlPolicy.origin(start))
        assertEquals(null, UrlPolicy.origin("http://example.github.io/x"))
    }
}
