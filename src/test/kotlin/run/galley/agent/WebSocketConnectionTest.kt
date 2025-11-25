package run.galley.agent

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class WebSocketConnectionTest {
  private lateinit var vertx: Vertx
  private lateinit var config: JsonObject
  private lateinit var connection: WebSocketConnection
  private val vesselEngineId = UUID.randomUUID()

  @BeforeEach
  fun setUp() {
    vertx = Vertx.vertx()
    config =
      JsonObject()
        .put(
          "galley",
          JsonObject()
            .put("vesselEngineId", vesselEngineId.toString()),
        )
    connection = WebSocketConnection(vertx, config)
  }

  @AfterEach
  fun tearDown(testContext: VertxTestContext) {
    vertx.close().onComplete(testContext.succeedingThenComplete())
  }

  @Test
  fun `should route action to event bus when vesselEngineId matches`(testContext: VertxTestContext) {
    val checkpoint = testContext.checkpoint()
    val action = "k8s.nodes.get"
    val replyTo = "test.reply"
    val payload = JsonObject().put("key", "value")

    vertx.eventBus().consumer<JsonObject>("action.$action") { message ->
      val body = message.body()
      testContext.verify {
        assert(body.getString("id") == vesselEngineId.toString())
        assert(body.getJsonObject("payload").getString("key") == "value")
        assert(body.getString("replyTo") == replyTo)
      }
      checkpoint.flag()
    }

    val text =
      JsonObject()
        .put("action", action)
        .put("replyTo", replyTo)
        .put("payload", payload)
        .put("vesselEngineId", vesselEngineId.toString())
        .encode()

    connection.textMessageHandler(text)

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }

  @Test
  fun `should ignore message with invalid vesselEngineId`(testContext: VertxTestContext) {
    val invalidVesselEngineId = UUID.randomUUID()
    val action = "k8s.nodes.get"

    vertx.eventBus().consumer<JsonObject>("action.$action") { _ ->
      testContext.failNow("Should not receive message with invalid vesselEngineId")
    }

    val text =
      JsonObject()
        .put("action", action)
        .put("replyTo", "test.reply")
        .put("payload", JsonObject())
        .put("vesselEngineId", invalidVesselEngineId.toString())
        .encode()

    connection.textMessageHandler(text)

    vertx.setTimer(500) {
      testContext.completeNow()
    }

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }

  @Test
  fun `should ignore message with missing vesselEngineId`(testContext: VertxTestContext) {
    val action = "k8s.nodes.get"

    vertx.eventBus().consumer<JsonObject>("action.$action") { _ ->
      testContext.failNow("Should not receive message with missing vesselEngineId")
    }

    val text =
      JsonObject()
        .put("action", action)
        .put("replyTo", "test.reply")
        .put("payload", JsonObject())
        .encode()

    connection.textMessageHandler(text)

    vertx.setTimer(500) {
      testContext.completeNow()
    }

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }

  @Test
  fun `should handle agent setMaxParallel action`(testContext: VertxTestContext) {
    val checkpoint = testContext.checkpoint()
    val payload = JsonObject().put("value", 10)

    vertx.eventBus().consumer<JsonObject>("agent.ctrl.maxParallel") { message ->
      val body = message.body()
      testContext.verify {
        assert(body.getString("id") == vesselEngineId.toString())
        assert(body.getJsonObject("payload").getInteger("value") == 10)
      }
      checkpoint.flag()
    }

    val text =
      JsonObject()
        .put("action", "agent.setMaxParallel")
        .put("payload", payload)
        .put("vesselEngineId", vesselEngineId.toString())
        .encode()

    connection.textMessageHandler(text)

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }

  @Test
  fun `should route k8s apply action to event bus`(testContext: VertxTestContext) {
    val checkpoint = testContext.checkpoint()
    val action = "k8s.apply"
    val replyTo = "test.reply"
    val manifests =
      JsonObject()
        .put(
          "manifests",
          listOf(
            JsonObject()
              .put("apiVersion", "v1")
              .put("kind", "Pod"),
          ),
        )

    vertx.eventBus().consumer<JsonObject>("action.$action") { message ->
      val body = message.body()
      testContext.verify {
        assert(body.getString("id") == vesselEngineId.toString())
        assert(body.getJsonObject("payload") != null)
        assert(body.getString("replyTo") == replyTo)
      }
      checkpoint.flag()
    }

    val text =
      JsonObject()
        .put("action", action)
        .put("replyTo", replyTo)
        .put("payload", manifests)
        .put("vesselEngineId", vesselEngineId.toString())
        .encode()

    connection.textMessageHandler(text)

    testContext.awaitCompletion(2, TimeUnit.SECONDS)
  }
}
