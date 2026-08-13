package io.github.PctAIGM.procview.export

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ExportAnonymizer private constructor(private val salt: ByteArray) {
    private val packageAliases = mutableMapOf<String, String>()
    private val applicationAliases = mutableMapOf<String, String>()
    private val processAliases = mutableMapOf<String, String>()
    private val commandAliases = mutableMapOf<String, String>()
    private val uidAliases = mutableMapOf<Int, Int>()

    fun packageName(value: String?): String = value
        ?.takeIf(String::isNotBlank)
        ?.let { packageAliases.getOrPut(it) { "app_${token("package", it)}" } }
        .orEmpty()

    fun packageCandidates(value: String?): String = value
        ?.split('|')
        .orEmpty()
        .filter(String::isNotBlank)
        .map(::packageName)
        .distinct()
        .sorted()
        .joinToString("|")

    fun applicationName(packageName: String?, displayName: String): String {
        if (!packageName.isNullOrBlank()) return this.packageName(packageName)
        return applicationAliases.getOrPut(displayName) {
            "native_${token("application", displayName)}"
        }
    }

    fun processName(value: String): String = if (value.isBlank()) "" else {
        processAliases.getOrPut(value) { "process_${token("process", value)}" }
    }

    fun commandLine(value: String): String = if (value.isBlank()) "" else {
        commandAliases.getOrPut(value) { "command_${token("command", value)}" }
    }

    fun uid(value: Int?): Int? = value?.let { uidAliases.getOrPut(it) { uidAliases.size + 1 } }

    private fun token(namespace: String, value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        val digest = mac.doFinal("$namespace\u0000$value".toByteArray(Charsets.UTF_8))
        return buildString(TOKEN_BYTES * 2) {
            repeat(TOKEN_BYTES) { index ->
                val byte = digest[index].toInt() and 0xff
                append(HEX_DIGITS[byte ushr 4])
                append(HEX_DIGITS[byte and 0x0f])
            }
        }
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val TOKEN_BYTES = 6
        private const val SALT_BYTES = 32
        private const val HEX_DIGITS = "0123456789abcdef"

        fun random(): ExportAnonymizer = ExportAnonymizer(
            ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes),
        )

        internal fun withSaltForTesting(salt: ByteArray): ExportAnonymizer {
            require(salt.size == SALT_BYTES) { "test salt must be 256 bits" }
            return ExportAnonymizer(salt.copyOf())
        }
    }
}
