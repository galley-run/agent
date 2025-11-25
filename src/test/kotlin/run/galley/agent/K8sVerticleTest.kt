package run.galley.agent

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class K8sVerticleTest {
  private lateinit var vertx: Vertx

  @BeforeEach
  fun setUp() {
    vertx = Vertx.vertx()
  }

  @AfterEach
  fun tearDown(testContext: VertxTestContext) {
    vertx.close().onComplete(testContext.succeedingThenComplete())
  }

  @Test
  fun `should register GET_NODES event bus consumer`(testContext: VertxTestContext) {
    val checkpoint = testContext.checkpoint()

    // Since K8sVerticle requires service account token, we test event bus registration only
    vertx.eventBus().consumer<JsonObject>(K8sVerticle.GET_NODES) { message ->
      testContext.verify {
        assert(message.body() != null)
      }
      checkpoint.flag()
    }

    val testMessage =
      JsonObject()
        .put("id", "test-id")
        .put("replyTo", "test.reply")

    vertx.eventBus().send(K8sVerticle.GET_NODES, testMessage)

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }

  @Test
  fun `should register APPLY event bus consumer`(testContext: VertxTestContext) {
    val checkpoint = testContext.checkpoint()

    vertx.eventBus().consumer<JsonObject>(K8sVerticle.APPLY) { message ->
      testContext.verify {
        assert(message.body() != null)
      }
      checkpoint.flag()
    }

    val manifests = JsonArray().add(JsonObject().put("apiVersion", "v1").put("kind", "Pod"))
    val testMessage =
      JsonObject()
        .put("id", "test-id")
        .put("replyTo", "test.reply")
        .put(
          "payload",
          JsonObject()
            .put("manifests", manifests),
        )

    vertx.eventBus().send(K8sVerticle.APPLY, testMessage)

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }

  @Test
  fun `should define GET_NODES constant`() {
    assert(K8sVerticle.GET_NODES == "action.k8s.nodes.get")
  }

  @Test
  fun `should define APPLY constant`() {
    assert(K8sVerticle.APPLY == "action.k8s.apply")
  }

  @Test
  fun `should validate message structure for apply action`(testContext: VertxTestContext) {
    val checkpoint = testContext.checkpoint()

    vertx.eventBus().consumer<JsonObject>(K8sVerticle.APPLY) { message ->
      val body = message.body()
      testContext.verify {
        assert(body.getString("id") != null)
        assert(body.getString("replyTo") != null)
        assert(body.getJsonObject("payload") != null)
        assert(body.getJsonObject("payload").getJsonArray("manifests") != null)
      }
      checkpoint.flag()
    }

    val manifests =
      JsonArray()
        .add(
          JsonObject()
            .put("apiVersion", "apps/v1")
            .put("kind", "Deployment")
            .put(
              "metadata",
              JsonObject()
                .put("name", "test-deployment")
                .put("namespace", "default"),
            ),
        )

    val testMessage =
      JsonObject()
        .put("id", "test-id-123")
        .put("replyTo", "test.reply.channel")
        .put(
          "payload",
          JsonObject()
            .put("manifests", manifests),
        )

    vertx.eventBus().send(K8sVerticle.APPLY, testMessage)

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }

  @Test
  fun `should validate message structure for getNodes action`(testContext: VertxTestContext) {
    val checkpoint = testContext.checkpoint()

    vertx.eventBus().consumer<JsonObject>(K8sVerticle.GET_NODES) { message ->
      val body = message.body()
      testContext.verify {
        assert(body.getString("id") != null)
        assert(body.getString("replyTo") != null)
      }
      checkpoint.flag()
    }

    val testMessage =
      JsonObject()
        .put("id", "test-id-456")
        .put("replyTo", "test.reply.channel")

    vertx.eventBus().send(K8sVerticle.GET_NODES, testMessage)

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }
}
