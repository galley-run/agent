package run.galley.agent.k8s

import io.vertx.core.json.JsonObject
import kotlin.math.log

class K8sResourceParser(
  private val baseUrl: String = "https://kubernetes.default.svc",
) {
  fun build(resource: JsonObject): K8sApiCall {
    val apiVersion = resource.getString("apiVersion") ?: "v1"
    val kind = resource.getString("kind") ?: throw IllegalArgumentException("Kind is required")
    val metadata = resource.getJsonObject("metadata") ?: throw IllegalArgumentException("Metadata is required")
    val name = metadata.getString("name") ?: throw IllegalArgumentException("Name is required")
    val namespace = metadata.getString("namespace") ?: "default"

    val postApiPath = buildApiPath(apiVersion, kind, namespace)
    val patchApiPath = buildApiPath(apiVersion, kind, namespace, name)

    println(postApiPath)
    println(patchApiPath)

    return K8sApiCall(
      postUrl = "$baseUrl$postApiPath",
      patchUrl = "$baseUrl$patchApiPath",
      body = resource,
    )
  }

  private fun buildApiPath(
    apiVersion: String,
    kind: String,
    namespace: String,
    name: String? = null,
  ): String {
    val (group, version) = parseApiVersion(apiVersion)
    val resourceType = kindToResourceType(kind)

    return when {
      // Core API (v1)
      group.isEmpty() -> {
        if (isNamespaced(kind)) {
          "/api/$version/namespaces/$namespace/$resourceType${name?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""}"
        } else {
          "/api/$version/$resourceType${name?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""}"
        }
      }

      else -> {
        if (isNamespaced(kind)) {
          "/apis/$group/$version/namespaces/$namespace/$resourceType${name?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""}"
        } else {
          "/apis/$group/$version/$resourceType${name?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: ""}"
        }
      }
    }
  }

  /**
   * Parse apiVersion naar group en version
   */
  private fun parseApiVersion(apiVersion: String): Pair<String, String> =
    if (apiVersion.contains("/")) {
      val parts = apiVersion.split("/", limit = 2)
      parts[0] to parts[1]
    } else {
      "" to apiVersion
    }

  /**
   * Converteer Kind naar resource type (meervoud, lowercase)
   */
  private fun kindToResourceType(kind: String): String =
    when (kind.lowercase()) {
      "service" -> "services"
      "deployment" -> "deployments"
      "pod" -> "pods"
      "configmap" -> "configmaps"
      "secret" -> "secrets"
      "ingress" -> "ingresses"
      "namespace" -> "namespaces"
      "persistentvolumeclaim" -> "persistentvolumeclaims"
      "persistentvolume" -> "persistentvolumes"
      "serviceaccount" -> "serviceaccounts"
      "daemonset" -> "daemonsets"
      "statefulset" -> "statefulsets"
      "job" -> "jobs"
      "cronjob" -> "cronjobs"
      "horizontalpodautoscaler" -> "horizontalpodautoscalers"
      else -> "${kind.lowercase()}s"
    }

  /**
   * Check of een resource type namespaced is
   */
  private fun isNamespaced(kind: String): Boolean {
    val clusterScopedResources =
      setOf(
        "Namespace",
        "Node",
        "PersistentVolume",
        "ClusterRole",
        "ClusterRoleBinding",
        "StorageClass",
        "CustomResourceDefinition",
      )
    return !clusterScopedResources.contains(kind)
  }
}
