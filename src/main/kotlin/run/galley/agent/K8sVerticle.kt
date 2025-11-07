package run.galley.agent

import io.vertx.core.buffer.Buffer
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.CoroutineEventBusSupport
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import java.io.File
import java.util.UUID

class K8sVerticle :
  CoroutineVerticle(),
  CoroutineEventBusSupport {
  private lateinit var k8s: WebClient
  private lateinit var token: String
  private var maxParallel = 4
  private var inflight = 0

  override suspend fun start() {
    maxParallel = config.getInteger("AGENT_MAX_PARALLEL", 4)

    val saPath = "/var/run/secrets/kubernetes.io/serviceaccount"
    token = File("$saPath/token").readText().trim()
    val k8sCA = PemTrustOptions().addCertPath("$saPath/ca.crt")
    k8s =
      WebClient.create(
        vertx,
        WebClientOptions()
          .setSsl(true)
          .setTrustOptions(k8sCA)
          .setDefaultHost("kubernetes.default.svc")
          .setDefaultPort(443),
      )

    vertx.eventBus().coConsumer<String>("agent.ctrl.maxParallel") { msg ->
      val obj = JsonObject(msg.body())
      val newMax = obj.getJsonObject("payload")?.getInteger("value") ?: maxParallel
      maxParallel = newMax
    }

    vertx.eventBus().coConsumer<String>("agent.cmd.exec") { m ->
      if (inflight >= maxParallel) {
        // laat gateway de opdracht in queue houden; hier sturen we geen ack
        return@coConsumer
      }
      inflight += 1
      val obj = JsonObject(m.body())
      val id = obj.getString("id")
      val payload = obj.getJsonObject("payload")
      val kind = payload.getString("kind")

      when (kind) {
        "k8s.getNodes" -> {
          val r =
            k8s
              .get("/api/v1/nodes")
              .putHeader("Authorization", "Bearer $token")
              .send()
              .coAwait()
          sendDone(id, r.statusCode() in 200..299, r.body())
        }

        "k8s.apply" -> {
          val ns = payload.getString("namespace", "galley")
          val manifest = payload.getString("manifest") ?: ""
          val r =
            k8s
              .patch(443, "kubernetes.default.svc", "/apis/apps/v1/namespaces/$ns/deployments")
              .putHeader("Authorization", "Bearer $token")
              .putHeader("Content-Type", "application/apply-patch+yaml")
              .putHeader("Accept", "application/json")
              .putHeader("X-Applying-Agent", "galley-agent")
              .sendBuffer(Buffer.buffer(manifest))
              .coAwait()
          sendDone(id, r.statusCode() in 200..299, r.bodyAsString())
        }

        else -> sendDone(id, ok = false, result = mapOf("error" to "unknown kind $kind"))
      }
    }
  }

  private fun sendDone(
    id: String,
    ok: Boolean,
    result: Any?,
  ) {
    inflight -= 1
    // 1 credit teruggeven aan platform via control channel
    val credit =
      Json.encode(
        mapOf(
          "type" to "agent.credits",
          "id" to UUID.randomUUID().toString(),
          "payload" to mapOf("delta" to +1),
        ),
      )
    vertx.eventBus().publish("agent.ctrl.tx", credit)

    val done = Json.encode(mapOf("type" to "cmd.done", "id" to id, "ok" to ok, "result" to result))
    vertx.eventBus().publish("agent.ctrl.tx", done)
  }
}
