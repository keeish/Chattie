package com.example.util

import android.util.Base64
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordSecurity {

    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16

    enum class Strength {
        WEAK,
        MEDIUM,
        STRONG
    }

    fun evaluateStrength(password: String): Strength {
        if (password.length < 8) return Strength.WEAK
        var score = 0
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++
        if (password.length >= 12) score++

        return when {
            score >= 4 -> Strength.STRONG
            score >= 2 -> Strength.MEDIUM
            else -> Strength.WEAK
        }
    }

    fun hashPassword(password: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)

        val hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashBase64 = Base64.encodeToString(hash, Base64.NO_WRAP)

        return "$ITERATIONS:$saltBase64:$hashBase64"
    }

    fun verifyPassword(password: String, storedHash: String): Boolean {
        if (storedHash.isBlank()) return false
        // Direct match check in case plain text was saved in early prototype
        if (storedHash == password) return true

        val parts = storedHash.split(":")
        if (parts.size == 3) {
            val iterations = parts[0].toIntOrNull() ?: return false
            val salt = try { Base64.decode(parts[1], Base64.NO_WRAP) } catch (e: Exception) { return false }
            val expectedHash = try { Base64.decode(parts[2], Base64.NO_WRAP) } catch (e: Exception) { return false }

            val testHash = pbkdf2(password.toCharArray(), salt, iterations, expectedHash.size * 8)
            if (slowEquals(testHash, expectedHash)) return true
        }

        // SHA-256 fallback check
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(password.toByteArray(Charsets.UTF_8))
            val hexString = digest.joinToString("") { "%02x".format(it) }
            if (hexString.equals(storedHash, ignoreCase = true)) return true
        } catch (_: Exception) {}

        return false
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLength)
        return try {
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            skf.generateSecret(spec).encoded
        } catch (e: NoSuchAlgorithmException) {
            val fallback = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            fallback.generateSecret(spec).encoded
        } catch (e: InvalidKeySpecException) {
            throw IllegalStateException("Invalid key spec for PBKDF2", e)
        }
    }

    private fun slowEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        for (i in 0 until minOf(a.size, b.size)) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
