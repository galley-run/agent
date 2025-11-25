package nl.clicqo.ext

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class UuidExtTest {
  @Test
  fun `should parse valid UUID string`() {
    val uuid = UUID.randomUUID()
    val json = JsonObject().put("id", uuid.toString())

    val result = json.getUUID("id")

    assertEquals(uuid, result)
  }

  @Test
  fun `should return null for invalid UUID string`() {
    val json = JsonObject().put("id", "invalid-uuid")

    val result = json.getUUID("id")

    assertNull(result)
  }

  @Test
  fun `should return null for missing key`() {
    val json = JsonObject()

    val result = json.getUUID("id")

    assertNull(result)
  }

  @Test
  fun `should return default value when key is missing and default is provided`() {
    val defaultUuid = UUID.randomUUID()
    val json = JsonObject()

    val result = json.getUUID("id", defaultUuid)

    assertEquals(defaultUuid, result)
  }

  @Test
  fun `should return null when value is null`() {
    val json = JsonObject().putNull("id")

    val result = json.getUUID("id")

    assertNull(result)
  }

  @Test
  fun `should handle multiple UUIDs in same object`() {
    val uuid1 = UUID.randomUUID()
    val uuid2 = UUID.randomUUID()
    val json =
      JsonObject()
        .put("id1", uuid1.toString())
        .put("id2", uuid2.toString())

    val result1 = json.getUUID("id1")
    val result2 = json.getUUID("id2")

    assertEquals(uuid1, result1)
    assertEquals(uuid2, result2)
  }

  @Test
  fun `should parse UUID with uppercase letters`() {
    val uuid = UUID.randomUUID()
    val json = JsonObject().put("id", uuid.toString().uppercase())

    val result = json.getUUID("id")

    assertEquals(uuid, result)
  }

  @Test
  fun `applyIf should apply block when condition is true`() {
    val value = "test"

    val result =
      value.applyIf(true) {
        uppercase()
      }

    assertEquals("TEST", result)
  }

  @Test
  fun `applyIf should not apply block when condition is false`() {
    val value = "test"

    val result =
      value.applyIf(false) {
        uppercase()
      }

    assertEquals("test", result)
  }

  @Test
  fun `applyIf should work with complex objects`() {
    val json = JsonObject().put("key", "value")

    val result =
      json.applyIf(true) {
        put("newKey", "newValue")
      }

    assertEquals("value", result.getString("key"))
    assertEquals("newValue", result.getString("newKey"))
  }

  @Test
  fun `applyIf should not modify object when condition is false`() {
    val json = JsonObject().put("key", "value")

    val result =
      json.applyIf(false) {
        put("newKey", "newValue")
      }

    assertEquals("value", result.getString("key"))
    assertNull(result.getString("newKey"))
  }
}
