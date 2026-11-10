package io.github.anastaci1a.localica.ui.layout.menu

import io.github.anastaci1a.localica.ui.spec.MarkerTone
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * #757 M0 acceptance checks on the concrete model (DESIGN 3.2): content cannot be constructed outside the model
 * file, is not copyable, holds no caller object, and admission is the only factory.
 */
class MenuModelBoundaryTest {
    private val contracts = listOf(PageContent::class.java, BodyContent::class.java, TabContent::class.java, SectionContent::class.java,
        OptionContent::class.java, RowContent::class.java, InfoLineContent::class.java, StatContent::class.java, LegendEntryContent::class.java,
        OrderedItemContent::class.java, PopupContent::class.java, AcceptedPage::class.java, PageDraft::class.java, PopupSpec::class.java)
    private val identities = listOf(MenuIcon::class.java, PageRef::class.java, TabId::class.java, Datum::class.java, InfoKey::class.java,
        PreviewId::class.java, HostedPopupId::class.java, RowKeySource::class.java)

    /** Every class compiled into the model package. */
    private val classes: List<Class<*>> by lazy {
        val root = File(AcceptedPage::class.java.protectionDomain.codeSource.location.toURI())
        val path = AcceptedPage::class.java.packageName.replace('.', '/')
        // The unit-test classpath holds the app classes as a directory or as a jar, depending on the Gradle task graph.
        val names = if (root.isDirectory) File(root, path).listFiles { file -> file.name.endsWith(".class") }!!.map { it.name }
            else java.util.zip.ZipFile(root).use { zip -> zip.entries().toList().map { it.name }
                .filter { it.startsWith("$path/") && it.endsWith(".class") && '/' !in it.removePrefix("$path/") }.map { it.removePrefix("$path/") } }
        assertTrue(names.size > 60)
        names.map { Class.forName("${AcceptedPage::class.java.packageName}.${it.removeSuffix(".class")}", false, javaClass.classLoader) }
    }
    private val implementations get() = classes.filter { type -> !type.isInterface && contracts.any { it.isAssignableFrom(type) } }

    @Test fun everyContentContractIsSealedAndImplementedOnlyInTheModelFile() {
        val source = File("src/main/java/io/github/anastaci1a/localica/ui/layout/menu/MenuModel.kt").readText()
        fun leaves(type: Class<*>): List<Class<*>> = if (type.isInterface) {
            assertTrue("${type.simpleName} must be sealed", type.isSealed)
            type.permittedSubclasses.flatMap(::leaves)
        } else listOf(type)
        val reachable = contracts.flatMap(::leaves).toSet()
        assertEquals("every implementation is a permitted subclass of a sealed contract", implementations.toSet(), reachable)
        assertTrue(reachable.size >= 30)
        for (type in reachable) assertTrue("${type.simpleName} is declared in MenuModel.kt as a file-private class",
            Regex("""^private class ${type.simpleName}\(""", RegexOption.MULTILINE).containsMatchIn(source))
    }

    @Test fun implementationsAreFilePrivateFinalAndNotDataClasses() {
        for (type in implementations) {
            assertTrue("${type.simpleName} is final", Modifier.isFinal(type.modifiers))
            assertFalse("${type.simpleName} is not visible outside its file", Modifier.isPublic(type.modifiers))
            val methods = type.declaredMethods.map { it.name }
            assertFalse("${type.simpleName} has no copy", methods.any { it == "copy" || it.startsWith("copy$") || it.startsWith("component") })
        }
    }

    @Test fun admitIsTheOnlyFactory() {
        for (contract in contracts) {
            val companion = contract.declaredClasses.firstOrNull { it.simpleName == "Companion" }
            if (contract == AcceptedPage::class.java) assertEquals(listOf("admit"), companion!!.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.map { it.name })
            else assertNull("${contract.simpleName} has no companion factory", companion)
        }
        // No public or internal function of the package returns content except through admission. Compiler-made accessors
        // (synthetic, not callable from Kotlin source) are outside this source-level guarantee.
        val leaking = classes.filter { Modifier.isPublic(it.modifiers) }.flatMap { type -> type.declaredMethods.filter { method ->
            Modifier.isPublic(method.modifiers) && !method.isSynthetic && Modifier.isStatic(method.modifiers) && contracts.minus(listOf(PageDraft::class.java, PopupSpec::class.java))
                .any { it.isAssignableFrom(method.returnType) } }.map { "${type.simpleName}.${it.name}" } }
        assertEquals(emptyList<String>(), leaking)
        assertEquals("a draft exposes nothing", emptyList<String>(), PageDraft::class.java.declaredMethods.map { it.name })
    }

    @Test fun contentStoresOnlyPlainValuesAndNeverACallerObjectOrALambda() {
        val allowed = setOf(String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            java.lang.Float::class.java, List::class.java, Value::class.java, RecordSnapshot::class.java, BindingTable::class.java, ContentIndex::class.java)
        for (type in implementations.filter { RowContent::class.java.isAssignableFrom(it) || PopupContent::class.java.isAssignableFrom(it) ||
            listOf(PageContent::class.java, BodyContent::class.java, TabContent::class.java, SectionContent::class.java, OptionContent::class.java,
                InfoLineContent::class.java, StatContent::class.java, LegendEntryContent::class.java, OrderedItemContent::class.java).any { c -> c.isAssignableFrom(it) } })
            for (field in type.declaredFields.filterNot { it.name.startsWith("$") }) {
                val fieldType = field.type
                assertFalse("${type.simpleName}.${field.name} stores an identity interface", identities.any { it.isAssignableFrom(fieldType) })
                assertFalse("${type.simpleName}.${field.name} stores a function", Function::class.java.isAssignableFrom(fieldType))
                assertTrue("${type.simpleName}.${field.name}: ${fieldType.simpleName}", fieldType in allowed || fieldType.isEnum || contracts.any { it.isAssignableFrom(fieldType) })
            }
    }

    @Test fun identitiesAreSnapshottedSoAMutableImplementationCannotChangeAdmittedContent() {
        class Mutable(override var key: String, override var title: String) : PageRef, MenuIcon
        class MutableSetting(override var id: String, override var title: String) : SettingKey
        val page = Mutable("Main", "Title")
        val icon = Mutable("icon.one", "")
        val setting = MutableSetting("Setting", "Shown title")
        val accepted = accepted(page(page) { section("a") { action(setting, icon) {}; nav(page) } })
        val before = accepted.content
        page.key = "Other"; page.title = "Changed"; icon.key = "icon.two"; setting.id = "Renamed"; setting.title = "Changed"
        assertEquals("Main", accepted.content.pageKey)
        assertEquals("Title", accepted.content.title)
        val action = accepted.content.row<ActionContent>()
        assertEquals(listOf("Main/a/setting:Setting", "Shown title", "icon.one"), listOf(action.key.path, action.title, action.iconKey))
        assertEquals("Main", accepted.content.row<NavContent>().target)
        assertSame(before, accepted.content)
    }

    @Test fun contentIsImmutableAndCopiesCallerCollections() {
        val options = mutableListOf(Option("a", "A"), Option("b", "B"))
        val items = mutableListOf(OrderedItem("x", "X"))
        val accepted = accepted(page(TestPage.Main) { section("a") {
            choice(local("c", "Choice"), options, "a", {})
            orderedList(local("o"), items, { _, _ -> })
            legend(local("l")) { entry("m", MarkerTone.Neutral, "M", Value.Ready(1L)) }
        } })
        options.clear(); items.clear()
        assertEquals(2, accepted.content.row<ChoiceContent>().options.size)
        assertEquals(1, accepted.content.row<OrderedListContent>().items.size)
        @Suppress("UNCHECKED_CAST")
        val sections = (accepted.content.body as SectionsBody).sections
        for (list in listOf(sections, sections.single().rows, accepted.content.row<ChoiceContent>().options,
            accepted.content.row<OrderedListContent>().items, accepted.content.row<LegendContent>().entries))
            assertThrows(UnsupportedOperationException::class.java) { (list as MutableList<Any?>).clear() }
    }

    @Test fun contentHasStructuralEqualityAndBindingsAreNotPartOfIt() {
        val a = accepted(everyKind(onToggle = {}))
        val b = accepted(everyKind(onToggle = { error("another callback") }))
        assertNotSame(a.content, b.content)
        assertEquals(a.content, b.content)
        assertEquals(a.content.hashCode(), b.content.hashCode())
        assertNotEquals(a.content, accepted(everyKind(toggled = true)).content)
        assertEquals(a.content.rows(), b.content.rows())
    }
}
