package com.xnotes.platform

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A minimal local HTTP server that streams MJPEG frames to browsers (PAL §16 /
 * spec 12 §6). Routes: `/` (viewer), `/stream` (multipart/x-mixed-replace),
 * `/frame` (single JPEG), `/status` (JSON). One-way and read-only.
 */
class PresentationServer {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var currentFrame: ByteArray = ByteArray(0)
    private val clients = CopyOnWriteArrayList<Socket>()

    /** Supplies the `/status` JSON body. */
    @Volatile var statusJson: () -> String = { "{}" }

    /** Notified when the connected-client count changes. */
    @Volatile var onClientCountChanged: (Int) -> Unit = {}

    /** Notified when a viewer requests a stream quality ("low" | "medium" | "high"). */
    @Volatile var onQualityRequest: (String) -> Unit = {}

    val clientCount: Int get() = clients.size
    val isRunning: Boolean get() = running

    fun start(port: Int, lan: Boolean): Result<Unit> = try {
        stop()
        val address = InetAddress.getByName(if (lan) "0.0.0.0" else "127.0.0.1")
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(address, port))
        }
        serverSocket = socket
        running = true
        Thread { acceptLoop(socket) }.apply { isDaemon = true; start() }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun stop() {
        running = false
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        onClientCountChanged(0)
    }

    /** Push a new JPEG frame to every connected stream client. */
    fun pushFrame(jpeg: ByteArray) {
        currentFrame = jpeg
        val header = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray()
        for (socket in clients) {
            try {
                val out = socket.getOutputStream()
                synchronized(socket) {
                    out.write(header)
                    out.write(jpeg)
                    out.write(CRLF)
                    out.flush()
                }
            } catch (_: Exception) {
                dropClient(socket)
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            Thread { handle(client) }.apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: run { socket.close(); return }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            when {
                path == "/" -> {
                    writeText(out, "text/html; charset=utf-8", VIEWER_HTML.toByteArray())
                    socket.close()
                }
                path.startsWith("/stream") -> startStream(socket, out)
                path.startsWith("/frame") -> {
                    writeBinary(out, "image/jpeg", currentFrame)
                    socket.close()
                }
                path.startsWith("/status") -> {
                    writeText(out, "application/json", statusJson().toByteArray())
                    socket.close()
                }
                path.startsWith("/quality") -> {
                    val q = queryParam(path, "q")
                    if (q == "low" || q == "medium" || q == "high") onQualityRequest(q)
                    writeText(out, "text/plain", (q ?: "").toByteArray())
                    socket.close()
                }
                else -> {
                    out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    socket.close()
                }
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    private fun startStream(socket: Socket, out: java.io.OutputStream) {
        val headers = "HTTP/1.0 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
            "Cache-Control: no-cache\r\nPragma: no-cache\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray())
        if (currentFrame.isNotEmpty()) {
            out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${currentFrame.size}\r\n\r\n".toByteArray())
            out.write(currentFrame)
            out.write(CRLF)
        }
        out.flush()
        clients.add(socket) // kept open; pushFrame writes subsequent parts
        onClientCountChanged(clients.size)
    }

    private fun dropClient(socket: Socket) {
        if (clients.remove(socket)) {
            runCatching { socket.close() }
            onClientCountChanged(clients.size)
        }
    }

    private fun writeText(out: java.io.OutputStream, contentType: String, body: ByteArray) {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun writeBinary(out: java.io.OutputStream, contentType: String, body: ByteArray) = writeText(out, contentType, body)

    private fun queryParam(path: String, key: String): String? {
        val q = path.substringAfter('?', "")
        if (q.isEmpty()) return null
        return q.split('&').firstNotNullOfOrNull {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            if (k == key) v else null
        }
    }

    companion object {
        private const val BOUNDARY = "xnoteframe"
        private val CRLF = "\r\n".toByteArray()

        /**
         * Dependency-free viewer: fits the MJPEG stream to the window, auto-reconnects.
         * Double-click toggles fullscreen; right-click opens a quality menu that posts
         * the choice back to `/quality`.
         */
        val VIEWER_HTML = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>白い熊 速記 presentation</title>
            <style>
              html,body{margin:0;height:100%;background:#000;overflow:hidden}
              img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}
              #msg{position:absolute;inset:0;display:none;align-items:center;justify-content:center;
                   color:#888;font-family:monospace;font-size:1.2rem}
              #menu{position:fixed;display:none;background:#1e1e1e;border:1px solid #444;border-radius:8px;
                    padding:4px 0;min-width:160px;box-shadow:0 6px 24px rgba(0,0,0,.5);
                    font-family:system-ui,sans-serif;font-size:.95rem;color:#eee;z-index:10}
              #menu .hd{padding:6px 16px;color:#888;font-size:.75rem;text-transform:uppercase;letter-spacing:.05em}
              #menu .it{padding:8px 16px;cursor:pointer;display:flex;align-items:center;gap:8px}
              #menu .it:hover{background:#333}
              #menu .it .dot{width:8px;height:8px;border-radius:50%;background:transparent}
              #menu .it.on .dot{background:#5b9dff}
              #hint{position:fixed;left:0;right:0;bottom:0;text-align:center;color:#666;
                    font-family:system-ui,sans-serif;font-size:.8rem;padding:6px;
                    transition:opacity .4s;pointer-events:none}
            </style></head><body>
            <img id="v" src="/stream" alt="">
            <div id="msg">Presentation ended</div>
            <div id="menu">
              <div class="hd">Quality</div>
              <div class="it" data-q="low"><span class="dot"></span>Low</div>
              <div class="it" data-q="medium"><span class="dot"></span>Medium</div>
              <div class="it" data-q="high"><span class="dot"></span>High</div>
            </div>
            <div id="hint">Double-click for fullscreen &nbsp;·&nbsp; right-click for quality</div>
            <script>
              var v=document.getElementById('v'),m=document.getElementById('msg'),
                  menu=document.getElementById('menu'),hint=document.getElementById('hint'),
                  quality='medium';
              v.onerror=function(){m.style.display='flex';setTimeout(function(){v.src='/stream?'+Date.now();m.style.display='none';},2000);};
              function fs(){if(document.fullscreenElement){document.exitFullscreen();}else{document.documentElement.requestFullscreen();}}
              document.addEventListener('dblclick',function(e){if(menu.style.display!=='block'){fs();}});
              function mark(){var its=menu.querySelectorAll('.it');for(var i=0;i<its.length;i++){its[i].classList.toggle('on',its[i].getAttribute('data-q')===quality);}}
              document.addEventListener('contextmenu',function(e){
                e.preventDefault();
                mark();
                menu.style.display='block';
                var mw=menu.offsetWidth,mh=menu.offsetHeight;
                var x=Math.min(e.clientX,window.innerWidth-mw-8),y=Math.min(e.clientY,window.innerHeight-mh-8);
                menu.style.left=x+'px';menu.style.top=y+'px';
              });
              document.addEventListener('click',function(e){
                var it=e.target.closest?e.target.closest('.it'):null;
                if(it&&menu.contains(it)){
                  quality=it.getAttribute('data-q');
                  fetch('/quality?q='+quality);
                  menu.style.display='none';
                  return;
                }
                menu.style.display='none';
              });
              setTimeout(function(){hint.style.opacity='0';},4000);
            </script>
            </body></html>
        """.trimIndent()
    }
}
