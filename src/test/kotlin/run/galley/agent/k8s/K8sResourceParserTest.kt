package run.galley.agent.k8s

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class K8sResourceParserTest {
  private val parser = K8sResourceParser()

  @Test
  fun `should build core API resource path for namespaced resource`() {
    val resource =
      JsonObject()
        .put("apiVersion", "v1")
        .put("kind", "Pod")
        .put(
          "metadata",
          JsonObject()
            .put("name", "test-pod")
            .put("namespace", "default"),
        )

    val result = parser.build(resource)

    assertEquals(
      "https://kubernetes.default.svc/api/v1/namespaces/default/pods",
      result.postUrl,
    )
    assertEquals(
      "https://kubernetes.default.svc/api/v1/namespaces/default/pods/test-pod",
      result.patchUrl,
    )
    assertEquals(resource, result.body)
  }

  @Test
  fun `should build core API resource path for cluster-scoped resource`() {
    val resource =
      JsonObject()
        .put("apiVersion", "v1")
        .put("kind", "Namespace")
        .put(
          "metadata",
          JsonObject()
            .put("name", "test-namespace"),
        )

    val result = parser.build(resource)

    assertEquals(
      "https://kubernetes.default.svc/api/v1/namespaces",
      result.postUrl,
    )
    assertEquals(
      "https://kubernetes.default.svc/api/v1/namespaces/test-namespace",
      result.patchUrl,
    )
  }

  @Test
  fun `should build grouped API resource path for namespaced resource`() {
    val resource =
      JsonObject()
        .put("apiVersion", "apps/v1")
        .put("kind", "Deployment")
        .put(
          "metadata",
          JsonObject()
            .put("name", "test-deployment")
            .put("namespace", "production"),
        )

    val result = parser.build(resource)

    assertEquals(
      "https://kubernetes.default.svc/apis/apps/v1/namespaces/production/deployments",
      result.postUrl,
    )
    assertEquals(
      "https://kubernetes.default.svc/apis/apps/v1/namespaces/production/deployments/test-deployment",
      result.patchUrl,
    )
  }

  @Test
  fun `should default namespace to default when not specified`() {
    val resource =
      JsonObject()
        .put("apiVersion", "v1")
        .put("kind", "Service")
        .put(
          "metadata",
          JsonObject()
            .put("name", "test-service"),
        )

    val result = parser.build(resource)

    assertEquals(
      "https://kubernetes.default.svc/api/v1/namespaces/default/services",
      result.postUrl,
    )
    assertEquals(
      "https://kubernetes.default.svc/api/v1/namespaces/default/services/test-service",
      result.patchUrl,
    )
  }

  @Test
  fun `should handle various resource types`() {
    val testCases =
      mapOf(
        "ConfigMap" to "configmaps",
        "Secret" to "secrets",
        "Ingress" to "ingresses",
        "StatefulSet" to "statefulsets",
        "DaemonSet" to "daemonsets",
        "Job" to "jobs",
        "CronJob" to "cronjobs",
      )

    testCases.forEach { (kind, expectedResourceType) ->
      val resource =
        JsonObject()
          .put("apiVersion", "v1")
          .put("kind", kind)
          .put(
            "metadata",
            JsonObject()
              .put("name", "test-resource"),
          )

      val result = parser.build(resource)
      assertEquals(
        "https://kubernetes.default.svc/api/v1/namespaces/default/$expectedResourceType",
        result.postUrl,
      )
    }
  }

  @Test
  fun `should handle cluster-scoped resources without namespace`() {
    val clusterResources =
      listOf(
        "Node",
        "PersistentVolume",
        "ClusterRole",
        "ClusterRoleBinding",
        "StorageClass",
      )

    clusterResources.forEach { kind ->
      val resource =
        JsonObject()
          .put("apiVersion", "v1")
          .put("kind", kind)
          .put(
            "metadata",
            JsonObject()
              .put("name", "test-resource"),
          )

      val result = parser.build(resource)
      assertEquals(
        "https://kubernetes.default.svc/api/v1/${kind.lowercase()}s",
        result.postUrl,
      )
    }
  }

  @Test
  fun `should throw exception when kind is missing`() {
    val resource =
      JsonObject()
        .put("apiVersion", "v1")
        .put(
          "metadata",
          JsonObject()
            .put("name", "test-resource"),
        )

    assertThrows(IllegalArgumentException::class.java) {
      parser.build(resource)
    }
  }

  @Test
  fun `should throw exception when metadata is missing`() {
    val resource =
      JsonObject()
        .put("apiVersion", "v1")
        .put("kind", "Pod")

    assertThrows(IllegalArgumentException::class.java) {
      parser.build(resource)
    }
  }

  @Test
  fun `should throw exception when name is missing`() {
    val resource =
      JsonObject()
        .put("apiVersion", "v1")
        .put("kind", "Pod")
        .put("metadata", JsonObject())

    assertThrows(IllegalArgumentException::class.java) {
      parser.build(resource)
    }
  }

  @Test
  fun `should handle custom resource with group and version`() {
    val resource =
      JsonObject()
        .put("apiVersion", "networking.k8s.io/v1")
        .put("kind", "Ingress")
        .put(
          "metadata",
          JsonObject()
            .put("name", "test-ingress")
            .put("namespace", "default"),
        )

    val result = parser.build(resource)

    assertEquals(
      "https://kubernetes.default.svc/apis/networking.k8s.io/v1/namespaces/default/ingresses",
      result.postUrl,
    )
    assertEquals(
      "https://kubernetes.default.svc/apis/networking.k8s.io/v1/namespaces/default/ingresses/test-ingress",
      result.patchUrl,
    )
  }

  @Test
  fun `should use custom base URL when provided`() {
    val customParser = K8sResourceParser(baseUrl = "https://custom.k8s.cluster")
    val resource =
      JsonObject()
        .put("apiVersion", "v1")
        .put("kind", "Pod")
        .put(
          "metadata",
          JsonObject()
            .put("name", "test-pod")
            .put("namespace", "default"),
        )

    val result = customParser.build(resource)

    assertEquals(
      "https://custom.k8s.cluster/api/v1/namespaces/default/pods",
      result.postUrl,
    )
  }
}
