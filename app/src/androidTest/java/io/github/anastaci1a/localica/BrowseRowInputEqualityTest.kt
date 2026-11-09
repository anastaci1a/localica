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

/** A row skip boundary must retain every real input update, including ambient-only changes. */
class BrowseRowInputEqualityTest {
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
        compose.setContent {
            val tables=remember { mutableSetOf<CompositionData>() }
            CompositionLocalProvider(LocalInspectionTables provides tables) {
                Box(Modifier.pinLiveShadowBranch()) { LocalicaTheme {
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
        fun capture():Frame {
            compose.waitForIdle()
            val image=compose.onNodeWithTag("browser-list").captureToImage().toPixelMap()
            return Frame(IntArray(image.width*image.height) { i -> image[i%image.width,i/image.width].toArgb() },image.width,image.height)
        }
        val names=listOf("initial","pin","selection","playing","density","font-scale","theme")
        fun arm(old:Boolean):List<Frame> {
            compose.runOnIdle { reference=old;epoch++;pins=emptyList();playing=null;densityMultiplier=1f;fontScale=1f;blueTheme=false;session=SessionSelection() }
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
                frames+=capture()
                compose.runOnIdle {
                    assertTrue("Real $name update must reach retained rows, reference=$old album=$album",work.any { it=="row" })
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
