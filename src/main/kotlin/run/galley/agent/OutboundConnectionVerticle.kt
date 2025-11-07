package run.galley.agent

import io.vertx.core.buffer.Buffer
import io.vertx.core.http.WebSocket
import io.vertx.core.http.WebSocketClient
import io.vertx.core.http.WebSocketClientOptions
import io.vertx.core.http.WebSocketConnectOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.core.net.PemTrustOptions
import io.vertx.kotlin.coroutines.CoroutineEventBusSupport
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

class OutboundConnectionVerticle :
  CoroutineVerticle(),
  CoroutineEventBusSupport {
  private var ws: WebSocket? = null
  private lateinit var client: WebSocketClient
  private var reconnectTimerId: Long? = null
  private var backoff = 1000L
  private lateinit var wsUrl: String
  private lateinit var agentId: String

  companion object {
    const val PLATFORM_OUT = "outbound-connection"
  }

  override suspend fun start() {
    super.start()
    println("[OutboundConnectionVerticle] Starting...")

    wsUrl = "${config.getString("platform_ws_url", "wss://api.galley.run")}/agents/connect"
    agentId = config.getString("agent_id", UUID.randomUUID().toString())
    println("[OutboundConnectionVerticle] WS URL: $wsUrl")
    println("[OutboundConnectionVerticle] Agent ID: $agentId")

    val opts = WebSocketClientOptions().setSsl(wsUrl.startsWith("wss://"))
    config.getString("tls_ca_pem")?.let { opts.trustOptions = PemTrustOptions().addCertValue(Buffer.buffer(it)) }
    if (config.getString("tls_client_cert_pem") != null && config.getString("tls_client_key_pem") != null) {
      opts.keyCertOptions =
        PemKeyCertOptions()
          .addCertValue(Buffer.buffer(config.getString("tls_client_cert_pem")))
          .addKeyValue(Buffer.buffer(config.getString("tls_client_key_pem")))
    }
    // TODO: FIX THIS TRUST ALL MARK
    opts.isTrustAll = true

    client = vertx.createWebSocketClient(opts)
    println("[OutboundConnectionVerticle] WebSocket client created")

    vertx.eventBus().coConsumer(PLATFORM_OUT) { message ->
      println("[OutboundConnectionVerticle] Sending message to platform: ${message.body()}")
      ws?.writeTextMessage(message.body())?.coAwait()
    }

    // Start initial connection attempt
    scheduleConnect()
  }

  private fun scheduleConnect() {
    reconnectTimerId = vertx.setTimer(backoff) {
      launch {
        attemptConnect()
      }
    }
  }

  private suspend fun attemptConnect() {
    try {
      println("[OutboundConnectionVerticle] Attempting to connect...")
      connect(wsUrl, agentId)
      backoff = 1000L
      println("[OutboundConnectionVerticle] Connected successfully")
    } catch (e: Throwable) {
      println("[OutboundConnectionVerticle] Connection failed: ${e.message}")
      backoff = (backoff * 2).coerceAtMost(30_000L)
      scheduleConnect()
    }
  }

  override suspend fun stop() {
    reconnectTimerId?.let { vertx.cancelTimer(it) }
    ws?.close()
    super.stop()
  }

  private suspend fun connect(
    url: String,
    agentId: String,
  ) {
    val uri = URI(url)
    println(
      "[OutboundConnectionVerticle] Connecting to ${uri.host}:${if (uri.port > 0) {
        uri.port
      } else if (uri.scheme == "wss") {
        443
      } else {
        80
      }}",
    )
    val wsc =
      client
        .connect(
          WebSocketConnectOptions()
            .setHost(uri.host)
            .setPort(
              if (uri.port > 0) {
                uri.port
              } else if (uri.scheme == "wss") {
                443
              } else {
                80
              },
            ).setSsl(uri.scheme == "wss")
            .setURI(uri.rawPath + (if (uri.rawQuery != null) "?${uri.rawQuery}" else ""))
            .putHeader("X-Agent-Id", agentId)
            .apply {
              config.getString("BOOTSTRAP_TOKEN")?.let { putHeader("Authorization", "Bearer $it") }
            },
        ).coAwait()

    this.ws = wsc
    println("[OutboundConnectionVerticle] WebSocket connection established")

    // Handle close and schedule reconnect
    wsc.closeHandler {
      println("[OutboundConnectionVerticle] WebSocket closed, scheduling reconnect")
      scheduleConnect()
    }

    // hello
    val hello =
      JsonObject()
        .put("type", "agent.hello")
        .put("id", UUID.randomUUID().toString())
        .put(
          "payload",
          JsonObject()
            .put("agentId", agentId)
            .put("capabilities", JsonArray().add("k8s.getNodes").add("k8s.apply"))
            .put("credits", config.getInteger("AGENT_MAX_PARALLEL", 4)),
        ).encode()
    println("[OutboundConnectionVerticle] Sending hello message: $hello")
    wsc.writeTextMessage(hello)

    // ping/pong
    var lastPong = System.currentTimeMillis()
    wsc.pongHandler {
      println("[OutboundConnectionVerticle] Received pong")
      lastPong = System.currentTimeMillis()
    }
    vertx.setPeriodic(30_000) {
      try {
        println("[OutboundConnectionVerticle] Sending ping")
        wsc.writePing(Buffer.buffer())
      } catch (_: Exception) {
      }
      if (System.currentTimeMillis() - lastPong > 90_000) {
        println("[OutboundConnectionVerticle] No pong received for 90s, closing connection")
        try {
          wsc.close()
        } catch (_: Exception) {
        }
      }
    }

    // WS → EB: routeer commands naar K8sVerticle
    wsc.textMessageHandler { text ->
      println("[OutboundConnectionVerticle] Received message: $text")
      val obj = JsonObject(text)
      when (obj.getString("type")) {
        "cmd.submit" -> {
          println("[OutboundConnectionVerticle] Routing cmd.submit to agent.cmd.exec")
          vertx.eventBus().send("agent.cmd.exec", text)
        }
        "agent.setMaxParallel" -> {
          println("[OutboundConnectionVerticle] Publishing agent.setMaxParallel")
          vertx.eventBus().publish("agent.ctrl.maxParallel", obj)
        }
        else -> {
          println("[OutboundConnectionVerticle] Unknown message type: ${obj.getString("type")}")
        }
      }
    }
  }
}
