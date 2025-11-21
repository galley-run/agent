# Galley Agent Helm Chart

This Helm chart deploys the Galley Agent on a Kubernetes cluster.

## Prerequisites

- Kubernetes 1.19+
- Helm 3.0+

## Installation

### Install from local directory

```bash
helm install galley-agent ./helm/galley-agent -n galley --create-namespace
```

### Install with custom values

```bash
helm install galley-agent ./helm/galley-agent -n galley --create-namespace -f my-values.yaml
```

## Configuration

The following table lists the configurable parameters of the Galley Agent chart and their default values.

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of replicas | `1` |
| `image.repository` | Image repository | `reg.galley.dev:5999/galley-run/agent` |
| `image.tag` | Image tag | `dev` |
| `image.pullPolicy` | Image pull policy | `Always` |
| `galley.agentId` | Galley Agent ID | `f273e641-4733-45c6-90ae-f0d9aa5019f6` |
| `galley.platformWsUrl` | Galley platform WebSocket URL | `wss://api.galley.dev` |
| `galley.rootCA` | Root CA certificate for SSL | See values.yaml |
| `resources.limits.cpu` | CPU limit | `1000m` |
| `resources.limits.memory` | Memory limit | `512Mi` |
| `resources.requests.cpu` | CPU request | `1000m` |
| `resources.requests.memory` | Memory request | `512Mi` |
| `podDisruptionBudget.enabled` | Enable PodDisruptionBudget | `true` |
| `podDisruptionBudget.minAvailable` | Minimum available pods | `1` |
| `debug.enabled` | Enable debug port | `true` |
| `debug.port` | Debug port | `5005` |

## Upgrading

```bash
helm upgrade galley-agent ./helm/galley-agent -n galley
```

## Uninstalling

```bash
helm uninstall galley-agent -n galley
```

## Examples

### Production configuration

```yaml
# production-values.yaml
replicaCount: 3

image:
  tag: "1.0.0"
  pullPolicy: IfNotPresent

galley:
  agentId: "your-production-agent-id"
  platformWsUrl: "wss://api.galley.production"
  hostAliases: []  # Remove dev host aliases

debug:
  enabled: false

resources:
  limits:
    cpu: 2000m
    memory: 1Gi
  requests:
    cpu: 1000m
    memory: 512Mi

podDisruptionBudget:
  minAvailable: 2
```

Install with:
```bash
helm install galley-agent ./helm/galley-agent -n galley --create-namespace -f production-values.yaml
```
