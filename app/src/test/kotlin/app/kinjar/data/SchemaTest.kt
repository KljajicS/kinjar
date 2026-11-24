package app.kinjar.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaTest {
    @Test fun `account appearance migration preserves existing account fields`() {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(null, "CREATE TABLE account (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, currency TEXT NOT NULL, kind TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0, position INTEGER, excluded_from_goals INTEGER NOT NULL DEFAULT 0)", 0)
            driver.execute(null, "INSERT INTO account VALUES ('a', 'Cash', 'RSD', 'SPENDING', 1, 4, 1)", 0)
            app.kinjar.data.db.KinjarDb.Schema.migrate(driver, 18, 19)
            val account = app.kinjar.data.db.KinjarDb(driver).accountQueries.byId("a").executeAsOne()
            assertEquals("Cash", account.name)
            assertEquals(1L, account.archived)
            assertEquals(4L, account.position)
            assertEquals(1L, account.excluded_from_goals)
            assertEquals(null, account.icon)
            assertEquals(null, account.color)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `fresh database has no events and seq starts at zero`() {
        val db = testDb()
        assertEquals(0L, db.eventQueries.count().executeAsOne())
        assertEquals(0L, db.eventQueries.lastSeq("dev-1").executeAsOne().max_seq ?: 0L)
    }
}
