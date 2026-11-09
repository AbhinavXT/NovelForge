package com.abhinavxt.novelforge.data.source.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareInterceptorTest {

    private fun response(code: Int, body: String, vararg headers: Pair<String, String>) =
        Response.Builder()
            .request(Request.Builder().url("https://site.test/novel").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toResponseBody("text/html".toMediaType()))
            .build()

    @Test
    fun recognisesChallengesButNotOrdinaryErrors() {
        val page = "<title>Just a moment...</title><script src=\"/cdn-cgi/challenge-platform/x.js\"></script>"
        assertTrue(CloudflareInterceptor.isChallenge(response(403, page, "Server" to "cloudflare")))
        assertTrue(CloudflareInterceptor.isChallenge(response(200, "", "cf-mitigated" to "challenge")))
        assertFalse(CloudflareInterceptor.isChallenge(response(403, "Forbidden", "Server" to "cloudflare")))
        assertFalse(CloudflareInterceptor.isChallenge(response(503, page, "Server" to "nginx")))
        assertFalse(CloudflareInterceptor.isChallenge(response(200, page, "Server" to "cloudflare")))
    }

    @Test
    fun theRequestsOwnCookiesWin() {
        assertEquals(
            "a=1; cf_clearance=x",
            CloudflareInterceptor.mergeCookies("a=1", "a=2; cf_clearance=x")
        )
        assertEquals("cf_clearance=x", CloudflareInterceptor.mergeCookies(null, "cf_clearance=x"))
    }
}
