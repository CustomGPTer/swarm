package uk.jj.swarm

import java.io.File

/**
 * The engine and screen tests need the desktop build of the torrent library, so they are opt-in:
 *   gradle testDebugUnitTest -Pengine      (on a 64-bit Linux PC)
 * Without -Pengine they are skipped and only the plain logic tests run.
 */
object NativeLib {
    fun load() {
        org.junit.Assume.assumeTrue(System.getProperty("swarm.engineTest") == "true")
        if (System.getProperty("libtorrent4j.jni.path") != null) return
        val so = File.createTempFile("libtorrent4j", ".so")
        NativeLib::class.java.classLoader!!.getResourceAsStream("lib/x86_64/libtorrent4j.so")!!.use { i -> so.outputStream().use { o -> i.copyTo(o) } }
        System.setProperty("libtorrent4j.jni.path", so.absolutePath)
    }
}
