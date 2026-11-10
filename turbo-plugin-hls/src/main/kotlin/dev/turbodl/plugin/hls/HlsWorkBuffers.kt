package dev.turbodl.plugin.hls

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Owned by one sequential worker, including its retries and decryption. */
internal class HlsWorkBuffers {
    val input: ByteArray by lazy(LazyThreadSafetyMode.NONE) { ByteArray(128 * 1024) }
    val decrypted: ByteArray by lazy(LazyThreadSafetyMode.NONE) { ByteArray(input.size + 16) }
}

internal object HlsAes128 {
    fun decrypt(input: File, output: File, key: ByteArray, iv: ByteArray, buffers: HlsWorkBuffers, checkActive: () -> Unit) {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        input.inputStream().use { source ->
            output.outputStream().use { destination ->
                var total = 0L
                while (true) {
                    checkActive()
                    val count = source.read(buffers.input)
                    if (count < 0) break
                    total = HlsIo.checkedTotal(total, count.toLong())
                    val written = cipher.update(buffers.input, 0, count, buffers.decrypted, 0)
                    if (written > 0) destination.write(buffers.decrypted, 0, written)
                }
                checkActive()
                // Keep explicit doFinal: invalid CBC padding must fail, never publish a partial segment.
                val written = cipher.doFinal(buffers.decrypted, 0)
                if (written > 0) destination.write(buffers.decrypted, 0, written)
            }
        }
    }
}
