package io.github.anastaci1a.localica

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import io.github.anastaci1a.localica.browse.*
import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.PlaylistState
import io.github.anastaci1a.localica.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Strict FIRST and predetermined eighth-additional-frame parity; no settled/presented claim. */
class BrowseInputPhaseEqualityTest {
    private val compose = localicaComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(BrowsePreferenceIsolation()).around(compose)

    @Test fun realRowInputsReachRetainedSongsAndMatchFrozenPixels() = compare(false)
    @Test fun realRowInputsReachRetainedAlbumAndMatchFrozenPixels() = compare(true)

    private fun compare(album: Boolean) {
        val tracks = (0..6).map { Track("inputs:$it", "Input song $it", album="Input album", fileName="$it.mp3", relativePath="$it.mp3") }
        val library = LibraryUiState(LibrarySnapshot(folders=listOf(FolderSelection("root","Music")),tracks=tracks),loading=false)
        val playlists = PlaylistState(loading=false)
        val route = if (album) BrowseRoute(MainTab.Albums,
            BrowseQueries.evaluate(library.snapshot, emptyList(), BrowseRoute(MainTab.Albums)).items.single().ref)
            else BrowseRoute(MainTab.Songs)
        var reference by mutableStateOf(true)
        var epoch by mutableIntStateOf(0)
        var pins by mutableStateOf(emptyList<MediaRef>())
        var playing by mutableStateOf<String?>(null)
        var densityMultiplier by mutableFloatStateOf(1f)
        var fontScale by mutableFloatStateOf(1f)
        var blueTheme by mutableStateOf(false)
        var session by mutableStateOf(SessionSelection())
        val work = mutableListOf<String>()
        val observer: (String,Any)->Unit = { kind,_ -> work+=kind }
        val shadowPin = LiveShadowBranchPin()
        lateinit var runtime: GlassRendererRuntime
        lateinit var rootData: CompositionData
        lateinit var inspectionTables: MutableSet<CompositionData>
        compose.mainClock.autoAdvance = false
        compose.setContent {
            rootData = currentComposer.compositionData
            val tables=remember { mutableSetOf<CompositionData>() }
            inspectionTables = tables
            CompositionLocalProvider(LocalInspectionTables provides tables) {
                Box(Modifier.pinLiveShadowBranch(shadowPin)) { LocalicaTheme {
                    runtime = checkNotNull(LocalGlassRendererRuntime.current)
                    val base=MaterialTheme.colorScheme
                    val colors=remember(base,blueTheme) { if(blueTheme) base.copy(onSurface=Color(0xFF154AB5)) else base }
                    val density=LocalDensity.current
                    MaterialTheme(colorScheme=colors) {
                        CompositionLocalProvider(LocalDensity provides Density(density.density*densityMultiplier,fontScale),
                            LocalSessionSelection provides session,LocalBrowseCellWorkObserver provides observer,
                            LocalGlassAppearance provides GlassAppearance(renderer=GlassRendererMode.None)) {
                            key(reference,epoch) {
                                if(reference) ReferenceBrowserScreenP1(library,playlists,route,{},{},{},{_,_,_->},null,null,true,false,
                                    pins=pins,playingEntryId=playing,reducedMotion=true,queryDispatcher=kotlinx.coroutines.Dispatchers.Unconfined)
                                else BrowserScreen(library,playlists,route,{},{},{},{_,_,_->},null,null,true,false,
                                    pins=pins,playingEntryId=playing,reducedMotion=true,queryDispatcher=kotlinx.coroutines.Dispatchers.Unconfined)
                            }
                        }
                    }
                } }
            }
        }
        data class Frame(val pixels:IntArray,val width:Int,val height:Int)
        val names=listOf("initial","pin","selection","playing","density","font-scale","theme")
        val firstReferences = mutableMapOf<Int, Frame>()
        val lastReferences = mutableMapOf<Int, Pair<java.io.File, String>>()
        val cache = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "browse-input-phases-$album-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        fun digest(file: java.io.File): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        fun saveReference(index: Int, frame: Frame) {
            check(index !in lastReferences)
            val file = java.io.File(cache, "$index.argb.gz")
            check(file.createNewFile())
            java.io.DataOutputStream(java.util.zip.GZIPOutputStream(java.io.BufferedOutputStream(file.outputStream()))).use { output ->
                output.writeInt(frame.width); output.writeInt(frame.height)
                val bytes = java.nio.ByteBuffer.allocate(frame.pixels.size * 4)
                bytes.asIntBuffer().put(frame.pixels)
                output.write(bytes.array())
            }
            lastReferences[index] = file to digest(file)
        }
        fun loadReference(index: Int): Frame {
            val (file, hash) = lastReferences.getValue(index)
            assertEquals("Frozen eighth-frame bytes must stay immutable", hash, digest(file))
            return java.io.DataInputStream(java.util.zip.GZIPInputStream(java.io.BufferedInputStream(file.inputStream()))).use { input ->
                val width=input.readInt(); val height=input.readInt()
                val pixels=IntArray(width*height) { input.readInt() }
                assertEquals("No trailing frozen frame bytes", -1, input.read())
                Frame(pixels,width,height)
            }
        }
        fun assertFrame(label: String, expected: Frame, actual: Frame) {
            assertEquals("Width at $label",expected.width,actual.width)
            assertEquals("Height at $label",expected.height,actual.height)
            if(!expected.pixels.contentEquals(actual.pixels)) savePlayerEqualityEvidence("input-phase-$album-$label",expected.pixels,actual.pixels,expected.width,expected.height)
            assertArrayEquals("Exact input frame at $label, album=$album",expected.pixels,actual.pixels)
        }
        var sample = 0
        fun runtimeState(): String = compose.runOnIdle { "requested=${runtime.requested} backend=${runtime.generation.backend} epoch=${runtime.generation.epoch}" }
        fun sourceState(): List<List<Any>> = compose.runOnIdle {
            val sources = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<FrostedBackdrop, Boolean>())
            fun inspect(data: CompositionData) {
                for (group in data.compositionGroups) {
                    sources.addAll(group.data.filterIsInstance<FrostedBackdrop>())
                    inspect(group)
                }
            }
            inspect(rootData)
            inspectionTables.toList().filter { it !== rootData }.forEach(::inspect)
            sources.map { source -> listOf(System.identityHashCode(source), source.recordings, source.revision,
                source.readbackRequest, source.snapshotReadbackRequest, source.snapshot != null,
                source.size.width, source.size.height, source.layer.isReleased,
                source.rendererGeneration.backend.name, source.rendererGeneration.epoch) }.sortedBy { it[0] as Int }
        }
        fun readImage(): Frame {
            compose.waitForIdle()
            val image=compose.onNodeWithTag("browser-list").captureToImage().toPixelMap()
            return Frame(IntArray(image.width*image.height) { i -> image[i%image.width,i/image.width].toArgb() },image.width,image.height)
        }
        fun capture():Frame {
            val n = sample++
            val firstRuntime = runtimeState()
            val firstState = sourceState()
            val first = readImage()
            val index = n % names.size
            if (reference) {
                check(index !in firstReferences)
                firstReferences[index] = first
            } else assertFrame("first-${names[index]}",firstReferences.getValue(index),first)
            var previous = first
            repeat(8) { frame ->
                advanceLiveShadowFrame(compose, shadowPin)
                val next = readImage()
                assertEquals("Diagnostic width", first.width, next.width)
                assertEquals("Diagnostic height", first.height, next.height)
                val fromFirst = first.pixels.indices.count { first.pixels[it] != next.pixels[it] }
                val fromPrevious = previous.pixels.indices.count { previous.pixels[it] != next.pixels[it] }
                android.util.Log.i("PERF2_SETTLED_DIAGNOSTIC", "album=$album reference=$reference sample=$n frame=$frame firstChanged=$fromFirst priorChanged=$fromPrevious beforeFirstReadRuntime=$firstRuntime afterLaterReadRuntime=${runtimeState()} beforeFirstReadSources=$firstState afterLaterReadSources=${sourceState()}")
                if(frame == 7 && fromFirst > 0) savePlayerEqualityEvidence("settled-diagnostic-album$album-reference$reference-sample$n", first.pixels, next.pixels, first.width, first.height)
                if (fromPrevious > 0) savePlayerEqualityEvidence("input-phase-album$album-reference$reference-sample$n-adjacent$frame", previous.pixels, next.pixels, previous.width, previous.height)
                previous = next
            }
            // Eight additional samples remain separate from FIRST; both contracts are strict.
            if(reference) saveReference(index,previous)
            else assertFrame("additional-frame-8-${names[index]}",loadReference(index),previous)
            return first
        }
        fun arm(old:Boolean):List<Frame> {
            compose.runOnIdle { reference=old;epoch++;pins=emptyList();playing=null;densityMultiplier=1f;fontScale=1f;blueTheme=false;session=SessionSelection() }
            repeat(64) { advanceLiveShadowFrame(compose, shadowPin) }
            compose.onNodeWithText("Input song 0").assertIsDisplayed()
            val frames=mutableListOf(capture())
            for(name in names.drop(1)) {
                compose.runOnIdle {
                    work.clear()
                    when(name) {
                        "pin" -> pins=listOf(MediaRef(MediaKind.Song,tracks[0].id))
                        "selection" -> session.select(route.collection,listOf(SongRow(tracks[0].id,tracks[0])))
                        "playing" -> playing=tracks[1].id
                        "density" -> densityMultiplier=1.1f
                        "font-scale" -> fontScale=1.25f
                        "theme" -> blueTheme=true
                    }
                }
                advanceLiveShadowFrame(compose, shadowPin)
                frames+=capture()
                compose.runOnIdle {
                    if (old) assertTrue("Frozen probe must receive $name, album=$album", work.any { it == "row" })
                    android.util.Log.i("PERF2_ROW_INPUT","album=$album reference=$old input=$name rows=${work.count { it=="row" }}")
                }
            }
            return frames
        }
        val original=arm(true)
        val candidate=arm(false)
        original.zip(candidate).forEachIndexed { i,(a,b) ->
            assertEquals("Width at ${names[i]}",a.width,b.width)
            assertEquals("Height at ${names[i]}",a.height,b.height)
            if(!a.pixels.contentEquals(b.pixels)) savePlayerEqualityEvidence("row-input-album$album-${names[i]}",a.pixels,b.pixels,a.width,a.height)
            assertArrayEquals("Frozen pixels after ${names[i]}, album=$album",a.pixels,b.pixels)
        }
    }
}
