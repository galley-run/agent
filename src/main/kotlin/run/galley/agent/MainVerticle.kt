package run.galley.agent

import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait

class MainVerticle : CoroutineVerticle() {
  override suspend fun start() {
    super.start()

    vertx.deployVerticle(OutboundConnectionVerticle(), deploymentOptionsOf(config)).coAwait()
    vertx.deployVerticle(K8sVerticle(), deploymentOptionsOf(config)).coAwait()
    vertx.deployVerticle(HealthHttpVerticle(), deploymentOptionsOf(config)).coAwait()
  }
}
