package ksqlc.sql

import kotlin.test.*

class FormatterTest {
    private fun f(s: String) = formatSql(s)

    @Test fun clausesGoOnTheirOwnLinesAndKeywordsAreUpperCased() {
        assertEquals("SELECT a\nFROM t\nWHERE x = 1\nORDER BY a", f("select a from t where x=1 order by a"))
    }

    @Test fun multiItemSelectListIsOneItemPerLine() {
        assertEquals("SELECT\n    a,\n    b AS bee,\n    count(*)\nFROM t\nGROUP BY a", f("select a, b as bee, count(*) from t group by a"))
    }

    @Test fun distinctStaysOnTheSelectLine() {
        assertEquals("SELECT DISTINCT\n    a,\n    b\nFROM t", f("select distinct a, b from t"))
    }

    @Test fun andOrBreakInWhereButNotInsideBetweenOrCase() {
        assertEquals(
            "SELECT a\nFROM t\nWHERE x BETWEEN 1 AND 2\n    AND y = CASE WHEN a AND b THEN 1 ELSE 2 END\n    OR z IS NULL",
            f("select a from t where x between 1 and 2 and y = case when a and b then 1 else 2 end or z is null"),
        )
    }

    @Test fun joinsStartLinesAndOnStaysInline() {
        assertEquals(
            "SELECT *\nFROM a\nLEFT OUTER JOIN b ON a.id = b.id\n    AND b.x > 1\nJOIN c ON c.id = a.id",
            f("select * from a left outer join b on a.id=b.id and b.x>1 join c on c.id=a.id"),
        )
    }

    @Test fun subqueriesAreIndentedAndClosedOnTheirOwnLine() {
        assertEquals(
            "SELECT *\nFROM t\nWHERE id IN (\n    SELECT id\n    FROM u\n    WHERE k = 1\n)",
            f("select * from t where id in (select id from u where k=1)"),
        )
    }

    @Test fun cteAndUnion() {
        assertEquals(
            "WITH x AS (\n    SELECT 1\n)\nSELECT *\nFROM x\nUNION ALL\nSELECT 2",
            f("with x as (select 1) select * from x union all select 2"),
        )
    }

    @Test fun updateSetBreaksWhenThereAreSeveralAssignments() {
        assertEquals("UPDATE t\nSET\n    a = 1,\n    b = 2\nWHERE id = 3", f("update t set a=1, b=2 where id=3"))
        assertEquals("UPDATE t\nSET a = 1\nWHERE id = 3", f("update t set a=1 where id=3"))
    }

    @Test fun insertValues() = assertEquals("INSERT INTO t(a, b)\nVALUES (1, 'x')", f("insert into t(a,b) values (1,'x')"))

    @Test fun deleteFromStaysOnTheDeleteLine() = assertEquals("DELETE FROM t\nWHERE a = 1", f("delete from t where a=1"))

    @Test fun functionsCastsAndUnaryMinusKeepTheirSpacing() {
        assertEquals("SELECT\n    coalesce(a, -1),\n    b::int,\n    a - 1,\n    x.*\nFROM t", f("select coalesce(a,-1),b::int,a-1,x.* from t"))
    }

    @Test fun stringsCommentsAndQuotedNamesAreUntouched() {
        val sql = "select 'it''s  select', \"Select  Me\" /* keep  this */ from t -- tail  comment\nwhere a=1"
        val out = f(sql)
        assertTrue("'it''s  select'" in out && "\"Select  Me\"" in out && "/* keep  this */" in out && "-- tail  comment" in out)
        assertEquals("SELECT\n    'it''s  select',\n    \"Select  Me\"", out.lines().take(3).joinToString("\n").substringBefore(" /*"))
    }

    @Test fun lineCommentNeverSwallowsFollowingCode() {
        val out = f("select 1 -- c\n; select 2")
        assertEquals(2, out.lines().count { it.trim().startsWith("SELECT") })
        assertTrue(out.lines().first { "-- c" in it }.trim().endsWith("-- c"))
    }

    @Test fun statementsAreSeparatedByOneBlankLine() = assertEquals("SELECT 1;\n\nSELECT 2;", f("select 1; select 2;"))

    @Test fun keywordsUsedAsNamesKeepTheirCase() = assertEquals("SELECT t.key\nFROM index", f("select t.key from index"))

    @Test fun dollarQuotedBodiesAreUntouched() {
        val sql = "create function f() returns int as \$\$ select  1 \$\$ language sql"
        assertTrue("\$\$ select  1 \$\$" in f(sql))
    }

    @Test fun formattingTwiceChangesNothing() {
        val samples = listOf(
            "select a, b from t where x=1 and y=2 order by a limit 3",
            "with x as (select 1) select * from x union all select 2",
            "select a -- c\n, b /* d */ from t\n\n\nwhere z in (select 1)",
            "-- lead\nselect 1; /* mid */ select 2;\n",
            "update t set a=1,b=2 where id=3; delete from t where a between 1 and 2",
            "insert into t(a,b) values (1,'x'),(2,'y') returning id",
            "select case when a then 1 else 2 end, -x, a::int from t left join u on t.id=u.id",
        )
        for (s in samples) { val once = f(s); assertEquals(once, f(once), "no idempotente para: $s") }
    }

    @Test fun emptyAndWhitespaceInputIsReturnedAsIs() { assertEquals("", f("")); assertEquals("  \n", f("  \n")) }
}
