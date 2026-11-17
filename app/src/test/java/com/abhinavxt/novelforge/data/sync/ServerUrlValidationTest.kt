package com.abhinavxt.novelforge.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlValidationTest {

    private fun ok(url: String, local: Boolean = false) =
        (validateServerUrl(url, local) as ServerUrlResult.Valid).url

    private fun bad(url: String, local: Boolean = false) =
        assertTrue(url, validateServerUrl(url, local) is ServerUrlResult.Invalid)

    @Test fun httpsAccepted() = assertEquals("https://dav.example.com/", ok("https://dav.example.com"))

    @Test fun trailingSlashAddedOnce() {
        assertEquals("https://dav.example.com/a/b/", ok("https://dav.example.com/a/b"))
        assertEquals("https://dav.example.com/a/b/", ok("https://dav.example.com/a/b/"))
    }

    @Test fun whitespaceTrimmed() = assertEquals("https://h/", ok("  https://h  "))

    @Test fun portKept() = assertEquals("https://h:8443/dav/", ok("https://h:8443/dav"))

    @Test fun httpRejectedInRelease() {
        bad("http://example.com/")
        bad("http://localhost/")
        bad("http://127.0.0.1/")
    }

    @Test fun httpOnlyToLocalhostWhenAllowed() {
        assertEquals("http://localhost:8080/", ok("http://localhost:8080", true))
        assertEquals("http://127.0.0.1/dav/", ok("http://127.0.0.1/dav", true))
        bad("http://example.com/", true)
        bad("http://192.168.1.5/", true)
        bad("http://localhost.evil.com/", true)
        bad("http://127.0.0.2/", true)
    }

    @Test fun embeddedCredentialsRejected() {
        bad("https://user:pass@example.com/")
        bad("https://user@example.com/")
    }

    @Test fun queryAndFragmentRejected() {
        bad("https://example.com/?a=1")
        bad("https://example.com/#frag")
    }

    @Test fun missingHostOrGarbageRejected() {
        bad("")
        bad("   ")
        bad("https://")
        bad("example.com")
        bad("ftp://example.com/")
        bad("not a url")
    }
}
