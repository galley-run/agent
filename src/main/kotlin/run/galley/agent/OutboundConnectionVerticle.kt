package run.galley.agent

import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
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
import nl.clicqo.ext.getUUID
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
  private lateinit var vesselEngineId: String

  companion object {
    const val PLATFORM_OUT = "outbound-connection"
  }

  override suspend fun start() {
    super.start()
    println("[OutboundConnectionVerticle] Starting...")

    val caPath = "/etc/ssl/certs/galley-rootCA.pem"

    wsUrl = "${config.getJsonObject("galley", JsonObject()).getString("platformWsUrl", "wss://api.galley.run")}/agents/connect"
    vesselEngineId =
      config.getJsonObject("galley", JsonObject()).getString("vesselEngineId", System.getProperty("GALLEY_AGENT_ID"))
        ?: throw Exception("vesselEngineId missing")
    println("[OutboundConnectionVerticle] WS URL: $wsUrl")
    println("[OutboundConnectionVerticle] Vessel Engine ID: $vesselEngineId")

    val opts = WebSocketClientOptions().setSsl(wsUrl.startsWith("wss://")).setTrustOptions(PemTrustOptions().addCertPath(caPath))
    val tlsConfig = config.getJsonObject("tls", JsonObject())
    tlsConfig.getString("caPem")?.let { opts.trustOptions = PemTrustOptions().addCertValue(Buffer.buffer(it)) }
    if (tlsConfig.getString("clientCertPem") != null && tlsConfig.getString("clientKeyPem") != null) {
      opts.keyCertOptions =
        PemKeyCertOptions()
          .addCertValue(Buffer.buffer(tlsConfig.getString("clientCertPem")))
          .addKeyValue(Buffer.buffer(tlsConfig.getString("clientKeyPem")))
    }
    // TODO: FIX THIS TRUST ALL MARK
    opts.isTrustAll = true

    client = vertx.createWebSocketClient(opts)
    println("[OutboundConnectionVerticle] WebSocket client created")

    vertx.eventBus().coConsumer(PLATFORM_OUT, handler = ::sendWsMessage)

    // Start initial connection attempt
    scheduleConnect()
  }

  private fun scheduleConnect() {
    reconnectTimerId =
      vertx.setTimer(backoff) {
        launch {
          attemptConnect()
        }
      }
  }

  private suspend fun attemptConnect() {
    try {
      println("[OutboundConnectionVerticle] Attempting to connect...")
      connect(wsUrl, vesselEngineId)
      backoff = 1000L
      println("[OutboundConnectionVerticle] Connected successfully")
    } catch (e: Throwable) {
      println("[OutboundConnectionVerticle] Connection failed: ${e.message}")
      backoff = (backoff * 2).coerceAtMost(30_000L)
      scheduleConnect()
    }
  }

  private suspend fun sendWsMessage(message: Message<JsonObject>) {
    val json = message.body().encode()
    println("[OutboundConnectionVerticle] Sending message to platform: $json")
    ws
      ?.writeTextMessage(json)
      ?.coAwait()
  }

  override suspend fun stop() {
    reconnectTimerId?.let { vertx.cancelTimer(it) }
    ws?.close()
    super.stop()
  }

  private suspend fun connect(
    url: String,
    vesselEngineId: String,
  ) {
    val uri = URI(url)
    println(
      "[OutboundConnectionVerticle] Connecting to ${uri.host}:${
        if (uri.port > 0) {
          uri.port
        } else if (uri.scheme == "wss") {
          443
        } else {
          80
        }
      }",
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
            .putHeader("X-Vessel-Engine-Id", vesselEngineId)
            .apply {
              config.getJsonObject("galley", JsonObject()).getString("agentToken")?.let { putHeader("Authorization", "Bearer $it") }
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
            .put("vesselEngineId", vesselEngineId)
            .put("capabilities", JsonArray().add("k8s.getNodes").add("k8s.apply"))
            .put("credits", config.getInteger("maxParallel", 4)),
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
      val action = obj.getString("action")
      val payload = obj.getJsonObject("payload")
      val replyTo = obj.getString("replyTo")
      val vesselEngineId = obj.getUUID("vesselEngineId")
      val requiredVesselEngineId = config.getJsonObject("galley").getUUID("vesselEngineId")
      if (vesselEngineId == null || vesselEngineId != requiredVesselEngineId) {
        println("[OutboundConnectionVerticle] Received invalid vessel engine ID: $vesselEngineId (instead of $requiredVesselEngineId)")
        return@textMessageHandler
      }

      when (action) {
        "agent.setMaxParallel" -> {
          println("[OutboundConnectionVerticle] Publishing agent.setMaxParallel")
          vertx.eventBus().publish("agent.ctrl.maxParallel", JsonObject().put("payload", payload))
        }

        else -> {
          println("[OutboundConnectionVerticle] Routing cmd.submit to agent.cmd.exec")
          vertx.eventBus().send("action.$action", JsonObject().put("payload", payload).put("replyTo", replyTo))
        }
      }
    }
  }
}
