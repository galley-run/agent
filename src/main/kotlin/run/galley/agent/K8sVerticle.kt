package run.galley.agent

import io.vertx.core.eventbus.Message
import io.vertx.core.file.FileSystemException
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.PemTrustOptions
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.CoroutineEventBusSupport
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import run.galley.agent.OutboundConnectionVerticle.Companion.PLATFORM_OUT
import run.galley.agent.k8s.K8sResourceParser
import java.util.UUID

class K8sVerticle :
  CoroutineVerticle(),
  CoroutineEventBusSupport {
  private lateinit var k8s: WebClient
  private lateinit var token: String
  private var maxParallel = 4
  private var inflight = 0

  companion object {
    const val GET_NODES = "action.k8s.nodes.get"
    const val APPLY = "action.k8s.apply"
  }

  override suspend fun start() {
    val saPath = "/var/run/secrets/kubernetes.io/serviceaccount"
    try {
      token =
        vertx
          .fileSystem()
          .readFile("$saPath/token")
          .coAwait()
          .toString()
          .trim()
    } catch (e: FileSystemException) {
      throw Exception("Service account token could not be read", e)
    }
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

//    vertx.eventBus().coConsumer<JsonObject>("agent.ctrl.maxParallel") { message ->
//      val obj = message.body()
//      val newMax = obj.getJsonObject("payload")?.getInteger("value") ?: maxParallel
//      maxParallel = newMax
//    }

    vertx.eventBus().coConsumer(GET_NODES, handler = ::getNodes)
    vertx.eventBus().coConsumer(APPLY, handler = ::apply)

    println("K8sVerticle started")
  }

  private suspend fun getNodes(message: Message<JsonObject>) {
    val obj = message.body()
    val id = obj.getString("id")

    val r =
      k8s
        .get("/api/v1/nodes")
        .putHeader("Authorization", "Bearer $token")
        .send()
        .coAwait()
    sendDone(id, obj.getString("replyTo"), r.statusCode() in 200..299, r.body())
  }

  private suspend fun apply(message: Message<JsonObject>) {
    val obj = message.body()
    val id = obj.getString("id")
    val payload = obj.getJsonObject("payload", JsonObject())
    val manifests = payload.getJsonArray("manifests", JsonArray())

    repeat(manifests.count()) {
      val manifest = manifests.getJsonObject(it)
      val resource = K8sResourceParser().build(manifest)

      val createResponse =
        k8s
          .postAbs(resource.postUrl)
          //          .patch(443, "kubernetes.default.svc", "/apis/apps/v1/namespaces/$ns/deployments")
          .putHeader("Authorization", "Bearer $token")
          .putHeader("Content-Type", "application/json")
          .putHeader("Accept", "application/json")
          .putHeader("X-Applying-Agent", "galley-agent")
          .sendJsonObject(resource.body)
          .coAwait()

      val finalResponse =
        if (createResponse?.statusCode() == 409) {
          k8s
            .patchAbs("${resource.patchUrl}?fieldManager=galley-agent")
            //          .patch(443, "kubernetes.default.svc", "/apis/apps/v1/namespaces/$ns/deployments")
            .putHeader("Authorization", "Bearer $token")
            .putHeader("Content-Type", "application/json")
            .putHeader("Accept", "application/json")
            .putHeader("X-Applying-Agent", "galley-agent")
            .sendJsonObject(resource.body)
            .coAwait()
        } else {
          createResponse
        }

      sendDone(id, obj.getString("replyTo"), finalResponse.statusCode() in 200..299, finalResponse.body())
    }
  }

  private fun sendDone(
    id: String,
    replyTo: String,
    ok: Boolean,
    result: Any?,
  ) {
    inflight -= 1
    // 1 credit teruggeven aan platform via control channel
    val credit =
      JsonObject()
        .put("type", "agent.credits")
        .put("id", UUID.randomUUID().toString())
        .put("payload", JsonObject().put("delta", +1))
    vertx.eventBus().publish(PLATFORM_OUT, credit)

    val done =
      JsonObject()
        .put("type", "cmd.done")
        .put("action", replyTo)
        .put("id", id)
        .put("ok", ok)
        .put("result", result)
    vertx.eventBus().publish(PLATFORM_OUT, done)
  }
}
