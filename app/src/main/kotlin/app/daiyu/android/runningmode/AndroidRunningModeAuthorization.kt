package app.daiyu.android.runningmode

import app.daiyu.android.shizuku.ShizukuAuthorization
import app.daiyu.android.shizuku.ShizukuAvailability
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class AndroidRunningModeAuthorization(private val shizuku: ShizukuAuthorization) : RunningModeAuthorization {
    override suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        // Discover only; execute one fixed UID probe, never try a second su after a denial.
        val executable = (listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su") +
            System.getenv("PATH").orEmpty().split(':').filter { it.startsWith('/') }.map { "$it/su" })
            .distinct().firstOrNull { File(it).isFile && File(it).canExecute() } ?: return@withContext false
        val process = try { ProcessBuilder(executable, "-c", "/system/bin/id -u").redirectErrorStream(true).start() }
            catch (_: Exception) { return@withContext false }
        try {
            process.outputStream.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (process.isAlive && System.nanoTime() < deadline) delay(50)
            if (process.isAlive || process.exitValue() != 0) return@withContext false
            val output = ByteArray(128)
            val size = process.inputStream.read(output)
            size > 0 && String(output, 0, size, Charsets.UTF_8).trim() == "0"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
            process.inputStream.close()
            process.errorStream.close()
        }
    }

    override suspend fun requestShizuku(): Boolean = shizuku.requestPermissionAndAwait()
    override suspend fun shizukuGranted(): Boolean = withContext(Dispatchers.IO) {
        shizuku.refresh().availability == ShizukuAvailability.AUTHORIZED
    }
}
