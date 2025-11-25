package run.galley.agent

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.http.ServerWebSocketHandshake
import io.vertx.core.http.WebSocket
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import java.util.UUID

class WebSocketServer(
  val vertx: Vertx,
  val config: JsonObject,
) {
  private val logger = LoggerFactory.getLogger(this::class.java)

  var ws: WebSocket? = null

  private var websocketConnection: WebSocketConnection? = null

  fun handshakeHandler(): Handler<ServerWebSocketHandshake> {
    return Handler { handshake ->
      val ip = handshake.remoteAddress().host()
      logger.info("[WS] Handshake request from $ip on path ${handshake.path()}")

      if (handshake.path() != "/agents/connect") {
        logger.info("[WS] Rejected: Invalid path ${handshake.path()}")
        handshake.reject(404)
        return@Handler
      }

      // basic header-based auth (dev). In prod vervang je dit door mTLS of JWT-check op LB of hier.
      val authz = handshake.headers().get("Authorization")
      if (authz != null && !authz.startsWith("Bearer ")) {
        logger.warn("[WS] Rejected: Invalid Authorization header")
        handshake.reject(401)
        return@Handler
      }

      logger.info("[WS] Handshake accepted from $ip")
      handshake.accept()
    }
  }

  fun connectionHandler(): Handler<ServerWebSocket> =
    Handler { ws ->
      val vesselEngineId = ws.headers().get("X-Vessel-Engine-Id") ?: UUID.randomUUID().toString()
      val connectionId = ws.headers().get("X-Session-Id") ?: UUID.randomUUID().toString()
      val remoteAddress = ws.remoteAddress()

      logger.info("[WS] New connection: vesselEngineId=$vesselEngineId, connectionId=$connectionId from $remoteAddress")

      websocketConnection = WebSocketConnection(vertx, config)

      ws.textMessageHandler(websocketConnection!!::textMessageHandler)

      this.ws = ws

      ws.shutdownHandler {
        websocketConnection = null
        logger.info("[WS] All connections closed for vesselEngineId=$vesselEngineId, session removed")
      }

      ws.closeHandler {
        websocketConnection = null
        logger.info("[WS] All connections closed for vesselEngineId=$vesselEngineId, session removed")
      }

      ws.exceptionHandler { error ->
        logger.error("[WS] Exception for connectionId=$connectionId: ${error.message}")
        websocketConnection = null
        try {
          ws.close()
        } catch (_: Throwable) {
        }
      }
    }
}
