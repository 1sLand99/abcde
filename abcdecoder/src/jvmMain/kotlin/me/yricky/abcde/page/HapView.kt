package me.yricky.abcde.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.yricky.abcde.AppState
import me.yricky.abcde.HapSession
import me.yricky.abcde.desktop.DesktopUtils
import me.yricky.abcde.ui.*
import me.yricky.abcde.util.*
import me.yricky.oh.common.TreeStruct
import me.yricky.oh.common.toByteArray
import me.yricky.oh.hapde.Constant.DIR_ETS
import me.yricky.oh.hapde.Constant.DIR_LIB
import me.yricky.oh.hapde.Constant.DIR_RES
import me.yricky.oh.hapde.Constant.ENTRY_MODULE_JSON
import me.yricky.oh.hapde.Constant.ENTRY_PACK_INFO
import me.yricky.oh.hapde.Constant.ENTRY_RES_INDEX
import me.yricky.oh.hapde.HapConfig
import me.yricky.oh.hapde.HapSignBlocks
import me.yricky.oh.resde.ResIndexBuf
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSSignedData
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.streams.asSequence

class HapView(val hapFile:SelectedHapFile):Page() {
    companion object{
        val json = Json { ignoreUnknownKeys = true }
    }
    private val hap:ZipFile get() = hapFile.hap.getOrThrow()

    override val navString: String = asNavString("HAP", hap.name)
    override val name: String = hap.name

    val tree by lazy {
        TreeModel(TreeStruct(hap.stream().asSequence().asIterable(), pathOf = { it.name })).apply {  }
    }
    var list by mutableStateOf(tree.buildFlattenList())
        private set
    var filter by mutableStateOf("")
        private set
    fun isFilterMode() = filter.isNotEmpty()

    fun setNewFilter(str:String){
        filter = str
        if(!isFilterMode()){
            list = tree.buildFlattenList()
        } else {
            list = tree.buildFlattenList{ it.pathSeg.contains(filter) }
        }
    }

    fun toggleExpand(node: TreeStruct.TreeNode<ZipEntry>){
        if(!isFilterMode()){
            tree.toggleExpand(node)
            list = tree.buildFlattenList()
        }
    }

    val config by lazy {
        runCatching {
            json.decodeFromString(HapConfig.serializer(),hap.getInputStream(hap.getEntry(ENTRY_MODULE_JSON)).reader().readText())
        }.onFailure {
            System.err.println(it.stackTraceToString())
        }.getOrNull()
    }

    val signBlocks by lazy {
        runCatching { HapSignBlocks.from(hapFile.buf) }.getOrNull()
    }

    private val entryCache = mutableMapOf<String,TypedFile>()
    private val entryCacheMutex = Mutex()
    private suspend inline fun <reified T:TypedFile> getEntryFile(entryName:String, crossinline getter:(File) -> T):T? = withContext(Dispatchers.IO){
        entryCacheMutex.withLock {
            val ret = entryCache[entryName]
            if(ret == null){
                val entry = tree.tree.pathMap[entryName]?.value ?: return@withContext null
                val file = File.createTempFile("zipEntry","tmp",DesktopUtils.tmpDir)
                println("tmpFile:${file.absolutePath}")
                file.deleteOnExit()
                hap.getInputStream(entry).transferTo(file.outputStream())
                return@withLock getter(file).also {
                    entryCache[entryName] = it
                }
            }
            return@withLock (ret as? T) ?: getter(ret.file).also {
                entryCache[entryName] = it
            }
        }
    }

    suspend fun getDefaultResourceIndex(): ResIndexBuf?{
        return getEntryFile(ENTRY_RES_INDEX) { SelectedIndexFile(it) }?.resBuf
    }
    private val thumbnailCache = mutableStateMapOf<String,Painter>()
    private val thumbnailCacheMutex = Mutex()
    @Composable
    fun loadPainterInZip(entryName:String):Painter {
        val node = tree.tree.pathMap[entryName] ?: return Icons.image()
        val density = LocalDensity.current
        return thumbnailCache[node.value.name] ?: produceState(Icons.image()) {
            withContext(Dispatchers.IO + NonCancellable) { thumbnailCacheMutex.withLock {
                val cache = thumbnailCache[node.value.name]
                if(cache != null) { value = cache } else kotlin.runCatching {
                    hap.getInputStream(node.value).use {
                        if(entryName.endsWith(".svg")){
                            loadSvgPainter(it,density)
                        }else BitmapPainter(loadImageBitmap(it))
                    }
                }.onFailure {
                    println("load failed: ${node.value.name} \nmsg:${it.message}")
                }.onSuccess {
                    thumbnailCache[node.value.name] = it
                    value = it
                }
            } }
        }.value
    }

    //Hap的图标缓存
    private var iconEntryCache:String? = null
    @Composable
    fun iconDrawable(tag:String = ""):Painter?{
        return config?.let { hapConfig ->
            val iconEntry by produceState(iconEntryCache){
                if(value == null){
                    value = withContext(Dispatchers.IO + NonCancellable){
                        println("$tag:icon:${hapConfig.app.icon.fileName}")
                        getEntryFile(ENTRY_RES_INDEX){ f -> SelectedIndexFile(f, ENTRY_RES_INDEX) }
                            ?.resBuf?.resMap?.entries
                            ?.firstOrNull { it.value.any { it.fileName == hapConfig.app.icon.fileName } }?.value
                            ?.firstOrNull()?.data?.asString?.takeIf {
                                println(it)
                                it.startsWith("${hapConfig.module.name}/")
                            }
                            ?.removePrefix("${hapConfig.module.name}/").also {
                                println("$tag:iconEntry:${it}")
                            }
                    }.also {
                        iconEntryCache = it
                    }
                }

            }
            iconEntry?.let {
                loadPainterInZip(it)
            }
        }
    }

    private val tabState = mutableIntStateOf(0)

    @Composable
    override fun Page(modifier: Modifier, hapSession: HapSession, appState: AppState) {
        VerticalTabAndContent(modifier, tabState,
            listOfNotNull(
                composeSelectContent {
                    Image(Icons.showAsTree(), null, Modifier.fillMaxSize(), colorFilter = grayColorFilter)
                } to composeContent {
                    Row {
                        FileTree(Modifier.weight(1f).fillMaxHeight(),hapSession, appState)
                        val hapConfig = config
                        if(hapConfig != null) Column(Modifier.width(DP.SIDEBAR_WIDTH).padding(DP.VIEW_SPACE).verticalScroll(
                            rememberScrollState()
                        )) {
                            TitleCard(title = "App"){
                                Image(iconDrawable() ?: Icons.image(),null,
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(DP.VIEW_SPACE).size(80.dp)
                                )
                                val name by produceState(hapConfig.app.label.indexStr){
                                    println("label:${hapConfig.app.label.fileName}")
                                    value = getEntryFile(ENTRY_RES_INDEX){ f -> SelectedIndexFile(f, ENTRY_RES_INDEX) }
                                        ?.resBuf?.resMap?.entries
                                        ?.firstOrNull { it.value.any { it.fileName == hapConfig.app.label.fileName } }?.value
                                        ?.firstOrNull()?.data?.asString ?: hapConfig.app.label.indexStr
                                }
                                Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
                                OutlinedTextField(
                                    value = hapConfig.app.bundleName,
                                    onValueChange = {},
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("bundleName") },
                                    maxLines = 1,
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = "${hapConfig.app.versionName}(${hapConfig.app.versionCode})",
                                    onValueChange = {},
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("version") },
                                    maxLines = 1,
                                    singleLine = true
                                )
                            }
                            Spacer(Modifier.height(DP.VIEW_SPACE))
                            TitleCard(title = "Module"){
                                OutlinedTextField(
                                    value = hapConfig.module.name,
                                    onValueChange = {},
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("name") },
                                    maxLines = 1,
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = hapConfig.module.type,
                                    onValueChange = {},
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("type") },
                                    maxLines = 1,
                                    singleLine = true,
                                )
                            }
                        }
                    }
                },
                if (experimentalFeatures()){
                    composeSelectContent {
                        Image(Icons.key(), null, Modifier.fillMaxSize(), colorFilter = grayColorFilter)
                    } to composeContent {
                        Column(Modifier.padding(end = DP.VIEW_SPACE, bottom = DP.VIEW_SPACE)) {
                            Text("签名信息（WIP）", style = MaterialTheme.typography.titleLarge)
                            Row {

                                TitleCard(Modifier.fillMaxHeight().weight(3f),"Profile") {
                                    val profile = remember {
                                        runCatching { json.decodeFromString(JsonElement.serializer(),signBlocks?.getProfileContent() ?: "null").toTreeStruct() }.getOrDefault(
                                            TreeStruct(emptyList())
                                        ).let { TreeModel(it) }
                                    }
                                    JsonTree(Modifier.fillMaxSize(), profile)
                                }
                                Spacer(Modifier.size(DP.VIEW_SPACE))
                                TitleCard(Modifier.weight(2f),"Certificates"){
                                    val certs = remember {
                                        val conv = JcaX509CertificateConverter()
                                        signBlocks?.getSignatureSchemeBlock()
                                            ?.content?.toByteArray()
                                            ?.let { CMSSignedData(it) }
                                            ?.certificates?.getMatches(null)
                                            ?.map { conv.getCertificate(it) } ?: emptyList()
                                    }
                                    LazyColumnWithScrollBar {
                                        itemsIndexed(certs){ i,c ->
                                            OutlinedTextField(
                                                value = "Subject: " + c.subjectX500Principal
                                                        + "\n" + "Issuer: " + c.issuerX500Principal
                                                        + "\n" + "SerialNumber: " + c.serialNumber.toString(16)
                                                        + "\n" + "Cert Version: V" + c.version,
                                                onValueChange = {},
                                                label = {
                                                    Text("certificate #${i}")
                                                },
                                                textStyle = codeStyle,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                            }
                        }
                    }
                } else null
            )
        )
    }

    @Composable
    fun FileTree(modifier: Modifier, hapSession: HapSession, appState: AppState){
        TreeItemList(modifier,list,
            expand = { isFilterMode() || tree.isExpand(it) },
            applyContent = { content ->
                content()
                item {
                    Box(Modifier.fillMaxWidth().height(120.dp)){
                        Text("${tree.tree.pathMap.size}个文件",Modifier.align(Alignment.Center))
                    }
                }
            },
            onClick = {
                if (it is TreeStruct.LeafNode) {
                    val path = it.value.name
                    if(path.endsWith(".abc")){
                        appState.coroutineScope.launch {
                            kotlin.runCatching {
                                hapSession.openPage(AbcView(
                                    getEntryFile(path){ f ->SelectedAbcFile(f,path) }!!.abcBuf,
                                    hapSession
                                ))
//                                getDefaultResourceIndex()
                            }.onFailure {
                                it.printStackTrace()
                            }
                        }
                    } else if(path == ENTRY_RES_INDEX){
                        appState.coroutineScope.launch {
                            hapSession.openPage(ResIndexView(
                                getEntryFile(path){ f -> SelectedIndexFile(f,path) }!!.resBuf,
                                path,
                                hapSession
                            ))
                        }
                    }
                } else if(it is TreeStruct.TreeNode){
                    toggleExpand(it)
                }
            }) {
            when (val node = it) {
                is TreeStruct.LeafNode<ZipEntry> -> {
                    Image(iconOf(node), null, modifier = Modifier.padding(end = 2.dp).size(18.dp))
                    Text(it.pathSeg, maxLines = 1, overflow = TextOverflow.Ellipsis,modifier = Modifier.weight(1f))
                    Text(
                        remember { node.value.size.toByteSizeFormat() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                is TreeStruct.TreeNode<ZipEntry> -> {
                    Image(iconOf(node), null, modifier = Modifier.padding(end = 2.dp).size(18.dp))
                    Text(it.pathSeg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    fun iconOf(node:TreeStruct.Node<ZipEntry>):Painter{
        return when (node) {
            is TreeStruct.TreeNode<ZipEntry> -> {
                if(node.parent == tree.tree.rootNode){
                    when(node.pathSeg){
                        DIR_ETS -> Icons.moduleGroup()
                        DIR_LIB -> Icons.libraryFolder()
                        DIR_RES -> Icons.resourcesRoot()
                        else -> Icons.folder()
                    }
                } else {
                    Icons.folder()
                }
            }
            is TreeStruct.LeafNode<ZipEntry> -> {
                when(node.value.name){
                    ENTRY_MODULE_JSON -> Icons.info()
                    ENTRY_PACK_INFO -> Icons.info()
                    else -> {
                        if(node.pathSeg == ENTRY_RES_INDEX){
                            Icons.indexCluster()
                        } else if(node.value.name.endsWith(".so") && !node.value.isDirectory) {
                            Icons.library()
                        } else if(node.value.name.endsWith(".abc") && !node.value.isDirectory) {
                            Icons.listFiles()
                        } else if(node.value.name.endsWith(".json") && !node.value.isDirectory) {
                            Icons.json()
                        } else if(node.value.name.isImageFilePath() && !node.value.isDirectory) {
                            loadPainterInZip(node.value.name)
                        } else {
                            Icons.anyType()
                        }
                    }
                }
            }
        }
    }
}