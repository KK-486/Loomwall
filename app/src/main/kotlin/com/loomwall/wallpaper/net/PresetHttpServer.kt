package com.loomwall.wallpaper.net

import android.content.Context
import android.util.Log
import com.loomwall.wallpaper.scene.PresetImporter
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * 手机端的「接收预设」HTTP 服务。
 *
 * 为什么放在手机上：网页在电脑上跑，浏览器不能调 adb，也不该在电脑上装桥接服务。
 * 反过来让手机监听一个端口，网页直接把 JSON POST 过来——电脑端零安装。
 *
 * 约束：
 * - 只在主界面可见时运行（退出即停），不常驻、不耗电；
 * - 只接受局域网内的请求，不需要登录（预设内容不是机密，且端口只在开发时开）；
 * - 零第三方依赖：手写一个最小 HTTP/1.1 解析，够用即可。
 */
class PresetHttpServer(
    private val context: Context,
    private val onImported: (Boolean, String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: ServerSocket? = null

    /** 当前监听的端口，页面要显示给用户。 */
    var port: Int = 0
        private set

    /**
     * 启动接收服务。
     *
     * **绑定必须在调用线程同步完成**：主界面要立刻显示真实端口，
     * 之前把 bind 放在后台线程，导致界面显示 `:0`，网页拿着 0 端口当然连不上。
     */
    fun start(preferredPort: Int = DEFAULT_PORT): Boolean {
        if (running.getAndSet(true)) return true

        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (e: Exception) {
            Log.w(TAG, "接收服务启动失败：${e.message}")
            running.set(false)
            return false
        }

        socket = server
        port = server.localPort
        Log.i(TAG, "接收服务已启动：$port")

        thread = Thread({
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                runCatching { handle(client) }
                    .onFailure { Log.w(TAG, "处理请求失败：${it.message}") }
            }
        }, "LOOW-Import").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        port = 0
    }

    private fun handle(client: Socket) {
        client.use { conn ->
            conn.soTimeout = 15000
            val input = BufferedInputStream(conn.getInputStream())
            val output: OutputStream = conn.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            when {
                method == "OPTIONS" -> respond(output, 204, "")

                method == "GET" && path == "/ping" ->
                    respond(output, 200, """{"ok":true,"app":"LOOW"}""")

                method == "POST" && path == "/import" -> {
                    val body = readBody(input, contentLength)
                    if (body.isBlank()) {
                        respond(output, 400, """{"ok":false,"message":"空请求体"}""")
                        return
                    }
                    // 走壁纸库：先入库再激活，主页据此多出一张卡片（不再覆盖旧预设）
                    val result = com.loomwall.wallpaper.scene.PresetLibrary(context).import(body)
                    if (result.ok) com.loomwall.wallpaper.scene.PresetLibrary.notifyChanged(context)

                    // 回调由宿主实现，可能碰 UI（弹 Toast）——这里必须兜住，
                    // 否则它抛异常会打断后续 respond，浏览器端表现为"传到一半连接被关"
                    runCatching { onImported(result.ok, result.message) }
                        .onFailure { Log.w(TAG, "导入回调异常：${it.message}") }
                    respond(
                        output,
                        200,
                        JSONObject().apply {
                            put("ok", result.ok)
                            put("message", result.message)
                        }.toString()
                    )
                }

                else -> respond(output, 404, """{"ok":false,"message":"not found"}""")
            }
        }
    }

    private fun respond(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "OK"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            // 网页和手机不同源，必须放行跨域，否则浏览器会拦掉响应
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: content-type\r\n")
            append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) builder.append(b.toChar())
        }
        return builder.toString()
    }

    private fun readBody(input: BufferedInputStream, length: Int): String {
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val TAG = "LOOW.Import"
    }
}
