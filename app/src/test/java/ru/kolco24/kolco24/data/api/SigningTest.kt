package ru.kolco24.kolco24.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class SigningTest {

    @Test
    fun buildCanonical_matchesApiDocExample() {
        val canonical = buildCanonical("GET", "/app/race/8/teams/", "1718200000")

        val expected = listOf(
            "GET",
            "/app/race/8/teams/",
            "1718200000",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        ).joinToString("\n")
        assertEquals(expected, canonical)
    }

    @Test
    fun buildCanonical_uppercasesMethod() {
        val canonical = buildCanonical("get", "/app/races/", "1718200000")

        assertEquals("GET", canonical.substringBefore("\n"))
    }

    @Test
    fun sign_matchesExternallyComputedVector() {
        // Vector computed with Python's hmac: secret="test-secret-123" over the API.md
        // canonical example. Verify with:
        //   python3 -c 'import hmac,hashlib;print(hmac.new(b"test-secret-123",
        //   b"GET\n/app/race/8/teams/\n1718200000\n"
        //   b"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        //   hashlib.sha256).hexdigest())'
        val canonical = buildCanonical("GET", "/app/race/8/teams/", "1718200000")

        val sig = sign("test-secret-123", canonical)

        assertEquals(
            "cf1c254fb2eac6c7efde1cff6efe9553878370299cd60a42be4d2105a8072588",
            sig,
        )
    }

    @Test
    fun sign_producesLowerCaseHex64Chars() {
        val sig = sign("secret", buildCanonical("GET", "/app/races/", "1"))

        assertEquals(64, sig.length)
        assertEquals(sig.lowercase(), sig)
    }
}
