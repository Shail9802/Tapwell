package app.tapwell

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class DriveSetup(private val token: String) {
    data class Replica(val fileId: String, val name: String, val changes: SetupChangeSet)

    fun account(): SyncAccount {
        val uri = Uri.parse("$API/about").buildUpon()
            .appendQueryParameter("fields", "user(emailAddress,permissionId)").build()
        val user = JSONObject(request("GET", uri.toString())).getJSONObject("user")
        return SyncAccount(user.getString("emailAddress"), user.getString("permissionId"))
    }

    fun download(): List<Replica> {
        val replicas = mutableListOf<Replica>()
        var page: String? = null
        do {
            val uri = Uri.parse("$API/files").buildUpon()
                .appendQueryParameter("spaces", "appDataFolder")
                .appendQueryParameter("q", "name contains '$PREFIX' and trashed = false")
                .appendQueryParameter("fields", "nextPageToken,files(id,name)")
                .appendQueryParameter("pageSize", "100")
            page?.let { uri.appendQueryParameter("pageToken", it) }
            val result = JSONObject(request("GET", uri.build().toString()))
            val files = result.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val name = file.getString("name")
                if (!name.startsWith(PREFIX)) continue
                val id = file.getString("id")
                val document = JSONObject(request("GET", "$API/files/${Uri.encode(id)}?alt=media"))
                require(document.getInt("schema") == 1) { "Unsupported Tapwell backup format" }
                replicas.add(Replica(id, name, readSetupChanges(document.getJSONObject("changes"))))
            }
            page = result.optString("nextPageToken").takeIf(String::isNotEmpty)
        } while (page != null)
        return replicas
    }

    fun upload(device: String, replicas: List<Replica>, changes: SetupChangeSet) {
        val name = "$PREFIX$device.json"
        val previous = replicas.firstOrNull { it.name == name }
        if (changes == previous?.changes || changes.isEmpty()) return
        val document = JSONObject().put("schema", 1).put("changes", writeSetupChanges(changes)).toString()
        if (previous != null) {
            request("PATCH", "$UPLOAD/${Uri.encode(previous.fileId)}?uploadType=media", "application/json", document)
        } else {
            val boundary = UUID.randomUUID().toString()
            val metadata = JSONObject().put("name", name)
                .put("parents", JSONArray().put("appDataFolder")).toString()
            val multipart = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$document\r\n--$boundary--\r\n"
            request("POST", "$UPLOAD?uploadType=multipart", "multipart/related; boundary=$boundary", multipart)
        }
    }

    private fun request(method: String, address: String, type: String? = null, data: String? = null): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (data != null) {
                val bytes = data.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw DriveSetupError(status)
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > MAX_BYTES) throw IOException("Tapwell backup exceeds the size limit")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val PREFIX = "tapwell-setup-"
        const val MAX_BYTES = 1024 * 1024
    }
}

internal class DriveSetupError(val status: Int) : IOException("Drive request failed (HTTP $status)")
