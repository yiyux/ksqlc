package ksqlc.core

import kotlinx.serialization.Serializable

@Serializable
enum class DbKind { POSTGRES, MYSQL, MARIADB, SQLITE, MSSQL, ORACLE }

@Serializable
data class DataSource(
    val id: String,
    val name: String,
    val kind: DbKind,
    val url: String,
    val user: String? = null,
)

data class Column(val name: String, val typeName: String, val nullable: Boolean = true)

data class ResultTable(val columns: List<Column>, val rows: List<List<Any?>>)
