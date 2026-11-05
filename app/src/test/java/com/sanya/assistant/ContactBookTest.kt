package com.sanya.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactBookTest {

    private fun e(id: Long, name: String, number: String = "9876543210") =
        ContactBook.Entry(id, name, number, "mobile")

    private fun names(people: List<ContactBook.Person>) = people.map { it.name }

    // ---------------- who fits what was said ----------------

    @Test
    fun marksMatchTheCommandRunner() {
        assertEquals(100, ContactBook.score("Ravi Kumar", "ravi kumar"))
        assertEquals(90, ContactBook.score("Ravi Kumar", "kumar"))
        assertEquals(80, ContactBook.score("Preethi", "pree"))
        assertEquals(55, ContactBook.score("Preethi", "pretty"))
        assertEquals(0, ContactBook.score("Amma", "ravi"))
    }

    @Test
    fun twoRavisAreAsked() {
        val people = ContactBook.rank(listOf(e(1, "Ravi Kumar"), e(2, "Ravi Teja"), e(3, "Amma")), "ravi")
        assertEquals(listOf("Ravi Kumar", "Ravi Teja"), names(people))
        assertEquals(listOf("Ravi Kumar", "Ravi Teja"), names(ContactBook.ambiguous(people)))
    }

    @Test
    fun aFullNameIsClear() {
        val people = ContactBook.rank(listOf(e(1, "Ravi Kumar"), e(2, "Ravi Teja")), "ravi kumar")
        assertEquals(emptyList<String>(), names(ContactBook.ambiguous(people)))
    }

    @Test
    fun anExactNameBeatsALongerOne() {
        val people = ContactBook.rank(listOf(e(1, "Ravi"), e(2, "Ravi Kumar")), "ravi")
        assertEquals(emptyList<String>(), names(ContactBook.ambiguous(people)))
    }

    @Test
    fun theSameNameTwiceIsNotAWhoQuestion() {
        val people = ContactBook.rank(listOf(e(1, "Amma", "111"), e(2, "Amma", "222")), "amma")
        assertEquals(emptyList<String>(), names(ContactBook.ambiguous(people)))
    }

    @Test
    fun aSharedSurnameIsAsked() {
        val people = ContactBook.rank(
            listOf(e(1, "Ravi Kumar"), e(2, "Suresh Kumar"), e(3, "Amma")),
            "kumar"
        )
        assertEquals(listOf("Ravi Kumar", "Suresh Kumar"), names(ContactBook.ambiguous(people)))
    }

    @Test
    fun aGuessBySoundAloneIsNotAsked() {
        val people = ContactBook.rank(listOf(e(1, "Preethi Kumar"), e(2, "Preeti Sharma")), "pretty")
        assertEquals(emptyList<String>(), names(ContactBook.ambiguous(people)))
    }

    @Test
    fun oneRowPerPerson() {
        val people = ContactBook.rank(listOf(e(1, "Amma", "111"), e(1, "Amma", "222"), e(2, "Ravi")), "amma")
        assertEquals(1, people.size)
        assertEquals(2, people[0].entries.size)
        assertEquals(2, ContactBook.distinctNumbers(people[0]).size)
    }

    @Test
    fun numbersCompareByTheirLastTenDigits() {
        assertEquals("9876543210", ContactBook.digits("+91 98765 43210"))
        assertEquals("9876543210", ContactBook.digits("098765 43210"))
        assertEquals("12345", ContactBook.digits("12345"))
    }

    // ---------------- choosing from your answer ----------------

    private val who = listOf(
        Choose.Option("Ravi Kumar", "Ravi Kumar", Choose.words("Ravi Kumar")),
        Choose.Option("Ravi Teja", "Ravi Teja", Choose.words("Ravi Teja"))
    )

    private val numbers = listOf(
        Choose.Option("mobile ending 3 2 1 0", "9876543210", listOf("mobile", "3210")),
        Choose.Option("home ending 7 7 8 1", "9123457781", listOf("home", "7781"))
    )

    @Test
    fun byPosition() {
        assertEquals("Ravi Teja", Choose.pick(who, "the second one")?.key)
        assertEquals("Ravi Kumar", Choose.pick(who, "first")?.key)
        assertEquals("Ravi Teja", Choose.pick(who, "last")?.key)
        assertEquals("Ravi Teja", Choose.pick(who, "number two please")?.key)
    }

    @Test
    fun byAWordOnlyOneHas() {
        assertEquals("Ravi Kumar", Choose.pick(who, "kumar")?.key)
        assertEquals("Ravi Teja", Choose.pick(who, "Teja")?.key)
        assertEquals("Ravi Kumar", Choose.pick(who, "ravi kumar")?.key)
    }

    @Test
    fun aWordBothHaveIsNotAnAnswer() {
        assertNull(Choose.pick(who, "ravi"))
    }

    @Test
    fun byTheEndOfANumberOrItsLabel() {
        assertEquals("9876543210", Choose.pick(numbers, "3 2 1 0")?.key)
        assertEquals("9123457781", Choose.pick(numbers, "7781")?.key)
        assertEquals("9123457781", Choose.pick(numbers, "home")?.key)
        assertEquals("9876543210", Choose.pick(numbers, "the mobile one")?.key)
        assertEquals("9123457781", Choose.pick(numbers, "second")?.key)
    }

    @Test
    fun joinOr() {
        assertEquals("a or b", Choose.joinOr(listOf("a", "b")))
        assertEquals("a, b or c", Choose.joinOr(listOf("a", "b", "c")))
    }
}
