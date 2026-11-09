package app.nageune.car

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 주소 → 좌표, 좌표 → 주소.
 *
 * NaviHelper에는 장소 검색 API가 없어서(경로 요청만 됨) 먼저 안드로이드 기본 Geocoder를 쓰고,
 * 없거나 못 찾으면 OpenStreetMap Nominatim으로 찾는다. Nominatim은 1초에 1번 이하·앱 식별 User-Agent가
 * 이용 조건이라 사용자가 버튼을 눌렀을 때만 부른다. 고객 이름은 보내지 않고 주소만 보낸다.
 * 정식 서비스에서는 카카오·네이버 지도 API나 Pleos 장소 검색으로 바꾼다.
 */
class Geo(context: Context) {
    data class Place(val lat: Double, val lng: Double, val label: String, val source: String)

    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.KOREA) else null
    val systemAvailable: Boolean get() = geocoder != null

    suspend fun search(query: String): Place? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        android(query) ?: nominatim(query)
    }

    suspend fun reverse(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        androidReverse(lat, lng) ?: nominatimReverse(lat, lng)
    }

    @Suppress("DEPRECATION")
    private fun android(q: String): Place? = runCatching {
        val a = geocoder?.getFromLocationName(q, 1)?.firstOrNull() ?: return null
        Place(a.latitude, a.longitude, a.getAddressLine(0) ?: q, "기기 지오코더")
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun androidReverse(lat: Double, lng: Double): String? = runCatching {
        geocoder?.getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0)
    }.getOrNull()

    private fun nominatim(q: String): Place? = runCatching {
        val url = "$BASE/search?format=jsonv2&limit=1&countrycodes=kr&accept-language=ko&addressdetails=1&q=" +
            URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(get(url))
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        Place(o.getString("lat").toDouble(), o.getString("lon").toDouble(), shortLabel(o) ?: q, "OpenStreetMap")
    }.getOrNull()

    private fun nominatimReverse(lat: Double, lng: Double): String? = runCatching {
        shortLabel(JSONObject(get("$BASE/reverse?format=jsonv2&accept-language=ko&zoom=16&lat=$lat&lon=$lng")))
    }.getOrNull()

    /** "성남시 분당구 정자동"처럼 시·구·동까지만. */
    private fun shortLabel(o: JSONObject): String? {
        val a = o.optJSONObject("address") ?: return o.optString("display_name").ifBlank { null }
        val parts = listOf("city", "county", "borough", "city_district", "quarter", "suburb", "neighbourhood", "road")
            .mapNotNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
            .distinct()
            .take(3)
        return parts.joinToString(" ").ifBlank { o.optString("display_name").ifBlank { null } }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "Nageune/0.1 (Pleos Connect app contest demo)")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    private companion object {
        const val BASE = "https://nominatim.openstreetmap.org"
    }
}
