package io.github.anastaci1a.localica.ui.components
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.engine.*
/** U07.00 typed popup contract (D39–D43); no production caller before U11.00 (D03). [PopupVariant.Choice] is the single-select picker (#642): a titleless text list applying on tap. */
enum class HomeKind { Surface,Quiet,Contextual,Detached,Page,Search }; enum class PopupVariant { Menu,Choice,Dialog,Sheet,Page,Search }
/** A return home. A physical home opens only while a live trigger node is bound; return keeps the last valid binding. Detached/Page are stock origins. A Quiet trigger's node materializes on press, so opening continues its presented shape and material. */
@Stable class HomeRef internal constructor(val key: Any,val kind: HomeKind) {
    internal var node: SurfaceView? by mutableStateOf(null); private set; internal var host: HostKey?=null; private set; private var bound by mutableStateOf(false)
    val available: Boolean get()=kind==HomeKind.Detached || kind==HomeKind.Page || bound && node?.retained==false
    internal fun bind(node: SurfaceView,host: HostKey) { this.node=node; this.host=host; bound=true }
    internal fun unbind(node: SurfaceView) { require(bound && node===this.node) { "Only the bound node unbinds its home" }; bound=false } }
@Composable fun rememberHomeRef(key: Any,kind: HomeKind=HomeKind.Surface)=remember(key,kind) { HomeRef(key,kind) }
/** [shared] keys the content element that is the home's face (I-new): it opens out of the trigger, and [select] makes the chosen row the one that closes back into it. */
@Stable interface PopupState { val visible: Boolean; val home: HomeRef?; val shared: Any?; val expansion: ExpansionView; fun open(home: HomeRef,shared: Any?=null); fun dismiss(); fun select(shared: Any) }
internal class PopupController(val key: Any,internal val motion: PopupMotion): PopupState {
    override var visible by mutableStateOf(false); private set; override var home: HomeRef? by mutableStateOf(null); private set; override var shared: Any? by mutableStateOf(null); private set; internal var generation by mutableLongStateOf(0L); private set
    override val expansion: ExpansionView get()=motion.expansion
    override fun open(home: HomeRef,shared: Any?) { check(home.available) { "Open from a bound physical home or a stock origin" }; if(visible && home===this.home && shared==this.shared) return; motion.show(true); this.home=home; this.shared=shared; visible=true; generation++ }
    override fun dismiss() { if(!visible) return; motion.show(false); visible=false; generation++ }; override fun select(shared: Any) { check(visible) { "Select inside an open popup" }; this.shared=shared; dismiss() } }
@Composable fun rememberPopupState(key: Any): PopupState { val motion=rememberPopupMotion(key); return remember(motion) { PopupController(key,motion) } }
@Immutable data class PopupModel(val key: Any,val variant: PopupVariant,val onDismissRequest: ()->Boolean={ true },val title: String?=null,val measurement: (@Composable ()->Unit)?=null,val footer: (@Composable ()->Unit)?=null) {
    init { require(variant!=PopupVariant.Choice || title==null && footer==null) { "A choice menu is a titleless text list that applies on tap" } } }
/** No contact API (D42). [scrubs]: press-and-slide with release-to-select, only in menus anchored on a control (#642 Q5). User intents pass [PopupModel.onDismissRequest] once; a stale scope does nothing. */
@Stable interface PopupScope { val interactive: Boolean; val scrubs: Boolean; fun dismiss(); fun select(shared: Any) }
internal class PopupHostScope(private val state: PopupController,private val model: PopupModel): PopupScope { private val generation=state.generation
    override val interactive get()=state.visible && state.generation==generation
    override val scrubs get()=(model.variant==PopupVariant.Menu || model.variant==PopupVariant.Choice) && state.home?.kind.let { it==HomeKind.Surface || it==HomeKind.Quiet || it==HomeKind.Contextual }
    override fun dismiss() { if(interactive && model.onDismissRequest()) state.dismiss() }; override fun select(shared: Any) { if(interactive && model.onDismissRequest()) state.select(shared) } }
/** D40: the root installs the stock body presenter beside GlassDialogHost; screens cannot replace it. It receives the scope-bound body. */
internal typealias PopupBodyPresenter=@Composable (state: PopupState,model: PopupModel,body: @Composable ()->Unit)->Unit; internal val LocalPopupBodyPresenter=staticCompositionLocalOf<PopupBodyPresenter?> { null }
@Composable fun Popup(state: PopupState,model: PopupModel,content: @Composable PopupScope.()->Unit) { val present=checkNotNull(LocalPopupBodyPresenter.current) { "The root installs the stock popup body presenter" }
    val owner=requireNotNull(state as? PopupController) { "PopupState comes from rememberPopupState" }; require(owner.key==model.key) { "A popup model keeps its state's key" }
    val scope=remember(owner,model,owner.generation) { PopupHostScope(owner,model) }; present(state,model) { scope.content() } }
