package chekhov

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.*

import java.net.InetSocketAddress
import java.nio.file.{Files, Path}

/** Serve a directory of static files (Phase 1 dogfood without Vite). */
object StaticFileServer:

  def serve(dir: Path, host: String = "127.0.0.1", port: Int = 0)(using
      Trace
  ): ZIO[Scope, ChekhovError, AppServer] =
    for
      bound <- ZIO.acquireRelease {
        ZIO
          .attemptBlocking {
            val server = HttpServer.create(new InetSocketAddress(host, port), 0)
            server.createContext(
              "/",
              (ex: HttpExchange) =>
                val rel  = Option(ex.getRequestURI.getPath).getOrElse("/").stripPrefix("/")
                val file =
                  if rel.isEmpty then dir.resolve("index.html")
                  else dir.resolve(rel).normalize()
                if !file.startsWith(dir.normalize()) || !Files.isRegularFile(file) then
                  val msg = "Not found".getBytes
                  ex.sendResponseHeaders(404, msg.length)
                  ex.getResponseBody.write(msg)
                else
                  val bytes = Files.readAllBytes(file)
                  val ctype =
                    val name = file.getFileName.toString.toLowerCase
                    if name.endsWith(".html") || name.endsWith(".htm") then "text/html; charset=utf-8"
                    else if name.endsWith(".js") || name.endsWith(".mjs") then "text/javascript; charset=utf-8"
                    else if name.endsWith(".css") then "text/css; charset=utf-8"
                    else if name.endsWith(".json") then "application/json; charset=utf-8"
                    else "application/octet-stream"
                  ex.getResponseHeaders.add("Content-Type", ctype)
                  // ES modules need CORS-like freedom when served locally.
                  ex.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
                  ex.sendResponseHeaders(200, bytes.length)
                  ex.getResponseBody.write(bytes)
                end if
                ex.close(),
            )
            server.setExecutor(null)
            server.start()
            server
          }
          .mapError(e => ChekhovError.Serve("static server", Some(e)))
      } { s =>
        ZIO.attempt(s.stop(0)).orDie
      }
      addr = bound.getAddress
      url  = s"http://${addr.getHostString}:${addr.getPort}"
    yield new AppServer:
      val baseUrl = url

  def layer(dir: Path): ZLayer[Any, ChekhovError, AppServer] =
    ZLayer.scoped(serve(dir))
end StaticFileServer
