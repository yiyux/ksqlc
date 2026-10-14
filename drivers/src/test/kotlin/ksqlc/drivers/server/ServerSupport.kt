package ksqlc.drivers.server

import ksqlc.core.DataSource
import ksqlc.core.DbKind
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import java.sql.DriverManager

/** Integration tests need Docker; without it the whole class is skipped, not failed. */
fun assumeDocker() = assumeTrue("Docker no disponible: se omiten los tests de integración", runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))

class ServerFixture(val ds: DataSource, val password: String?)

fun fixture(kind: DbKind, url: String, user: String, password: String, setup: List<String>): ServerFixture {
    DriverManager.getConnection(url, user, password).use { c -> c.createStatement().use { st -> setup.forEach { st.execute(it) } } }
    return ServerFixture(DataSource("it", "it", kind, url, user), password)
}
