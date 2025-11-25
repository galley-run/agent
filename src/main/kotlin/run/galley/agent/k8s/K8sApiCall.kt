package run.galley.agent.k8s

import io.vertx.core.json.JsonObject

data class K8sApiCall(
  val postUrl: String,
  val patchUrl: String,
  val body: JsonObject,
)
