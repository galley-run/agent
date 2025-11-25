package run.galley.agent

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import nl.clicqo.ext.getUUID

class WebSocketConnection(
  val vertx: Vertx,
  val config: JsonObject,
) {
  fun textMessageHandler(text: String) {
    println("[OutboundConnectionVerticle] Received message: $text")
    val obj = JsonObject(text)
    val action = obj.getString("action")
    val payload = obj.getJsonObject("payload")
    val replyTo = obj.getString("replyTo")
    val vesselEngineId = obj.getUUID("vesselEngineId")
    val requiredVesselEngineId = config.getJsonObject("galley").getUUID("vesselEngineId")
    if (vesselEngineId == null || vesselEngineId != requiredVesselEngineId) {
      println(
        "[OutboundConnectionVerticle] Received invalid vessel engine ID: " +
          "$vesselEngineId (instead of $requiredVesselEngineId)",
      )
      return@textMessageHandler
    }

    when (action) {
      "agent.setMaxParallel" -> {
        println("[OutboundConnectionVerticle] Publishing agent.setMaxParallel")
        vertx
          .eventBus()
          .publish("agent.ctrl.maxParallel", JsonObject().put("id", vesselEngineId.toString()).put("payload", payload))
      }

      else -> {
        println("[OutboundConnectionVerticle] Routing cmd.submit to action.$action")
        vertx.eventBus().send(
          "action.$action",
          JsonObject().put("id", vesselEngineId.toString()).put("payload", payload).put("replyTo", replyTo),
        )
      }
    }
  }
}
