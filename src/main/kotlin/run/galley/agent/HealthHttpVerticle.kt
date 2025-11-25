package run.galley.agent

import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait

class HealthHttpVerticle : CoroutineVerticle() {
  override suspend fun start() {
    val port = config.getInteger("healthPort", 8080)
    val router =
      Router.router(vertx).apply {
        get("/healthz").handler { it.response().end("ok") }
      }
    vertx
      .createHttpServer(
        HttpServerOptions()
          .setPort(port),
      ).requestHandler(router)
      .listen()
      .coAwait()
  }
}
