package com.sanya.assistant

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListParseTest {

    private val today = LocalDate.of(2026, 10, 16)       // a Friday

    private fun p(s: String) = ListParse.parse(s, today)

    private fun add(list: String, vararg items: String, due: LocalDate? = null) =
        ListParse.Cmd.Add(list, items.toList(), due)

    // ---------------- adding ----------------

    @Test
    fun addToANamedList() {
        assertEquals(add("shopping", "milk"), p("add milk to my shopping list"))
        assertEquals(add("grocery", "rice"), p("put rice on the grocery list"))
        assertEquals(add("trip packing", "passport"), p("add passport to my trip packing list"))
    }

    @Test
    fun addToTheToDoList() {
        assertEquals(add("todo", "milk"), p("add milk to my list"))
        assertEquals(add("todo", "call ravi"), p("add call ravi to my to-do list"))
        assertEquals(add("todo", "submit report"), p("add submit report to my tasks"))
    }

    @Test
    fun addSeveralThings() {
        assertEquals(add("shopping", "milk", "eggs"), p("add milk and eggs to my shopping list"))
        assertEquals(add("shopping", "milk", "eggs", "bread"), p("add milk, eggs, bread to my shopping list"))
    }

    @Test
    fun addWithADueDate() {
        assertEquals(
            add("todo", "call the bank", due = LocalDate.of(2026, 10, 16)),
            p("add call the bank to my list by friday")
        )
        assertEquals(
            add("todo", "pay rent", due = LocalDate.of(2026, 10, 17)),
            p("add pay rent to my tasks due tomorrow")
        )
        assertEquals(
            add("todo", "call mom", due = LocalDate.of(2026, 10, 18)),
            p("add call mom on sunday to my list")
        )
        assertEquals(
            add("todo", "insurance", due = LocalDate.of(2027, 3, 12)),
            p("add insurance to my list on 12 march")
        )
    }

    @Test
    fun addATask() {
        assertEquals(
            add("todo", "call the bank", due = LocalDate.of(2026, 10, 17)),
            p("add task call the bank tomorrow")
        )
        assertEquals(add("todo", "call the bank"), p("add a task to call the bank"))
    }

    @Test
    fun theWordToInsideAnItemStaysInTheItem() {
        assertEquals(add("todo", "talk to ravi"), p("add talk to ravi to my list"))
    }

    // ---------------- reading ----------------

    @Test
    fun readingAList() {
        assertEquals(ListParse.Cmd.Read("shopping"), p("read my shopping list"))
        assertEquals(ListParse.Cmd.Read("shopping"), p("what's on my shopping list"))
        assertEquals(ListParse.Cmd.Read("todo"), p("what is on my list"))
        assertEquals(ListParse.Cmd.Read("todo"), p("read my tasks"))
        assertEquals(ListParse.Cmd.Read("packing"), p("what do i have on my packing list"))
        assertEquals(ListParse.Cmd.Read("shopping"), p("my shopping list"))
    }

    @Test
    fun whatIsDue() {
        assertEquals(ListParse.Cmd.Due("today"), p("what's due today"))
        assertEquals(ListParse.Cmd.Due("today"), p("what is due"))
        assertEquals(ListParse.Cmd.Due("tomorrow"), p("what tasks are due tomorrow"))
        assertEquals(ListParse.Cmd.Due("this week"), p("what's due this week"))
        assertEquals(ListParse.Cmd.Due("today"), p("what do i have to do today"))
        assertEquals(ListParse.Cmd.Due("today"), p("what's on my to-do list today"))
        assertEquals(ListParse.Cmd.Due("overdue"), p("what's overdue"))
        assertEquals(ListParse.Cmd.Due("overdue"), p("show my overdue tasks"))
    }

    // ---------------- finishing, removing ----------------

    @Test
    fun finishingThings() {
        assertEquals(ListParse.Cmd.Done("milk", null, true), p("mark milk as done"))
        assertEquals(ListParse.Cmd.Done("milk", "shopping", true), p("mark milk as done on my shopping list"))
        assertEquals(ListParse.Cmd.Done("milk", null, true), p("tick off milk"))
        assertEquals(ListParse.Cmd.Done("eggs", "shopping", true), p("cross off eggs from my shopping list"))
        assertEquals(ListParse.Cmd.Done("eggs", null, true), p("i bought the eggs"))
        assertEquals(ListParse.Cmd.Done("report", null, true), p("i finished the report"))
    }

    @Test
    fun puttingThingsBack() {
        assertEquals(ListParse.Cmd.Done("milk", null, false), p("mark milk as not done"))
        assertEquals(ListParse.Cmd.Done("milk", null, false), p("unmark milk"))
        assertEquals(ListParse.Cmd.Done("milk", null, false), p("milk is not done"))
    }

    @Test
    fun removing() {
        assertEquals(ListParse.Cmd.Remove("milk", "shopping"), p("remove milk from my shopping list"))
        assertEquals(ListParse.Cmd.Remove("milk", "todo"), p("remove milk from my list"))
        assertEquals(ListParse.Cmd.Remove("eggs", "shopping"), p("take eggs off my shopping list"))
        assertEquals(ListParse.Cmd.Remove("call ravi", "todo"), p("delete call ravi from my to-do list"))
    }

    // ---------------- whole lists ----------------

    @Test
    fun clearing() {
        assertEquals(ListParse.Cmd.Clear("shopping"), p("clear my shopping list"))
        assertEquals(ListParse.Cmd.Clear("todo"), p("clear my list"))
        assertEquals(ListParse.Cmd.ClearDone("shopping"), p("clear done items from my shopping list"))
        assertEquals(ListParse.Cmd.ClearDone("shopping"), p("tidy up my shopping list"))
    }

    @Test
    fun creatingAndDeleting() {
        assertEquals(ListParse.Cmd.Create("packing"), p("create a list called packing"))
        assertEquals(ListParse.Cmd.Create("packing"), p("create a packing list"))
        assertEquals(ListParse.Cmd.Create("trip"), p("start a list for the trip"))
        assertEquals(ListParse.Cmd.Delete("packing"), p("delete my packing list"))
        assertEquals(ListParse.Cmd.Delete("packing"), p("delete the list called packing"))
        assertEquals(ListParse.Cmd.Show, p("what lists do i have"))
    }

    // ---------------- sentences that belong to something else ----------------

    @Test
    fun routinesStudyTimeAndOtherThingsAreNotLists() {
        assertNull(p("add morning walk to my daily routine"))
        assertNull(p("add yoga to my weekly tasks"))
        assertNull(p("add 45 minutes to my japanese list"))
        assertNull(p("read my weekly tasks"))
        assertNull(p("call ravi"))
        assertNull(p("what time is it"))
        assertNull(p("remind me to buy milk"))
        assertNull(p("put this song on my playlist"))
        assertNull(p("what is the capital of india"))
        assertNull(p("who called me"))
    }

    @Test
    fun spendingIsLeftForTheMoneyTracker() {
        assertNull(p("bought tea for 20"))
        assertNull(p("i bought tea for 20 rupees"))
        assertNull(p("bought a shirt for 500"))
    }

    // ---------------- due dates ----------------

    @Test
    fun readingADueDate() {
        assertEquals(LocalDate.of(2026, 10, 17), ListParse.dueFrom("tomorrow", today))
        assertEquals(LocalDate.of(2026, 10, 23), ListParse.dueFrom("next friday", today))
        assertEquals(LocalDate.of(2026, 10, 19), ListParse.dueFrom("in 3 days", today))
        assertEquals(LocalDate.of(2026, 10, 20), ListParse.dueFrom("the 20th", today))
        assertEquals(LocalDate.of(2027, 3, 12), ListParse.dueFrom("12 march", today))
        assertNull(ListParse.dueFrom("banana", today))
    }

    @Test
    fun splittingADueDateOffTheEnd() {
        assertEquals(
            Pair("submit report", LocalDate.of(2026, 10, 23)),
            ListParse.splitDue("submit report by next friday", today)
        )
        assertEquals(Pair("buy 2 apples", null), ListParse.splitDue("buy 2 apples", today))
        assertEquals(Pair("tomorrow", null), ListParse.splitDue("tomorrow", today))
    }

    @Test
    fun listKeys() {
        assertEquals("todo", ListParse.key("to-do"))
        assertEquals("todo", ListParse.key("tasks"))
        assertEquals("todo", ListParse.key(null))
        assertEquals("shopping", ListParse.key("my shopping"))
    }
}
