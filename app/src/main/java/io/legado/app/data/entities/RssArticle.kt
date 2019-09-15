package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "rssArticles")
data class RssArticle(
    var origin: String = "",
    @PrimaryKey
    var guid: String? = null,
    var title: String? = null,
    var author: String? = null,
    var link: String? = null,
    var pubDate: String? = null,
    var description: String? = null,
    var content: String? = null,
    var image: String? = null,
    var categories: MutableList<String> = mutableListOf()
)