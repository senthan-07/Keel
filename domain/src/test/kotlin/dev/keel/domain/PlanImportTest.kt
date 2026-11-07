package dev.keel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanImportTest {
    private val sample = """
        |# Learning Plan
        |
        |Intro with a [link](https://example.com).
        |
        |## Level 1: Basics
        |
        || Step | Read | Resource | Apply |
        ||---|---|---|---|
        || [ ] 1 | Your system | [*A Book*, An Author](https://example.com/a) | Write your version |
        || [x] 2 | Banking | [Site](https://example.com/b) | List 5 scams |
        |
        |## Habits
        |
        |- [ ] **Monthly:** spend 15 minutes checking.
        |- [x] Done thing
        |  - [ ] Nested thing
        |* [X] Star bullet
        |- not a checkbox
        |
        |```
        |- [ ] inside a code block
        |```
        |
        || Topic | Resource | Why |
        ||---|---|---|
        || [ ] Macroeconomics | [Khan](https://k) | Rates |
    """.trimMargin()

    private val plan = parsePlan("learning-plan.md", sample)

    @Test fun `the first top heading is the title`() {
        assertEquals("Learning Plan", plan.title)
    }

    @Test fun `a file with no heading is named after the file`() {
        assertEquals("First salary plan", parsePlan("first-salary_plan.md", "- [ ] a").title)
        assertEquals("Untitled plan", parsePlan(".md", "").title)
    }

    @Test fun `finds checkboxes in lists and tables, and nothing else`() {
        assertEquals(7, plan.items.size)
        assertFalse(plan.items.any { "code block" in it.title || "not a checkbox" in it.title })
    }

    @Test fun `table rows drop the step number and keep the useful cells`() {
        val row = plan.items[0]
        assertEquals("Your system: A Book, An Author", row.title)
        assertEquals("Write your version", row.detail)
        assertEquals("Level 1: Basics", row.section)
        assertFalse(row.done)
    }

    @Test fun `a table row with text in the checkbox cell keeps it`() {
        val row = plan.items.last()
        assertEquals("Macroeconomics: Khan", row.title)
        assertEquals("Rates", row.detail)
    }

    @Test fun `markdown is removed from list items`() {
        assertEquals("Monthly: spend 15 minutes checking.", plan.items[2].title)
        assertEquals("Habits", plan.items[2].section)
    }

    @Test fun `ticks are read in either case`() {
        assertTrue(plan.items[1].done)
        assertTrue(plan.items[3].done)
        assertTrue(plan.items[5].done)
        assertFalse(plan.items[4].done)
    }

    @Test fun `inline markdown becomes plain words`() {
        assertEquals("a b c d", plainText("[a](u) **b** *c* `d`"))
        assertEquals("2 * 3 * 4", plainText("2 * 3 * 4"))
    }

    @Test fun `re-import keeps progress, adds new items and removes old ones`() {
        val existing = listOf(
            ExistingItem(1, "s|keep", 100),
            ExistingItem(2, "s|gone", 100),
            ExistingItem(3, "s|tick in file", null),
        )
        val parsed = ParsedPlan(
            "T",
            listOf(
                PlanItem("s", "new", null, false),
                PlanItem("s", "Keep", null, false),
                PlanItem("s", "tick in file", null, true),
            ),
        )
        val merge = mergePlan(existing, parsed, now = 500)
        assertEquals(listOf(null, 1L, 3L), merge.rows.map { it.existingId })
        assertEquals(listOf(null, 100L, 500L), merge.rows.map { it.doneAt })
        assertEquals(listOf(2L), merge.removedIds)
    }

    @Test fun `identical lines are matched one by one, not merged`() {
        val item = PlanItem("s", "same", null, false)
        val existing = listOf(ExistingItem(1, item.key, 100), ExistingItem(2, item.key, null))
        val merge = mergePlan(existing, ParsedPlan("T", listOf(item, item, item)), 500)
        assertEquals(listOf(1L, 2L, null), merge.rows.map { it.existingId })
        assertTrue(merge.removedIds.isEmpty())
    }

    @Test fun `a re-import of the unchanged file changes nothing`() {
        val existing = plan.items.mapIndexed { i, it -> ExistingItem(i.toLong(), it.key, if (it.done) 1 else null) }
        val merge = mergePlan(existing, plan, 500)
        assertEquals(existing.map { it.id }, merge.rows.map { it.existingId })
        assertTrue(merge.removedIds.isEmpty())
    }

    @Test fun `export sets ticks and leaves everything else alone`() {
        val first = plan.items[0].key
        val out = planWithTicks(sample, listOf(first))
        assertTrue("| [x] 1 | Your system" in out)
        assertTrue("| [ ] 2 | Banking" in out)
        assertTrue("- [ ] Done thing" in out)
        assertTrue("- [ ] inside a code block" in out)
        assertTrue("Intro with a [link](https://example.com)." in out)
    }

    @Test fun `a clean export clears every tick, and exported files parse the same`() {
        val clean = planWithTicks(sample, emptyList())
        val parsed = parsePlan("x.md", clean)
        assertEquals(plan.items.map { it.key }, parsed.items.map { it.key })
        assertTrue(parsed.items.none { it.done })
    }

    @Test fun `exporting current progress round-trips`() {
        val done = plan.items.filter { it.done }.map { it.key }
        val parsed = parsePlan("x.md", planWithTicks(sample, done))
        assertEquals(plan.items.map { it.done }, parsed.items.map { it.done })
    }

    @Test fun `reading view skips table rules and keeps content`() {
        val lines = planLines(sample)
        assertTrue(lines.first() == PlanLine.Heading(1, "Learning Plan"))
        assertTrue(lines.contains(PlanLine.Paragraph("Intro with a link.")))
        assertTrue(lines.contains(PlanLine.Item("1 / Your system / A Book, An Author / Write your version")))
        assertFalse(lines.any { it is PlanLine.Item && it.text.contains("---") })
    }

    @Test fun `categories are guessed from the name, and can stay Other`() {
        assertEquals(PlanCategory.MONEY, guessCategory("first-salary-money-plan.md", "x"))
        assertEquals(PlanCategory.HEALTH, guessCategory("x.md", "Health and habits"))
        assertEquals(PlanCategory.OTHER, guessCategory("guitar.md", "Learn guitar"))
    }

    @Test fun `money state uses the latest amount and reports stalling`() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals(MoneyGoalState(12_400, 24, 21), moneyState(50_000, 12_400, 0, 21 * day))
        assertEquals(MoneyGoalState(0, 0, null), moneyState(50_000, null, null, 0))
        assertEquals(100, moneyState(100, 500, 0, 0).percent)
        assertEquals(0, moneyState(0, 500, 0, 0).percent)
        assertNull(moneyState(10, null, null, 0).idleDays)
    }
}
