package show.taps.touch

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * root / su 通道（本机 KernelSU 即可用，不需要 Shizuku）。
 *
 * 流程：
 *   1. 起 `su -c "CLASSPATH=<base.apk> app_process /system/bin show.taps.server.RootServer …"`
 *   2. 提权进程在 127.0.0.1:<随机端口> accept，发一次性 token 做校验
 *   3. App 发 `START <dev>`，之后持续收到 S/M/Y 行
 */
object RootTouchSource {

    private const val TAG = "RootSource"

    @Volatile
    private var rootProcess: Process? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var running = false

    @Volatile
    private var availability: Boolean? = null

    fun isAvailable(): Boolean {
        availability?.let { return it }
        val ok = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(4, TimeUnit.SECONDS)) p.destroy()
            text.contains("uid=0")
        } catch (t: Throwable) {
            Log.w(TAG, "su unavailable: $t")
            false
        }
        availability = ok
        Log.d(TAG, "root available = $ok")
        return ok
    }

    fun isRunning(): Boolean = running

    /** 清掉缓存的检测结果，下次 [isAvailable] 重新试探 */
    fun resetAvailability() {
        availability = null
    }

    /** 阻塞启动（必须在后台线程调用）：返回是否成功 */
    fun startBlocking(context: Context, dev: String?, sink: TouchSink): Boolean {
        stop()

        val port = findFreePort()
        val token = UUID.randomUUID().toString().replace("-", "")
        val apk = context.applicationInfo.sourceDir
        val libDir = context.applicationInfo.nativeLibraryDir
        val cmd = "CLASSPATH='$apk' /system/bin/app_process /system/bin " +
                "${"show.taps.server.RootServer"} --port=$port --token=$token --libdir='$libDir'"

        val process = try {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.e(TAG, "launch failed", t)
            return false
        }
        rootProcess = process

        // 把提权进程的 stdout/stderr 收进 logcat，顺带防止缓冲区写满卡死
        Thread({
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    Log.d(TAG, "server: $line")
                }
            }
        }, "root-server-log").apply { isDaemon = true }.start()

        var client: Socket? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try {
                val candidate = Socket()
                candidate.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300)
                candidate.tcpNoDelay = true
                client = candidate
                break
            } catch (t: Throwable) {
                if (!process.isAlive) break
                Thread.sleep(80)
            }
        }
        if (client == null) {
            Log.e(TAG, "connect timeout")
            stop()
            return false
        }
        socket = client

        val out = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        writer = out

        if (!send(out, "HELLO $token")) {
            stop()
            return false
        }
        val ack = runCatching { input.readLine() }.getOrNull()
        if (ack == null || !ack.startsWith("OK")) {
            Log.e(TAG, "handshake failed: $ack")
            stop()
            return false
        }

        if (!send(out, "START ${dev ?: "-"}")) {
            stop()
            return false
        }

        running = true
        Thread({ readLoop(input, sink) }, "root-source-read").apply { isDaemon = true }.start()
        Log.d(TAG, "started, port=$port")
        return true
    }

    fun stop() {
        running = false
        runCatching { writer?.let { send(it, "EXIT") } }
        runCatching { socket?.close() }
        runCatching { rootProcess?.destroy() }
        writer = null
        socket = null
        rootProcess = null
    }

    private fun readLoop(input: BufferedReader, sink: TouchSink) {
        try {
            while (running) {
                val line = input.readLine() ?: break
                dispatch(line, sink)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "read loop ended", t)
        } finally {
            Log.d(TAG, "read loop end")
            running = false
        }
    }

    private fun dispatch(line: String, sink: TouchSink) {
        if (line.isEmpty()) return
        try {
            when (line[0]) {
                'S' -> {
                    val p = line.split(' ')
                    if (p.size >= 3) sink.onSlotState(p[1].toInt(), p[2] == "1")
                }

                'M' -> {
                    val p = line.split(' ')
                    if (p.size >= 4) sink.onSlotMove(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
                }

                'Y' -> sink.onSync()

                else -> Log.d(TAG, "server says: $line")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bad line: $line", t)
        }
    }

    private fun send(out: BufferedWriter, line: String): Boolean = try {
        out.write(line)
        out.write("\n")
        out.flush()
        true
    } catch (t: Throwable) {
        Log.e(TAG, "send failed: $line", t)
        false
    }

    private fun findFreePort(): Int {
        repeat(20) {
            val candidate = (20000..45000).random()
            try {
                ServerSocket(candidate, 1, InetAddress.getLoopbackAddress()).use { return candidate }
            } catch (ignored: Throwable) {
                // try next
            }
        }
        return (20000..45000).random()
    }
}
