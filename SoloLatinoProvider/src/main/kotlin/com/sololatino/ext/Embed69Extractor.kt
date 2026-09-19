package com.sololatino.ext

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extractor para embed69.org
 *
 * El sitio protege los links con un Proof-of-Work (SHA-256 hasta encontrar
 * un nonce cuyo hash empiece con N ceros) y la clave derivada descifra los
 * embeds via AES-CBC.
 */
object Embed69Extractor {
    suspend fun load(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val aesKey: ByteArray
        app.get(url).document.select("script")
            .firstOrNull { it.html().contains("dataLink = [") }?.html()?.let {
                val powChallenge = it.substringAfter("const POW_CHALLENGE = '").substringBefore("';")
                val powDifficulty = it.substringAfter("const POW_DIFFICULTY = ").substringBefore(";").toInt()
                val powSalt = it.substringAfter("const POW_SALT = '").substringBefore("';")
                aesKey = deriveAesKey(powChallenge, powDifficulty, powSalt)
                it
            }?.substringAfter("dataLink = ")
            ?.substringBefore(";")?.let {
                AppUtils.tryParseJson<List<ServersByLang>>(it)?.amap { lang ->
                    val links = lang.sortedEmbeds.amap { decryptAES(it.link!!, aesKey) }
                    if (links.isNotEmpty()) {
                        links.filterNotNull().amap {
                            loadSourceNameExtractor(
                                lang.videoLanguage!!,
                                fixHostsLinks(it),
                                referer,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
    }

    fun deriveAesKey(challenge: String, difficulty: Int, salt: String): ByteArray {
        val prefix = "0".repeat(difficulty)
        var nonce: Long = 0
        val batchSize = 5000

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }

        fun sha256Bytes(input: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray(Charsets.UTF_8))
        }

        while (true) {
            for (i in 0 until batchSize) {
                val hashHex = sha256Hex(challenge + nonce)
                if (hashHex.startsWith(prefix)) {
                    return sha256Bytes(challenge + nonce + salt)
                }
                nonce++
            }
        }
    }

    fun decryptAES(encryptedBase64: String, aesKey: ByteArray): String? {
        return try {
            val raw = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val iv = raw.copyOfRange(0, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)
            val keyBytes = aesKey.copyOfRange(0, 32)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

data class Server(
    @JsonProperty("servername") val servername: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("type") val type: String? = null,
)

data class ServersByLang(
    @JsonProperty("file_id") val fileId: String? = null,
    @JsonProperty("video_language") val videoLanguage: String? = null,
    @JsonProperty("sortedEmbeds") val sortedEmbeds: List<Server> = emptyList(),
    @JsonProperty("downloadEmbeds") val downloadEmbeds: List<Server> = emptyList(),
)

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    // Invocacion directa (sin CoroutineScope.launch): emitir el link de forma
    // sincrona evita que CloudStream cierre loadLinks antes de recibirlo,
    // lo que rompia la descarga.
    loadExtractor(url, referer, subtitleCallback) { link ->
        callback.invoke(
            newExtractorLink(
                "$source[${link.source}]",
                "$source[${link.source}]",
                link.url,
            ) {
                this.quality = link.quality
                this.type = link.type
                this.referer = link.referer
                this.headers = link.headers
                this.extractorData = link.extractorData
            }
        )
    }
}

/**
 * Normaliza los dominios rotados de los embeds a los que los
 * extractores de CloudStream ya conocen.
 */
fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}
