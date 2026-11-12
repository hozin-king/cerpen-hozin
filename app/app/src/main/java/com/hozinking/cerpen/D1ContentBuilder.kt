package com.hozinking.cerpen

import org.json.JSONArray
import org.json.JSONObject

/** Dekripsi satu cipher base64 jadi teks UTF-8; null bila gagal. */
typealias D1Decrypt = (cipherB64: String) -> String?

/**
 * Rakit ulang JSON konten (cerpen.json / komik.json / news.json) dari
 * baris D1 (dump `/cerpen/all`) — bentuknya PERSIS seperti file JSON
 * yang dibaca ContentLoader, supaya DataManager/Reader tidak berubah.
 *
 * Yang D1 TIDAK simpan (dan tidak perlu): `date` cerpen/komik (file
 * JSON lama juga tidak punya field itu — parser default ""), `slots`
 * (bookkeeping generator). Yang dipulihkan dari URL: nama file cover /
 * panel (basename URL .enc tanpa ".enc").
 *
 * Murni org.json — tanpa dependensi Android — supaya bisa di-unit-test
 * di JVM. Dekripsi disuntik lewat lambda (pemanggil memakai
 * ContentCrypto + kunci native; tes memakai kunci test).
 *
 * Fail-closed: SATU episode/panel gagal didekripsi -> buildAll null
 * (jangan simpan konten setengah — pemanggil jatuh ke jalur GitHub).
 */
object D1ContentBuilder {

    /** Hasil rebuild: 3 teks JSON + peta jalur gambar -> URL .enc repo. */
    data class Built(
        val cerpenJson: String,
        val komikJson: String,
        val newsJson: String,
        /** "covers/cover_01.jpg" -> URL .enc lengkap (cover + panel komik). */
        val imageUrls: Map<String, String>
    )

    /** Nama file dari URL .enc (".../covers/cover_01.jpg.enc" -> "cover_01.jpg"). */
    fun fileNameFromEncUrl(url: String): String {
        val base = url.substringAfterLast("/").substringBefore("?")
        return if (base.endsWith(".enc")) base.dropLast(4) else base
    }

    /** Angka di akhir id ("cerpen-30" -> 30); 0 bila tidak ada angka. */
    private fun trailingNumber(id: String): Int {
        val digits = id.takeLastWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    private fun parseStringArray(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull {
                arr.optString(it, "").takeIf { s -> s.isNotEmpty() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** details_json: objek / larik / string mentah — apa adanya. */
    private fun parseDetails(raw: String): Any? {
        if (raw.isBlank()) return ""
        return try {
            JSONObject(raw)
        } catch (e1: Exception) {
            try {
                JSONArray(raw)
            } catch (e2: Exception) {
                raw
            }
        }
    }

    fun buildAll(dump: JSONObject, decrypt: D1Decrypt): Built? {
        val cerpen = buildCerpen(
            dump.optJSONArray("series") ?: return null,
            dump.optJSONArray("episodes") ?: return null,
            decrypt
        ) ?: return null
        val komik = buildKomik(
            dump.optJSONArray("komik") ?: return null,
            dump.optJSONArray("komik_episodes") ?: return null,
            decrypt
        ) ?: return null
        val news = buildNews(dump.optJSONArray("news") ?: return null)
        val imageUrls = HashMap<String, String>()
        imageUrls.putAll(cerpen.second)
        imageUrls.putAll(komik.second)
        return Built(cerpen.first, komik.first, news, imageUrls)
    }

    /** Pair(teks cerpen.json, peta cover). */
    private fun buildCerpen(
        seriesArr: JSONArray,
        episodesArr: JSONArray,
        decrypt: D1Decrypt
    ): Pair<String, Map<String, String>>? {
        // Kelompokkan episode per (series, season), urut no.
        val bySeries = HashMap<String, HashMap<Int, ArrayList<JSONObject>>>()
        for (i in 0 until episodesArr.length()) {
            val e = episodesArr.optJSONObject(i) ?: continue
            val sid = e.optString("series_id", "")
            if (sid.isEmpty()) continue
            val season = e.optInt("season", 1)
            val list = bySeries.getOrPut(sid) { HashMap() }.getOrPut(season) { ArrayList() }
            list.add(e)
        }
        val imageUrls = HashMap<String, String>()
        val outArr = JSONArray()
        // Urutan file lama: id menaik (cerpen-01..53).
        val seriesList = (0 until seriesArr.length())
            .mapNotNull { seriesArr.optJSONObject(it) }
            .sortedBy { trailingNumber(it.optString("id", "")) }
        for (s in seriesList) {
            val id = s.optString("id", "")
            if (id.isEmpty()) continue
            val coverUrl = s.optString("cover_url", "")
            val cover = fileNameFromEncUrl(coverUrl)
            if (coverUrl.isNotEmpty() && cover.isNotEmpty()) {
                imageUrls["covers/$cover"] = coverUrl
            }
            val seasonsArr = JSONArray()
            val seasons = bySeries[id] ?: emptyMap()
            for (season in seasons.keys.sorted()) {
                val eps = seasons[season]!!.sortedBy { it.optInt("no", 0) }
                val epsArr = JSONArray()
                for (e in eps) {
                    val isi = decrypt(e.optString("isi_cipher", "")) ?: return null
                    epsArr.put(JSONObject().apply {
                        put("no", e.optInt("no", 0))
                        put("judul", e.optString("judul", ""))
                        put("isi", isi)
                    })
                }
                seasonsArr.put(JSONObject().apply {
                    put("season", season)
                    put("episodes", epsArr)
                })
            }
            outArr.put(JSONObject().apply {
                put("id", id)
                put("judul", s.optString("judul", ""))
                put("genre", s.optString("genre", ""))
                put("deskripsi", s.optString("deskripsi", ""))
                put("cover", cover)
                put("seasons", seasonsArr)
            })
        }
        return JSONObject().apply { put("cerpen", outArr) }.toString() to imageUrls
    }

    /** Pair(teks komik.json, peta cover + panel). */
    private fun buildKomik(
        komikArr: JSONArray,
        episodesArr: JSONArray,
        decrypt: D1Decrypt
    ): Pair<String, Map<String, String>>? {
        val byKomik = HashMap<String, ArrayList<JSONObject>>()
        for (i in 0 until episodesArr.length()) {
            val e = episodesArr.optJSONObject(i) ?: continue
            val kid = e.optString("komik_id", "")
            if (kid.isEmpty()) continue
            byKomik.getOrPut(kid) { ArrayList() }.add(e)
        }
        val imageUrls = HashMap<String, String>()
        val outArr = JSONArray()
        val komikList = (0 until komikArr.length())
            .mapNotNull { komikArr.optJSONObject(it) }
            .sortedBy { trailingNumber(it.optString("id", "")) }
        for (k in komikList) {
            val id = k.optString("id", "")
            if (id.isEmpty()) continue
            val coverUrl = k.optString("cover_url", "")
            val cover = fileNameFromEncUrl(coverUrl)
            if (coverUrl.isNotEmpty() && cover.isNotEmpty()) {
                imageUrls["komik/covers/$cover"] = coverUrl
            }
            val epsArr = JSONArray()
            val eps = (byKomik[id] ?: emptyList()).sortedBy { it.optInt("no", 0) }
            for (e in eps) {
                val panelsJson = decrypt(e.optString("panels_cipher", "")) ?: return null
                val panelsArr = try {
                    JSONArray(panelsJson)
                } catch (ex: Exception) {
                    return null
                }
                val outPanels = JSONArray()
                for (j in 0 until panelsArr.length()) {
                    val p = panelsArr.optJSONObject(j) ?: continue
                    val imgUrl = p.optString("image_url", "")
                    val image = fileNameFromEncUrl(imgUrl)
                    if (imgUrl.isNotEmpty() && image.isNotEmpty()) {
                        imageUrls["komik/panels/$image"] = imgUrl
                    }
                    outPanels.put(JSONObject().apply {
                        put("image", image)
                        put("teks", p.optString("teks", ""))
                    })
                }
                epsArr.put(JSONObject().apply {
                    put("no", e.optInt("no", 0))
                    put("judul", e.optString("judul", ""))
                    put("panels", outPanels)
                })
            }
            outArr.put(JSONObject().apply {
                put("id", id)
                put("judul", k.optString("judul", ""))
                put("genre", k.optString("genre", ""))
                put("deskripsi", k.optString("deskripsi", ""))
                put("cover", cover)
                put("episodes", epsArr)
            })
        }
        return JSONObject().apply { put("komik", outArr) }.toString() to imageUrls
    }

    private fun buildNews(newsArr: JSONArray): String {
        val outArr = JSONArray()
        for (i in 0 until newsArr.length()) {
            val n = newsArr.optJSONObject(i) ?: continue
            // images_json = image_urls eksternal (sumber asli); image =
            // entri pertama (pola file lama: image == images[0]).
            val images = parseStringArray(n.optString("images_json", ""))
            val imgs = if (images.isNotEmpty()) images
                else listOfNotNull(n.optString("image_url", "").takeIf { it.isNotEmpty() })
            outArr.put(JSONObject().apply {
                put("title", n.optString("title", ""))
                put("summary", n.optString("summary", ""))
                put("source", n.optString("source_name", ""))
                put("date", n.optString("published_at", ""))
                put("image", imgs.firstOrNull() ?: JSONObject.NULL)
                put("images", JSONArray(imgs))
                put("image_urls", JSONArray(imgs))
                put("details", parseDetails(n.optString("details_json", "")))
                put("added_at", n.optString("added_at", ""))
                put("topic", n.optString("topic", ""))
                put("url", n.optString("source_url", ""))
            })
        }
        return JSONObject().apply { put("articles", outArr) }.toString()
    }
}
