package show.taps.server

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * root / su 通道的守护进程入口（README TODO：不再依赖 Shizuku 也能用）。
 *
 * 由 App 通过
 *   su -c "CLASSPATH=<base.apk> /system/bin/app_process /system/bin show.taps.server.RootServer \
 *          --port=<p> --token=<t> --libdir=<nativeLibraryDir>"
 * 拉起。进程在 127.0.0.1:<p> 上 accept 一次，校验一次性 token，然后把 /dev/input 的
 * 触摸数据按行写回 App。
 *
 * 协议（UTF-8，行分隔）：
 *   App  -> Server : HELLO <token> | START <devName|-> | STOP | PING | EXIT
 *   Server -> App  : OK <token> | ERR <msg> | S <slot> <0|1> | M <slot> <x> <y> | Y
 *
 * 选 TCP 回环而不是 unix socket：App 域（untrusted_app）与 root 域的 SELinux 规则下
 * unix socket 常被拒，回环 TCP 只要 App 有 INTERNET 权限就能通。
 */
object RootServer {

    private const val TAG = "RootServer"

    @Volatile
    private var writeFailed = false

    @JvmStatic
    fun main(args: Array<String>) {
        var port = 0
        var token = ""
        var libDir: String? = null
        for (a in args) {
            when {
                a.startsWith("--port=") -> port = a.substringAfter('=').toIntOrNull() ?: 0
                a.startsWith("--token=") -> token = a.substringAfter('=')
                a.startsWith("--libdir=") -> libDir = a.substringAfter('=')
            }
        }
        if (port <= 0 || token.isEmpty()) {
            Log.e(TAG, "bad args: ${args.joinToString(" ")}")
            exitProcess(2)
        }
        Log.d(TAG, "boot port=$port libDir=$libDir")

        // app_process 的 classloader 不保证能解析 APK 内的 .so，这里显式按绝对路径加载
        try {
            if (libDir != null) {
                NativeLib.loadFrom("$libDir/libtaps.so")
            } else {
                NativeLib.load()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "load libtaps failed", t)
            exitProcess(3)
        }

        var server: ServerSocket? = null
        var socket: Socket? = null
        try {
            server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2)
            server.soTimeout = 20_000
            socket = server.accept()
            socket.tcpNoDelay = true
            Log.d(TAG, "client connected: ${socket.remoteSocketAddress}")

            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            fun send(line: String) {
                if (writeFailed) return
                try {
                    output.write(line)
                    output.write("\n")
                    output.flush()
                } catch (t: Throwable) {
                    writeFailed = true
                    Log.e(TAG, "socket write failed: $t")
                }
            }

            // 握手
            val hello = input.readLine()
            if (hello == null || !hello.startsWith("HELLO ") || hello.substring(6).trim() != token) {
                Log.e(TAG, "handshake rejected: $hello")
                send("ERR bad token")
                return
            }
            send("OK $token")
            Log.d(TAG, "handshake ok, uid=${android.os.Process.myUid()}")

            val sink = object : NativeLib.Sink {
                override fun onSlotState(slot: Int, down: Boolean) = send("S $slot ${if (down) 1 else 0}")
                override fun onSlotMove(slot: Int, x: Float, y: Float) = send("M $slot $x $y")
                override fun onSync() = send("Y")
            }

            // 命令循环
            while (!writeFailed) {
                val line = try {
                    input.readLine()
                } catch (t: Throwable) {
                    Log.e(TAG, "socket read failed: $t")
                    null
                } ?: break

                when {
                    line == "EXIT" -> {
                        Log.d(TAG, "EXIT")
                        break
                    }

                    line == "STOP" -> {
                        runCatching { NativeLib.stop() }
                        NativeLib.sink = null
                    }

                    line.startsWith("START") -> {
                        val dev = line.removePrefix("START").trim().let {
                            if (it.isEmpty() || it == "-") null else it
                        }
                        val path = InputDevices.pick(dev)
                        if (path == null) {
                            send("ERR no touch device")
                            continue
                        }
                        runCatching { NativeLib.stop() }
                        NativeLib.sink = sink
                        if (NativeLib.start(path)) {
                            Log.d(TAG, "reading $path")
                            send("OK started $path")
                        } else {
                            NativeLib.sink = null
                            send("ERR native start failed")
                        }
                    }

                    line == "PING" -> send("OK pong")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "fatal: $t")
        } finally {
            runCatching { NativeLib.stop() }
            runCatching { socket?.close() }
            runCatching { server?.close() }
            Log.d(TAG, "exit")
            exitProcess(0)
        }
    }
}
